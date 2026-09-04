/**
 * 数据播种脚本 1：注册 200 个测试用户
 *
 * 运行方式（在 load-tests/ 目录下）：
 *   k6 run data/01_seed_users.js
 *
 * 说明：
 *   - 用 k6 调用注册接口而不是直接写 SQL，避免手工生成 BCrypt 哈希值
 *   - VU=1 串行执行，防止重复用户名冲突
 *   - 幂等：若用户名已存在，注册接口会返回业务错误，脚本忽略并继续
 */

import http from 'k6/http';
import { check, sleep } from 'k6';
import { BASE_URL, USER_PASSWORD } from '../config.js';

export const options = {
  vus: 1,
  iterations: 200,  // 注册 200 个用户，每次迭代注册一个
};

export default function () {
  const idx = __ITER + 1;  // __ITER 从 0 开始，序号从 1 开始
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

  // 200（注册成功）或 30001（用户名已存在）都算正常
  check(res, {
    'registered or exists': (r) => r.status === 200,
  });

  sleep(0.05);  // 50ms 间隔，避免压垮注册接口
}
