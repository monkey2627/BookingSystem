import os
from contextvars import ContextVar

import httpx
from langchain.tools import tool
from langchain_core.messages import HumanMessage, AIMessage
from langchain_core.prompts import ChatPromptTemplate, MessagesPlaceholder
from langchain.agents import create_tool_calling_agent, AgentExecutor
from langchain_openai import ChatOpenAI

GATEWAY_URL = os.getenv("GATEWAY_URL", "http://host.docker.internal:80")

# 每个请求独立存储用户 token，供 Tool 透传给 Java API
request_token: ContextVar[str] = ContextVar("request_token", default="")


def _auth_headers() -> dict:
    return {"token": request_token.get()}


# ── Tools ────────────────────────────────────────────────────────────────────

@tool
async def search_merchants(keyword: str = "", city: str = "", service_type: int = 0) -> str:
    """搜索符合条件的商家列表。
    keyword: 关键词（商家昵称/简介），可为空。
    city: 城市筛选，可为空表示不限城市。
    service_type: 服务类型，0=不限, 1=妆娘, 2=摄影师, 3=毛娘, 4=道具制作。
    """
    try:
        params: dict = {"page": 1, "size": 6}
        if keyword:
            params["keyword"] = keyword
        if city:
            params["city"] = city
        if service_type:
            params["serviceType"] = service_type

        async with httpx.AsyncClient() as client:
            r = await client.get(
                f"{GATEWAY_URL}/api/merchant/search",
                params=params,
                headers=_auth_headers(),
                timeout=10.0,
            )

        if r.status_code != 200:
            return f"搜索失败（状态码 {r.status_code}），请稍后重试"

        records = r.json().get("data", {}).get("records", [])
        if not records:
            return "未找到符合条件的商家，可以尝试放宽筛选条件"

        lines = []
        for m in records:
            svc_map = {1: "妆娘", 2: "摄影师", 3: "毛娘", 4: "道具制作"}
            svc = "/".join(svc_map.get(t, str(t)) for t in (m.get("serviceTypes") or []))
            lines.append(
                f"ID:{m['id']} 昵称:{m.get('nickname', '')} "
                f"城市:{m.get('city', '未知')} 类型:{svc} "
                f"评分:{m.get('avgScore', 0):.1f}({m.get('reviewCount', 0)}条) "
                f"价格:{m.get('priceMin', 0)}-{m.get('priceMax', 0)}元"
            )
        return "\n".join(lines)
    except Exception as e:
        return f"搜索时出错：{e}"


@tool
async def get_merchant_detail(merchant_id: int) -> str:
    """获取某个商家的详细信息（简介、服务类型、价格区间、预约须知等）。
    merchant_id: 商家 ID（从 search_merchants 结果中获取）。
    """
    try:
        async with httpx.AsyncClient() as client:
            r = await client.get(
                f"{GATEWAY_URL}/api/merchant/{merchant_id}",
                headers=_auth_headers(),
                timeout=10.0,
            )

        if r.status_code != 200:
            return f"获取商家详情失败（状态码 {r.status_code}）"

        m = r.json().get("data")
        if not m:
            return "商家不存在"

        svc_map = {1: "妆娘", 2: "摄影师", 3: "毛娘", 4: "道具制作"}
        svc = "/".join(svc_map.get(t, str(t)) for t in (m.get("serviceTypes") or []))
        return (
            f"昵称:{m.get('nickname', '')} 城市:{m.get('city', '')} "
            f"服务类型:{svc} 评分:{m.get('avgScore', 0):.1f}({m.get('reviewCount', 0)}条)\n"
            f"价格区间:{m.get('priceMin', 0)}-{m.get('priceMax', 0)}元\n"
            f"简介:{m.get('intro', '暂无')}\n"
            f"预约须知:{m.get('bookingNotice', '暂无')}"
        )
    except Exception as e:
        return f"获取商家详情时出错：{e}"


