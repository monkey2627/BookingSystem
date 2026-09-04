/**
 * 冒烟测试 — 验证脚本和环境基本可用
 *
 * 目标：1~2 个 VU，跑完一遍核心流程，确认无脚本错误、接口 200、业务逻辑正常。
 * 失败意味着环境或脚本本身有问题，后续测试无法信任。
 *
 * 运行：k6 run scenarios/01_smoke.js
 */

import http from 'k6/http';
import { sleep } from 'k6';
import { BASE_URL, THRESHOLDS, CODE } from '../config.js';
import { login, indexToPhone } from '../helpers/auth.js';
import { assertOk, assertBizOr } from '../helpers/checks.js';

export const options = {
  vus: 2,
  duration: '1m',
  thresholds: THRESHOLDS,
};

// setup() 在测试开始前执行一次，返回值传给每个 VU 的 default function
export function setup() {
  // 只需 2 个 token 做冒烟
  const tokens = [];
  for (let i = 1; i <= 2; i++) {
    const t = login(indexToPhone(i));
    if (t) tokens.push(t);
  }

  // 获取商家列表，取第一个商家 id
  const searchRes = http.get(`${BASE_URL}/api/merchant/search?keyword=测试&size=1`);
  const merchants = searchRes.json('data.list') || [];
  const merchantId = merchants.length > 0 ? merchants[0].id : null;

  // 获取该商家本月档期
  const now = new Date();
  const yearMonth = `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}`;
  let scheduleId = null;
  if (merchantId) {
    const schRes = http.get(
      `${BASE_URL}/api/schedule/month?merchantId=${merchantId}&yearMonth=${yearMonth}`
    );
    const schedules = schRes.json('data') || [];
    const available = schedules.find((s) => s.status === 0 && s.bookType === 0);
    if (available) scheduleId = available.id;
  }

  return { tokens, merchantId, scheduleId };
}

export default function (data) {
  const { tokens, merchantId, scheduleId } = data;
  if (tokens.length === 0) {
    console.error('没有可用 token，登录全部失败，请检查测试账号是否已播种');
    return;
  }
  const token = tokens[__VU % tokens.length];
  const headers = { 'Content-Type': 'application/json', token };

  // Step 1: 搜索商家
  const searchRes = http.get(`${BASE_URL}/api/merchant/search?keyword=测试&page=1&size=10`);
  assertOk(searchRes, 'search');
  sleep(0.5);

  // Step 2: 查看商家主页
  if (merchantId) {
    const detailRes = http.get(`${BASE_URL}/api/merchant/${merchantId}`);
    assertOk(detailRes, 'merchant-detail');
    sleep(0.5);
  }

  // Step 3: 发起预约（允许重复预约被拒绝）
  if (scheduleId) {
    const bookRes = http.post(
      `${BASE_URL}/api/booking`,
      JSON.stringify({ scheduleId }),
      { headers }
    );
    assertBizOr(bookRes, 'booking', [CODE.OK, CODE.BOOKING_DUPLICATE, CODE.SCHEDULE_NOT_AVAILABLE]);
    sleep(1);
  }
}
