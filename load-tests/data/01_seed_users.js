/**
 * 数据播种脚本 1：注册 200 个测试用户
 *
 * 运行方式（在 load-tests/ 目录下）：
 *   k6 run data/01_seed_users.js
 *
 * ⚠️  首次运行或密码变更后，需先清除旧测试账号再播种，否则"已存在"的账号会跳过，密码不会更新。
 *   清除命令（在数据库执行）：DELETE FROM user WHERE phone LIKE '199%';
 *
 * 说明：
 *   - 用 k6 调用注册接口而不是直接写 SQL，避免手工生成 BCrypt 哈希值
 *   - VU=1 串行执行，防止重复用户名冲突
 *   - 注册成功：business code=200；手机号已注册：business code=30002
 */

import http from 'k6/http';
import { check, sleep } from 'k6';
import { BASE_URL, USER_PASSWORD } from '../config.js';

export const options = {
  vus: 1,
  iterations: 200,
};

export default function () {
  const idx = __ITER + 1;
  const phone = '199' + String(idx).padStart(8, '0');  // 19900000001 ~ 19900000200

  const res = http.post(
    `${BASE_URL}/api/user/register`,
    JSON.stringify({
      phone: phone,
      password: USER_PASSWORD,
      nickname: `测试用户${idx}`,
    }),
    { headers: { 'Content-Type': 'application/json' } }
  );

  const body = JSON.parse(res.body);
  const ok = body && (body.code === 200);
  const exists = body && (body.code === 30002);

  check(res, {
    'register success': () => ok,
  });

  // 已存在的账号密码可能与当前不兼容，输出警告
  if (exists) {
    console.warn(`[SKIP] ${phone} already exists — run: DELETE FROM user WHERE phone LIKE '199%'; then re-seed`);
  } else if (!ok) {
    console.error(`[FAIL] ${phone} code=${body && body.code} msg=${body && body.message}`);
  }

  sleep(0.05);
}