@tool
async def get_merchant_schedules(merchant_id: int, month: str) -> str:
    """获取商家某月的可用档期。
    merchant_id: 商家 ID。
    month: 月份，格式 YYYY-MM，如 2026-08。
    """
    try:
        async with httpx.AsyncClient() as client:
            r = await client.get(
                f"{GATEWAY_URL}/api/schedule/merchant/{merchant_id}",
                params={"month": month},
                headers=_auth_headers(),
                timeout=10.0,
            )

        if r.status_code != 200:
            return f"获取档期失败（状态码 {r.status_code}）"

        schedules = r.json().get("data", [])
        available = [s for s in schedules if s.get("status") == 0]
        if not available:
            return f"{month} 无可用档期（已全部被预约或无档期）"

        lines = []
        for s in available[:10]:
            book_type = "正常预约" if s.get("bookType") == 0 else "抢档期"
            lines.append(f"日期:{s.get('date')} 时间段:{s.get('timeSlot')} 方式:{book_type}")
        total = len(available)
        if total > 10:
            lines.append(f"（共 {total} 个空档，仅显示前 10 个）")
        return "\n".join(lines)
    except Exception as e:
        return f"获取档期时出错：{e}"


@tool
async def get_merchant_reviews(merchant_id: int) -> str:
    """获取商家的最近评价（最多 5 条），辅助判断服务质量。
    merchant_id: 商家 ID。
    """
    try:
        async with httpx.AsyncClient() as client:
            r = await client.get(
                f"{GATEWAY_URL}/api/review/merchant/{merchant_id}",
                params={"page": 1, "size": 5},
                headers=_auth_headers(),
                timeout=10.0,
            )

        if r.status_code != 200:
            return f"获取评价失败（状态码 {r.status_code}）"

        records = r.json().get("data", {}).get("records", [])
        if not records:
            return "暂无评价"

        lines = []
        for rv in records:
            content = rv.get("content", "")[:80]
            lines.append(f"评分:{rv.get('score')}/5 {content}")
        return "\n".join(lines)
    except Exception as e:
        return f"获取评价时出错：{e}"


# ── Agent ─────────────────────────────────────────────────────────────────────

SYSTEM_PROMPT = """你是档期预约平台的 AI 推荐助手，帮助用户找到合适的 Cosplay 服务商（妆娘/摄影师/毛娘等）。

平台服务类型代码：1=妆娘, 2=摄影师, 3=毛娘, 4=道具制作

工作流程：
1. 根据用户需求（城市、服务类型、关键词）搜索商家
2. 查看感兴趣商家的详情和评价
3. 结合用户提到的时间，查询对应月份的档期
4. 给出有理由的推荐，包括商家 ID 方便用户直接查看

注意：必须调用工具获取真实数据，不要凭空捏造商家信息。回答要简洁、专业、亲切。"""

_TOOLS = [search_merchants, get_merchant_detail, get_merchant_schedules, get_merchant_reviews]

_LLM = ChatOpenAI(
    model="deepseek-chat",
    base_url="https://api.deepseek.com",
    api_key=os.getenv("DEEPSEEK_API_KEY", ""),
    streaming=True,
    temperature=0.7,
)


def create_agent() -> AgentExecutor:
    prompt = ChatPromptTemplate.from_messages([
        ("system", SYSTEM_PROMPT),
        MessagesPlaceholder("chat_history"),
        ("human", "{input}"),
        MessagesPlaceholder("agent_scratchpad"),
    ])
    agent = create_tool_calling_agent(_LLM, _TOOLS, prompt)
    return AgentExecutor(agent=agent, tools=_TOOLS, verbose=False, max_iterations=8)


def build_lc_history(history: list) -> list:
    """将 Redis 中存储的历史记录转换为 LangChain 消息对象列表。"""
    messages = []
    for h in history:
        if h["role"] == "human":
            messages.append(HumanMessage(content=h["content"]))
        elif h["role"] == "ai":
            messages.append(AIMessage(content=h["content"]))
    return messages
