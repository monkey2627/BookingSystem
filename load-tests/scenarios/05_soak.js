/**
 * 浸泡测试 — 检测长时间运行下的内存泄漏和资源耗尽
 *
 * 策略：以 30 VU（约基准负载的 60%）持续运行 1 小时。
 * 低于峰值的目的是"跑得久"而非"跑得猛"。
 * 关注点：JVM 堆使用趋势（是否持续上涨不回落）、连接池是否泄漏、GC 次数和停顿时长。
 *
 * 运行：
 *   k6 run --out influxdb=http://localhost:8086/k6 scenarios/05_soak.js
 *
 * 配合监控（Grafana Dashboard 4701 JVM）实时观察：
 *   - Heap Used：正常情况下随 GC 周期波动，不应单调递增
 *   - GC Pause：长时间运行后 GC 停顿不应明显增加
 *   - DB Connections Active：连接数在稳定后不应持续增长
 */

import http from 'k6/http';
import { sleep } from 'k6';
import { BASE_URL, THRESHOLDS, CODE } from '../config.js';
import { buildTokenPool, authHeaders } from '../helpers/auth.js';
import { assertOk, assertBizOr } from '../helpers/checks.js';

export const options = {
  stages: [
    { duration: '5m',  target: 30 },   // 预热
    { duration: '50m', target: 30 },   // 主测量窗口（1小时中的核心段）
    { duration: '5m',  target: 0  },   // 降温
  ],
  thresholds: THRESHOLDS,
};

export function setup() {
  const tokens = buildTokenPool(50);

  if (tokens.length === 0) {
    return { tokens, merchantIds: [], scheduleIds: [] };
  }
  const setupHeaders = { 'Content-Type': 'application/json', token: tokens[0] };

  const searchRes = http.get(
    `${BASE_URL}/api/merchant/search?size=20`,
    { headers: setupHeaders }
  );
  const merchants = searchRes.json('data.list') || [];
  const merchantIds = merchants.map((m) => m.id);

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

// 浸泡测试与基准测试使用相同的业务流，直接内联，不依赖模块重导出
export default function (data) {
  const { tokens, merchantIds, scheduleIds } = data;

  if (Math.random() < 0.7) {
    // 70% 浏览流（高频缓存命中路径）
    if (merchantIds.length > 0) {
      const headers = authHeaders(tokens);
      const mid = merchantIds[Math.floor(Math.random() * merchantIds.length)];
      const r = http.get(`${BASE_URL}/api/merchant/${mid}`, { headers });
      assertOk(r, 'detail');
      sleep(0.5 + Math.random() * 1);
    }
  } else {
    // 30% 预约流（打 DB 和锁）
    if (scheduleIds.length > 0) {
      const headers = authHeaders(tokens);
      const sid = scheduleIds[Math.floor(Math.random() * scheduleIds.length)];
      const r = http.post(
        `${BASE_URL}/api/booking`,
        JSON.stringify({ scheduleId: sid }),
        { headers }
      );
      assertBizOr(r, 'booking', [CODE.OK, CODE.BOOKING_DUPLICATE, CODE.SCHEDULE_NOT_AVAILABLE]);
      sleep(1 + Math.random() * 2);
    }
  }
}
