/**
 * 数据播种脚本 3：为每个测试商家创建档期
 *
 * 运行方式（在 load-tests/ 目录下）：
 *   k6 run data/03_seed_schedules.js
 *
 * 说明：
 *   - 先登录 test_user_1 ~ test_user_50（已是商家），为每个商家创建未来 14 天的档期
 *   - VU=1 串行，避免并发创建同一商家同一日期的档期
 *   - 幂等：档期接口若日期重复会返回业务错误，忽略即可
 */

import http from 'k6/http';
import { check, sleep } from 'k6';
import { BASE_URL, USER_PASSWORD } from '../config.js';

export const options = {
  vus: 1,
  // 50 个商家 × 14 天 × 2 种时间段 = 1400 个档期
  iterations: 1,
};

function login(phone) {
  const res = http.post(
    `${BASE_URL}/api/user/login`,
    JSON.stringify({ phone, password: USER_PASSWORD }),
    { headers: { 'Content-Type': 'application/json' } }
  );
  if (res.status !== 200 || res.json('code') !== 200) return null;
  return res.json('data.token');
}

function createSchedule(token, date, timeSlot, bookType, rushOpenTime = null) {
  return http.post(
    `${BASE_URL}/api/schedule`,
    JSON.stringify({
      date: date,
      timeSlot: timeSlot,
      bookType: bookType,
      serviceType: 1,
      maxQueueSize: 5,
      rushOpenTime: rushOpenTime || `${date}T10:00:00`,
    }),
    { headers: { 'Content-Type': 'application/json', 'token': token } }
  );
}

function formatDate(offsetDays) {
  const d = new Date();
  d.setDate(d.getDate() + offsetDays);
  return d.toISOString().slice(0, 10);
}

// 返回当前时间往前推 1 小时的 ISO 字符串（秒精度），保证抢档期在 spike 测试时已开放
function pastHourIso() {
  const d = new Date(Date.now() - 60 * 60 * 1000);
  return d.toISOString().slice(0, 19);  // "2026-09-04T12:00:00"
}

export default function () {
  for (let merchantIdx = 1; merchantIdx <= 50; merchantIdx++) {
    const phone = '199' + String(merchantIdx).padStart(8, '0');
    const token = login(phone);
    if (!token) {
      console.warn(`${phone} login failed, skip`);
      continue;
    }

    for (let dayOffset = 1; dayOffset <= 14; dayOffset++) {
      const date = formatDate(dayOffset);

      // 普通预约档期
      const r1 = createSchedule(token, date, '10:00-12:00', 0);
      check(r1, { 'schedule created or exists': (r) => r.status === 200 });

      // 抢档期：rushOpenTime 设为 1 小时前，保证 spike 测试时已处于开放状态
      const r2 = createSchedule(token, date, '14:00-16:00', 1, pastHourIso());
      check(r2, { 'rush schedule created or exists': (r) => r.status === 200 });

      sleep(0.02);
    }
  }
}
