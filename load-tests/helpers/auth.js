import http from 'k6/http';
import { check } from 'k6';
import { BASE_URL, USER_PASSWORD } from '../config.js';

/** 将序号转成 11 位测试手机号，如 1 → '19900000001' */
export function indexToPhone(i) {
  return '199' + String(i).padStart(8, '0');
}

/**
 * 登录单个用户，返回 token 字符串。失败返回 null。
 */
export function login(phone) {
  const res = http.post(
    `${BASE_URL}/api/user/login`,
    JSON.stringify({ phone, password: USER_PASSWORD }),
    { headers: { 'Content-Type': 'application/json' } }
  );
  const ok = check(res, { 'login ok': (r) => r.status === 200 && r.json('code') === 200 });
  if (!ok) return null;
  return res.json('data.token');
}

/**
 * 批量登录 count 个用户（19900000001 … 199000000N），返回 token 数组。
 * 在 k6 的 setup() 里串行调用，不消耗并发配额。
 */
export function buildTokenPool(count) {
  const tokens = [];
  for (let i = 1; i <= count; i++) {
    const token = login(indexToPhone(i));
    if (token) tokens.push(token);
  }
  return tokens;
}

/**
 * 从 token 池里随机取一个，构造带鉴权的 headers。
 */
export function authHeaders(tokens) {
  const token = tokens[Math.floor(Math.random() * tokens.length)];
  return {
    'Content-Type': 'application/json',
    'token': token,
  };
}
