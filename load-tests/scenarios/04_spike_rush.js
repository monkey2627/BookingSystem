/**
 * 尖峰测试 — 模拟抢档期瞬间并发冲击
 *
 * 场景：抢档期开放时，大量用户同时请求 POST /api/schedule/{id}/rush。
 * Lua 脚本保证原子性，最终只有 maxQueueSize 名用户成功入队，其余返回 SCHEDULE_FULL(10001)。
 *
 * 验证点：
 *   1. 最终排队人数 <= maxQueueSize（正确性）
 *   2. 接口 RT < 200ms（Lua+Redis，无 DB IO，必须快）
 *   3. 高并发下无 500 错误（健壮性）
 *
 * 运行：
 *   k6 run --out influxdb=http://localhost:8086/k6 scenarios/04_spike_rush.js
 *
 * 前置条件：
 *   1. 确保存在 bookType=1 的档期，且 rushOpenTime 已过（状态可抢）
 *   2. 档期 ID 在 setup() 中通过 API 动态获取
 */

import http from 'k6/http';
import { sleep, check } from 'k6';
import { BASE_URL, CODE } from '../config.js';
import { buildTokenPool, authHeaders } from '../helpers/auth.js';
import { assertBizOr } from '../helpers/checks.js';

export const options = {
  scenarios: {
    spike: {
      executor: 'ramping-vus',
      stages: [
        { duration: '10s', target: 0   },  // 等待阶段，所有 VU 就位
        { duration: '5s',  target: 200 },  // 瞬间拉到 200 VU（模拟抢档期开抢）
        { duration: '30s', target: 200 },  // 持续冲击 30s
        { duration: '10s', target: 0   },  // 冲击结束，快速降压
      ],
    },
  },
  thresholds: {
    // 抢档期接口走 Redis Lua，RT 必须极低
    'http_req_duration{scenario:spike}': ['p(95)<200', 'p(99)<500'],
    http_req_failed: ['rate<0.01'],
  },
};

export function setup() {
  // 登录 200 个测试用户
  const tokens = buildTokenPool(200);

  if (tokens.length === 0) {
    return { tokens, rushScheduleId: null };
  }
  const setupHeaders = { 'Content-Type': 'application/json', token: tokens[0] };

  // 查找一个可用的抢档期档期（bookType=1）
  const searchRes = http.get(
    `${BASE_URL}/api/merchant/search?size=5`,
    { headers: setupHeaders }
  );
  const merchants = searchRes.json('data.list') || [];
  let rushScheduleId = null;

  const now = new Date();
  const yearMonth = `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}`;

  for (const m of merchants) {
    const schRes = http.get(
      `${BASE_URL}/api/schedule/month?merchantId=${m.id}&yearMonth=${yearMonth}`,
      { headers: setupHeaders }
    );
    const schedules = schRes.json('data') || [];
    const rushSchedule = schedules.find((s) => s.bookType === 1);
    if (rushSchedule) {
      rushScheduleId = rushSchedule.id;
      break;
    }
  }

  if (!rushScheduleId) {
    console.error('未找到 bookType=1 的档期，请先运行 03_seed_schedules.js');
  }

  console.log(`抢档期 ID: ${rushScheduleId}，将在 10s 后开始冲击`);
  return { tokens, rushScheduleId };
}

export default function (data) {
  const { tokens, rushScheduleId } = data;
  if (!rushScheduleId) return;

  const headers = authHeaders(tokens);

  // 所有 VU 同时打同一个抢档期
  const res = http.post(
    `${BASE_URL}/api/schedule/${rushScheduleId}/rush`,
    null,
    { headers }
  );

  // 正常结果：成功入队(200+rank)、已在队列(RUSH_ALREADY_JOINED)、队满(SCHEDULE_FULL)
  assertBizOr(res, 'rush', [CODE.OK, CODE.RUSH_ALREADY_JOINED, CODE.SCHEDULE_FULL, CODE.SCHEDULE_NOT_OPEN]);

  // 验证最终排队人数（用 HTTP API 查，不在 k6 里直连 Redis）
  if (Math.random() < 0.05) {  // 5% 概率采样，避免额外压力
    const queueRes = http.get(
      `${BASE_URL}/api/schedule/${rushScheduleId}/queue`,
      { headers }
    );
    check(queueRes, { 'queue size valid': (r) => {
      const size = (r.json('data') || []).length;
      return size <= 5;  // maxQueueSize=5（与 seed 脚本一致）
    }});
  }

  // 抢档期场景无需 sleep，模拟真实的"开抢一瞬间"
}

export function teardown(data) {
  const { tokens, rushScheduleId } = data;
  if (!rushScheduleId || !tokens || tokens.length === 0) return;

  // 测试结束后查看最终队列状态
  const headers = { 'Content-Type': 'application/json', token: tokens[0] };
  const res = http.get(`${BASE_URL}/api/schedule/${rushScheduleId}/queue`, { headers });
  const queue = res.json('data') || [];
  console.log(`=== 抢档期结束，最终队列人数: ${queue.length} ===`);

  // 提醒手动清理 Redis（KEYS 命令会阻塞，改用 --scan）
  console.log(`手动清理命令（如需重测）：`);
  console.log(`  redis-cli --scan --pattern "schedule:${rushScheduleId}*" | xargs -r redis-cli DEL`);
}
