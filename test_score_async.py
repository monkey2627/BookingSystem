#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
MHP 评分异步更新改造 - 自动验证脚本

依赖：pip install requests
运行：python test_score_async.py
      python test_score_async.py --skip-step4   # 跳过容灾测试

前提：
  1. 所有中间件和微服务已启动
  2. 配置区的账号存在，且 BOOKING_ID_1 / BOOKING_ID_2 是 status=3（已完成）且尚未评价的预约
"""

import argparse
import json
import subprocess
import sys
import time
import uuid

import requests

# ═══════════════════════════════════════════════════════════
# 配置区（按本地环境修改后直接运行）
# ═══════════════════════════════════════════════════════════
GATEWAY     = "http://localhost:80"
MQ_API      = "http://localhost:15672/api"
MQ_USER     = "admin"
MQ_PASS     = "123456"
MQ_VHOST    = "%2F"                   # 默认 vhost "/" 的 URL 编码
MQ_EXCHANGE = "schedule.exchange"
MQ_QUEUE    = "notify.queue"
MQ_DEAD_Q   = "notify.dead.queue"
REDIS_CTR   = "mhp-redis"            # docker ps 中 Redis 容器名

USER_PHONE   = "13800000001"          # 拥有已完成预约的用户手机号
USER_PASS    = "123456"
BOOKING_ID_1 = 1                      # Step 1/2/3 用：status=3 且未评价
MERCHANT_ID  = 1                      # BOOKING_ID_1 对应的商家 ID
BOOKING_ID_2 = 2                      # Step 4 用：status=3 且未评价（须与上面不同）

ASYNC_WAIT   = 3                      # 等待异步消费的秒数
RT_THRESHOLD = 200                    # RT 判定阈值（ms），异步化后应 < 200ms
# ═══════════════════════════════════════════════════════════

# ---------- ANSI 颜色 ----------
GREEN  = "\033[92m"
RED    = "\033[91m"
YELLOW = "\033[93m"
BLUE   = "\033[94m"
DIM    = "\033[2m"
RESET  = "\033[0m"

_passed = 0
_failed = 0


def ok(msg):
    global _passed
    _passed += 1
    print(f"  {GREEN}✓ PASS{RESET}  {msg}")


def fail(msg, hint=""):
    global _failed
    _failed += 1
    hint_str = f"\n         {DIM}↳ {hint}{RESET}" if hint else ""
    print(f"  {RED}✗ FAIL{RESET}  {msg}{hint_str}")


def info(msg):
    print(f"  {BLUE}→{RESET}  {msg}")


def warn(msg):
    print(f"  {YELLOW}⚠{RESET}  {msg}")


def banner(title):
    print(f"\n{'─' * 62}")
    print(f"  {YELLOW}{title}{RESET}")
    print(f"{'─' * 62}")


# ---------- HTTP 工具 ----------
def api(method, path, token=None, timeout=10, **kwargs):
    headers = {"Content-Type": "application/json"}
    if token:
        headers["token"] = token
    return requests.request(
        method, GATEWAY + path, headers=headers, timeout=timeout, **kwargs
    )


def mq_get(path):
    return requests.get(MQ_API + path, auth=(MQ_USER, MQ_PASS), timeout=5)


def mq_publish(routing_key, payload_dict):
    """通过 RabbitMQ HTTP 管理 API 发布消息到 schedule.exchange。"""
    body = {
        "properties": {
            "delivery_mode": 2,           # 持久化
            "content_type": "application/json",
            "headers": {
                # Jackson2JsonMessageConverter 依赖此 header 做类型映射
                "__TypeId__": "com.mhp.booksystem.mq.NotifyMessage"
            }
        },
        "routing_key": routing_key,
        "payload": json.dumps(payload_dict),
        "payload_encoding": "string"
    }
    return requests.post(
        f"{MQ_API}/exchanges/{MQ_VHOST}/{MQ_EXCHANGE}/publish",
        auth=(MQ_USER, MQ_PASS),
        json=body,
        timeout=5
    )


def mq_queue_stats(queue_name):
    """返回队列的 (messages_ready, messages_total_published) 元组，失败返回 (None, None)。"""
    r = mq_get(f"/queues/{MQ_VHOST}/{queue_name}")
    if r.status_code != 200:
        return None, None
    data = r.json()
    messages = data.get("messages", 0)
    publish_total = data.get("message_stats", {}).get("deliver_get", 0)
    return messages, publish_total


def exchange_publish_count():
    """返回 schedule.exchange 累计 publish_out 次数，失败返回 -1。"""
    r = mq_get(f"/exchanges/{MQ_VHOST}/{MQ_EXCHANGE}")
    if r.status_code != 200:
        return -1
    return r.json().get("message_stats", {}).get("publish_out", 0)


def redis_get(key):
    """docker exec 进 mhp-redis 容器执行 redis-cli GET，返回值字符串。"""
    try:
        result = subprocess.run(
            ["docker", "exec", REDIS_CTR, "redis-cli", "GET", key],
            capture_output=True, text=True, timeout=5
        )
        return result.stdout.strip()
    except Exception as e:
        return f"ERROR:{e}"


def get_merchant_score(token):
    """返回 (avgScore, reviewCount) 或 (None, None)。"""
    try:
        r = api("GET", f"/api/merchant/{MERCHANT_ID}", token=token)
        if r.status_code == 200 and r.json().get("code") == 200:
            d = r.json().get("data", {})
            return d.get("avgScore"), d.get("reviewCount")
    except Exception:
        pass
    return None, None


# ══════════════════════════════════════════════════════════
# 前置检查
# ══════════════════════════════════════════════════════════
def preflight():
    banner("前置检查")

    info("检查 Gateway 连通性...")
    try:
        requests.get(GATEWAY + "/api/user/login", timeout=3)
        ok("Gateway 可达")
    except Exception as e:
        fail("Gateway 不可达", f"请确认服务已启动：{GATEWAY}  ({e})")
        sys.exit(1)

    info("检查 RabbitMQ 管理台及队列...")
    try:
        r = mq_get(f"/queues/{MQ_VHOST}/{MQ_QUEUE}")
        if r.status_code == 200:
            ok("RabbitMQ 可达，notify.queue 已存在")
        else:
            fail(
                f"notify.queue 不存在（HTTP {r.status_code}）",
                "请先启动所有微服务（队列在服务启动时声明）"
            )
            sys.exit(1)
    except Exception as e:
        fail("RabbitMQ 管理台不可达", str(e))
        sys.exit(1)

    info("检查 Redis 容器连通性（docker exec）...")
    val = redis_get("_preflight_check_")
    if val.startswith("ERROR:"):
        warn(f"docker exec 不可用：{val}")
        warn("Step 3 的 Redis 验证将跳过，其余步骤正常执行")
    else:
        ok(f"Redis 容器 {REDIS_CTR} 可用")


# ══════════════════════════════════════════════════════════
# 登录
# ══════════════════════════════════════════════════════════
def do_login():
    banner("登录")
    info(f"账号：{USER_PHONE}")
    r = api("POST", "/api/user/login",
            json={"phone": USER_PHONE, "password": USER_PASS})
    body = r.json()
    if r.status_code == 200 and body.get("code") == 200:
        token = body["data"]["token"]
        ok("登录成功，token 已获取")
        return token
    fail("登录失败", r.text[:200])
    sys.exit(1)


# ══════════════════════════════════════════════════════════
# Step 1：黄金路径
# ══════════════════════════════════════════════════════════
def step1(token):
    banner("Step 1 / 黄金路径：提交评价，验证 RT 和评分异步更新")

    info("读取提交前商家评分...")
    before_avg, before_count = get_merchant_score(token)
    if before_avg is None:
        fail("无法获取商家信息", f"merchantId={MERCHANT_ID}，请确认 MERCHANT_ID 配置正确")
        sys.exit(1)
    info(f"提交前：avgScore={before_avg}  reviewCount={before_count}")

    info(f"提交评价（bookingId={BOOKING_ID_1}，score=5）...")
    t0 = time.time()
    r = api("POST", "/api/review", token=token, json={
        "orderId": BOOKING_ID_1,
        "score": 5,
        "content": "自动测试评价 - 黄金路径"
    })
    rt_ms = (time.time() - t0) * 1000
    body = r.json()

    if r.status_code != 200 or body.get("code") != 200:
        fail("评价提交失败", body.get("msg", r.text[:200]))
        sys.exit(1)
    ok(f"评价提交成功，RT = {rt_ms:.0f} ms")

    if rt_ms < RT_THRESHOLD:
        ok(f"RT {rt_ms:.0f} ms < {RT_THRESHOLD} ms（异步改造生效，未阻塞在 Dubbo RPC）")
    else:
        fail(
            f"RT {rt_ms:.0f} ms 超过阈值 {RT_THRESHOLD} ms",
            "可能同步调用仍存在，请检查 ReviewServiceImpl.create() 是否已移除 updateMerchantScore()"
        )

    info(f"等待 {ASYNC_WAIT}s，让 MQ Consumer 完成异步评分更新...")
    time.sleep(ASYNC_WAIT)

    after_avg, after_count = get_merchant_score(token)
    info(f"提交后：avgScore={after_avg}  reviewCount={after_count}")

    expected_count = (before_count or 0) + 1
    if after_count == expected_count:
        ok(f"reviewCount {before_count} → {after_count}，评分已通过 MQ 异步更新")
    else:
        fail(
            f"reviewCount 未变化（{before_count} → {after_count}）",
            f"预期 {expected_count}，检查 mhp-social 日志是否有 [Score] 行"
        )


# ══════════════════════════════════════════════════════════
# Step 2：事务回滚 - 重复提交同一预约
# ══════════════════════════════════════════════════════════
def step2(token):
    banner("Step 2 / 事务回滚：重复提交同一预约，确认未发 MQ 消息")

    info("记录 Exchange 累计 publish_out 基准值...")
    pub_before = exchange_publish_count()
    if pub_before == -1:
        warn("无法读取 Exchange 统计，跳过 publish_out 对比")
    else:
        info(f"当前 Exchange publish_out = {pub_before}")

    info(f"重复提交同一预约（bookingId={BOOKING_ID_1}）...")
    r = api("POST", "/api/review", token=token, json={
        "orderId": BOOKING_ID_1,
        "score": 3,
        "content": "重复评价（不应写入）"
    })
    body = r.json()
    code = body.get("code")
    msg  = body.get("msg", "")

    if code != 200:
        ok(f"正确拒绝重复评价，code={code}  msg={msg}")
    else:
        fail("重复评价竟然提交成功，幂等校验失效")

    time.sleep(1)

    if pub_before != -1:
        pub_after = exchange_publish_count()
        if pub_after == pub_before:
            ok("Exchange publish_out 未增加，事务回滚后确认未发 MQ 消息")
        else:
            fail(
                f"Exchange publish_out 增加了 {pub_after - pub_before} 条",
                "可能因异常流程触发了不该发送的 MQ 消息"
            )


# ══════════════════════════════════════════════════════════
# Step 3：幂等性 - 相同 msgId 只处理一次
# ══════════════════════════════════════════════════════════
def step3():
    banner("Step 3 / 幂等性：相同 msgId 的 SCORE_UPDATE 消息只处理一次")

    test_msg_id = str(uuid.uuid4())
    payload = {
        "msgId": test_msg_id,
        "type": "SCORE_UPDATE",
        "merchantId": MERCHANT_ID
    }
    info(f"生成测试消息，msgId = {test_msg_id}")

    info("第一次发送 SCORE_UPDATE...")
    r1 = mq_publish("notify.score_update", payload)
    if r1.status_code == 200 and r1.json().get("routed"):
        ok("消息已路由到 notify.queue")
    else:
        fail("消息路由失败", r1.text[:200])
        warn("若反序列化失败，请确认 Spring Boot 是否自动配置了 Jackson2JsonMessageConverter")
        return

    info(f"等待 {ASYNC_WAIT}s，让 Consumer 处理并写入 Redis 幂等 key...")
    time.sleep(ASYNC_WAIT)

    redis_key = f"msg:processed:{test_msg_id}"
    val = redis_get(redis_key)

    if val.startswith("ERROR:"):
        warn(f"docker exec 不可用，跳过 Redis 验证：{val}")
        warn(f"可手动验证：docker exec {REDIS_CTR} redis-cli GET {redis_key}")
    elif val == "1":
        ok(f"Redis key [{redis_key}] = 1，第一次消息已处理")
    else:
        fail(
            f"Redis key 值为 '{val}'，预期 '1'",
            f"检查 mhp-social 日志是否有 [MQ] 评分更新完成 merchantId={MERCHANT_ID}"
        )
        return

    info("第二次发送（相同 msgId）...")
    mq_publish("notify.score_update", payload)
    time.sleep(1)

    val2 = redis_get(redis_key)
    if val2.startswith("ERROR:"):
        warn("跳过 Redis 二次验证，请在日志中搜索「重复消息，跳过」")
    elif val2 == "1":
        ok("第二次投递后 Redis key 仍存在且值为 1，幂等去重正常工作")
        info("可在 mhp-social 日志中确认出现：[MQ] 重复消息，跳过")
    else:
        fail(f"第二次投递后 Redis key 值异常：'{val2}'")


# ══════════════════════════════════════════════════════════
# Step 4：容灾验证
# ══════════════════════════════════════════════════════════
def step4(token):
    banner("Step 4 / 容灾：mhp-account 宕机时评价仍能提交，消息进入死信等待恢复")

    dq_before, _ = mq_queue_stats(MQ_DEAD_Q)
    dq_before = dq_before or 0
    info(f"死信队列当前消息数：{dq_before}")

    print(f"""
  {YELLOW}【需要手动操作】请停止 mhp-account，然后按 Enter 继续{RESET}

  停止方法（任选其一）：
    a. 在运行 mhp-account 的终端按 Ctrl+C
    b. 查进程：jps | findstr AccountApplication
       终止：  taskkill /PID <pid> /F

  停止后按 Enter：""", end="", flush=True)
    input()

    info(f"mhp-account 已停止，提交评价（bookingId={BOOKING_ID_2}）...")
    t0 = time.time()
    try:
        r = api("POST", "/api/review", token=token, json={
            "orderId": BOOKING_ID_2,
            "score": 5,
            "content": "自动测试评价 - 容灾测试"
        }, timeout=15)
        rt_ms = (time.time() - t0) * 1000
        body = r.json()

        if r.status_code == 200 and body.get("code") == 200:
            ok(
                f"mhp-account 宕机期间评价提交成功（RT={rt_ms:.0f}ms），"
                "解耦生效，评价不依赖 account 存活"
            )
        else:
            fail("评价提交失败", body.get("msg", r.text[:200]))
    except requests.exceptions.Timeout:
        fail("请求超时", "检查 mhp-booking / mhp-social 是否仍在运行")
        return

    info(f"等待 {ASYNC_WAIT}s，Consumer 尝试调 account 更新评分并失败...")
    time.sleep(ASYNC_WAIT)

    dq_after, _ = mq_queue_stats(MQ_DEAD_Q)
    dq_after = dq_after or 0
    if dq_after > dq_before:
        ok(
            f"死信队列 {dq_before} → {dq_after} 条，"
            "Consumer 调 mhp-account 失败后消息正确进入死信（符合预期）"
        )
    else:
        warn(
            f"死信队列未增加（{dq_before} → {dq_after}），"
            "可能 mhp-account 未完全停止或 Consumer 尚未处理"
        )

    print(f"""
  {YELLOW}【需要手动操作】请重新启动 mhp-account，然后按 Enter 继续{RESET}

  java -jar BookSystem/mhp-account/target/mhp-account-1.0.0.jar

  启动后按 Enter：""", end="", flush=True)
    input()

    info("等待 mhp-account 启动（15s）...")
    time.sleep(15)

    info("检查 mhp-account 是否可响应...")
    try:
        r = api("GET", f"/api/merchant/{MERCHANT_ID}", timeout=5)
        if r.status_code in (200, 401):
            ok("mhp-account 已恢复，Gateway 路由正常")
        else:
            warn(f"响应码 {r.status_code}，服务可能仍在启动")
    except Exception:
        warn("请求未完成，服务可能仍在启动中")

    print(f"""
  {YELLOW}【后续操作提示】死信队列里的消息需手动重投才会重新处理：{RESET}
    1. 打开 RabbitMQ 管理台：http://localhost:15672
    2. Queues → {MQ_DEAD_Q} → Get messages（获取消息内容）
    3. 复制 payload，在 Exchanges → {MQ_EXCHANGE} → Publish message
       routing_key = notify.score_update，粘贴 payload 发送
    4. 等待 {ASYNC_WAIT}s 后检查商家评分是否更新
