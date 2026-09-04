/**
 * 基准负载测试 — 模拟日常真实流量，验证系统能稳定承受
 *
 * 流量模型（ramp-up 策略）：
 *   0→2min：0→50 VU（预热，让 JIT 编译和连接池稳定）
 *   2→8min：50 VU 稳定（主测量窗口，记录 RPS / RT / 错误率基线）
 *   8→10min：50→0 VU（降温，观察资源是否正常释放）
 *
 * 成功标准：p95 RT < 500ms，错误率 < 1%
 *
 * 运行：
 *   k6 run --out influxdb=http://localhost:8086/k6 scenarios/02_load.js
 */

import http from 'k6/http';
import { sleep } from 'k6';
import { BASE_URL, THRESHOLDS, CODE } from '../config.js';
import { buildTokenPool, authHeaders } from '../helpers/auth.js';
import { assertOk, assertBizOr } from '../helpers/checks.js';

export const options = {
  stages: [
    { duration: '2m', target: 50 },
    { duration: '6m', target: 50 },
    { duration: '2m', target: 0  },
  ],
  thresholds: THRESHOLDS,
};

export function setup() {
  const tokens = buildTokenPool(100);  // 用 100 个账号池化，减少 token 过期风险

  if (tokens.length === 0) {
    return { tokens, merchantIds: [], scheduleIds: [] };
  }
  const setupHeaders = { 'Content-Type': 'application/json', token: tokens[0] };

  // 获取可用商家和档期（真实 ID）
  const searchRes = http.get(
    `${BASE_URL}/api/merchant/search?size=20`,
    { headers: setupHeaders }
  );
  const merchants = searchRes.json('data.records') || [];
  const merchantIds = merchants.map((m) => m.id);

  // 获取普通预约档期
  const now = new Date();
  const yearMonth = `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}`;
  const scheduleIds = [];
  for (const mid of merchantIds.slice(0, 5)) {
    const schRes = http.get(
      `${BASE_URL}/api/schedule/month?merchantId=${mid}&yearMonth=${yearMonth}`,
      { headers: setupHeaders }
    );
    const schedules = schRes.json('data') || [];
    schedules.filter((s) => s.status === 0 && s.bookType === 0).forEach((s) => scheduleIds.push(s.id));
  }

  return { tokens, merchantIds, scheduleIds };
}

export default function (data) {
  const { tokens, merchantIds, scheduleIds } = data;

  // 70% 浏览行为
  if (Math.random() < 0.7) {
    browseFlow(tokens, merchantIds);
  } else {
    // 30% 预约行为（需鉴权）
    bookingFlow(tokens, scheduleIds);
  }
}

function browseFlow(tokens, merchantIds) {
  const headers = authHeaders(tokens);

  // 搜索商家
  const q = ['北京', '上海', '妆娘', '摄影', ''][Math.floor(Math.random() * 5)];
  const searchRes = http.get(
    `${BASE_URL}/api/merchant/search?keyword=${encodeURIComponent(q)}&size=10`,
    { headers }
  );
  assertOk(searchRes, 'search');
  sleep(0.3 + Math.random() * 0.5);

  // 查看商家主页（命中缓存的高频路径）
  if (merchantIds.length > 0) {
    const mid = merchantIds[Math.floor(Math.random() * merchantIds.length)];
    const detailRes = http.get(`${BASE_URL}/api/merchant/${mid}`, { headers });
    assertOk(detailRes, 'merchant-detail');
    sleep(0.5 + Math.random() * 1);
  }
}

function bookingFlow(tokens, scheduleIds) {
  if (scheduleIds.length === 0) return;
  const headers = authHeaders(tokens);
  const sid = scheduleIds[Math.floor(Math.random() * scheduleIds.length)];

  const res = http.post(
    `${BASE_URL}/api/booking`,
    JSON.stringify({ scheduleId: sid }),
    { headers }
  );
  assertBizOr(res, 'booking', [CODE.OK, CODE.BOOKING_DUPLICATE, CODE.SCHEDULE_NOT_AVAILABLE]);
  sleep(1 + Math.random() * 2);
}
