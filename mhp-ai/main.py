import json
import os

import httpx
import redis.asyncio as aioredis
from dotenv import load_dotenv
from fastapi import FastAPI, Header, HTTPException
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import StreamingResponse
from pydantic import BaseModel

load_dotenv()

from agent import build_lc_history, create_agent, request_token

ACCOUNT_URL = os.getenv("ACCOUNT_URL", "http://host.docker.internal:8081")
REDIS_URL = os.getenv("REDIS_URL", "redis://redis:6379")
HISTORY_TTL = 86400     # 24 小时
HISTORY_MAX = 20        # 最多保留 20 条消息

app = FastAPI(title="MHP AI Agent")

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_methods=["*"],
    allow_headers=["*"],
)

redis_client = aioredis.from_url(REDIS_URL, decode_responses=True)


# ── 工具函数 ──────────────────────────────────────────────────────────────────

async def validate_token(token: str) -> int:
    """调用 mhp-account 验证 token，返回 userId；失败则抛 HTTPException(401)。"""
    try:
        async with httpx.AsyncClient() as client:
            r = await client.get(
                f"{ACCOUNT_URL}/internal/user/current",
                headers={"token": token},
                timeout=5.0,
            )
        if r.status_code != 200:
            raise HTTPException(status_code=401, detail="token 无效或已过期，请重新登录")
        result = r.json()
        if result.get("code") != 200 or result.get("data") is None:
            raise HTTPException(status_code=401, detail="token 无效或已过期，请重新登录")
        return int(result["data"])
    except HTTPException:
        raise
    except Exception:
        raise HTTPException(status_code=503, detail="认证服务暂时不可用，请稍后重试")


async def get_history(user_id: int) -> list:
    raw = await redis_client.get(f"ai:conv:{user_id}")
    if not raw:
        return []
    return json.loads(raw)


async def save_history(user_id: int, history: list):
    if len(history) > HISTORY_MAX:
        history = history[-HISTORY_MAX:]
    await redis_client.setex(
        f"ai:conv:{user_id}",
        HISTORY_TTL,
        json.dumps(history, ensure_ascii=False),
    )


# ── 请求模型 ──────────────────────────────────────────────────────────────────

class ChatRequest(BaseModel):
    message: str


# ── 路由 ──────────────────────────────────────────────────────────────────────

@app.post("/chat")
async def chat(req: ChatRequest, token: str = Header(...)):
    user_id = await validate_token(token)
    history = await get_history(user_id)
    lc_history = build_lc_history(history)

    async def generate():
        # 在 generator 内部 set，确保 ContextVar 在当前执行上下文中可见
        ctx_tok = request_token.set(token)
        full_response_parts: list[str] = []
        try:
            agent_executor = create_agent()
            async for event in agent_executor.astream_events(
                {"input": req.message, "chat_history": lc_history},
                version="v1",
            ):
                if event["event"] == "on_chat_model_stream":
                    chunk = event["data"]["chunk"]
                    text = chunk.content
                    if text:
                        full_response_parts.append(text)
                        payload = json.dumps({"type": "token", "content": text}, ensure_ascii=False)
                        yield f"data: {payload}\n\n"

            # LLM 流结束，先发 done 让前端关闭 cursor，再持久化历史
            yield f"data: {json.dumps({'type': 'done'})}\n\n"

        except Exception as e:
            error_payload = json.dumps({"type": "error", "content": str(e)}, ensure_ascii=False)
            yield f"data: {error_payload}\n\n"
        finally:
            request_token.reset(ctx_tok)
            # 持久化历史放 finally：无论成功还是异常，只要有内容就保存；
            # 失败静默忽略，不影响已返回给前端的消息
            if full_response_parts:
                ai_response = "".join(full_response_parts)
                try:
                    await save_history(
                        user_id,
                        history + [
                            {"role": "human", "content": req.message},
                            {"role": "ai", "content": ai_response},
                        ],
                    )
                except Exception:
                    pass

    return StreamingResponse(
        generate(),
        media_type="text/event-stream",
        headers={
            "Cache-Control": "no-cache",
            "Connection": "keep-alive",
            "X-Accel-Buffering": "no",
        },
    )


@app.delete("/clear")
async def clear_history(token: str = Header(...)):
    user_id = await validate_token(token)
    await redis_client.delete(f"ai:conv:{user_id}")
    return {"code": 200, "msg": "对话历史已清除"}


@app.get("/health")
async def health():
    return {"status": "ok"}