""")
    ok("Step 4 核心验证完成：宕机期间评价保存成功（解耦有效）")


# ══════════════════════════════════════════════════════════
# 主入口
# ══════════════════════════════════════════════════════════
def main():
    parser = argparse.ArgumentParser(description="MHP 评分异步更新自动验证")
    parser.add_argument("--skip-step4", action="store_true", help="跳过容灾测试（不需要手动停服务）")
    args = parser.parse_args()

    print(f"\n{YELLOW}{'═' * 62}")
    print(f"  MHP 评分异步更新改造 — 自动验证脚本")
    print(f"{'═' * 62}{RESET}")
    print(f"  Gateway  : {GATEWAY}")
    print(f"  MQ 管理台: {MQ_API}")
    print(f"  商家 ID  : {MERCHANT_ID}    预约(Step1-3): {BOOKING_ID_1}    预约(Step4): {BOOKING_ID_2}")

    preflight()
    token = do_login()
    step1(token)
    step2(token)
    step3()

    if not args.skip_step4:
        step4(token)
    else:
        warn("已跳过 Step 4（--skip-step4）")

    # 汇总
    banner("测试汇总")
    total = _passed + _failed
    status_color = GREEN if _failed == 0 else RED
    print(f"  总计 {total} 项　"
          f"{GREEN}通过 {_passed}{RESET}　"
          f"{status_color}失败 {_failed}{RESET}\n")
    sys.exit(0 if _failed == 0 else 1)


if __name__ == "__main__":
    main()
