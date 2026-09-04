/**
 * 压力测试 — 找系统的崩溃点（Breaking Point）
 *
 * 策略：逐步加压直到错误率超阈值或 RT 显著劣化，记录转折点对应的 VU 数量。
 * 这个数字就是当前版本的"系统容量上限"，用于制定扩容策略和对比优化前后效果。
 *
 * 注意：压力测试可能触发系统告警，提前通知相关人员。
 *
 * 运行：
 *   k6 run --out influxdb=http://localhost:8086/k6 scenarios/03_stress.js
 */

import http from 'k6/http';
import { sleep } from 'k6';
import { BASE_URL, CODE } from '../config.js';
import { buildTokenPool, authHeaders } from '../helpers/auth.js';
import { assertOk, assertBizOr } from '../helpers/checks.js';

export const options = {
  stages: [
    { duration: '2m',  target: 50  },  // 基线
    { duration: '3m',  target: 100 },  // 加压
    { duration: '3m',  target: 150 },  // 继续加压
    { duration: '3m',  target: 200 },  // 高压（通常在此阶段出现性能拐点）
    { duration: '2m',  target: 0   },  // 降温，观察恢复情况
  ],
  thresholds: {
    // 压力测试阈值适当放宽，目的是找崩溃点而非判断是否通过
    http_req_duration: ['p(95)<2000'],
    http_req_failed:   ['rate<0.05'],
  },
};

export function setup() {
  const tokens = buildTokenPool(200);

  if (tokens.length === 0) {
    return { tokens, merchantIds: [], scheduleIds: [] };
  }
  const setupHeaders = { 'Content-Type': 'application/json', token: tokens[0] };

  const searchRes = http.get(
    `${BASE_URL}/api/merchant/search?size=20`,
    { headers: setupHeaders }
  );
  const merchants = searchRes.json('data.records') || [];
  const merchantIds = merchants.map((m) => m.id);

  const now = new Date();
  const yearMonth = `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}`;
  const scheduleIds = [];
  for (const mid of merchantIds.slice(0, 10)) {
    const schRes = http.get(
      `${BASE_URL}/api/schedule/month?merchantId=${mid}&yearMonth=${yearMonth}`,
      { headers: setupHeaders }
    );
    const schedules = schRes.json('data') || [];
    schedules.filter((s) => s.bookType === 0).forEach((s) => scheduleIds.push(s.id));
  }

  return { tokens, merchantIds, scheduleIds };
}

export default function (data) {
  const { tokens, merchantIds, scheduleIds } = data;

  if (Math.random() < 0.6) {
    // 60% 高频读（商家主页，主要打缓存和 ES）
    if (merchantIds.length > 0) {
      const headers = authHeaders(tokens);
      const mid = merchantIds[Math.floor(Math.random() * merchantIds.length)];
      const r = http.get(`${BASE_URL}/api/merchant/${mid}`, { headers });
      assertOk(r, 'detail');
    }
    sleep(0.2);
  } else {
    // 40% 写操作（预约创建，打 Redisson 锁和 DB）
    if (scheduleIds.length > 0) {
      const headers = authHeaders(tokens);
      const sid = scheduleIds[Math.floor(Math.random() * scheduleIds.length)];
      const r = http.post(
        `${BASE_URL}/api/booking`,
        JSON.stringify({ scheduleId: sid }),
        { headers }
      );
      assertBizOr(r, 'booking', [CODE.OK, CODE.BOOKING_DUPLICATE, CODE.SCHEDULE_NOT_AVAILABLE]);
    }
    sleep(0.5);
  }
}
