import { check } from 'k6';

/**
 * 断言 HTTP 响应为业务成功（status 200 且 body.code == 200）。
 * @param {object} res  k6 Response 对象
 * @param {string} tag  用于 check 报告的标签
 */
export function assertOk(res, tag) {
  return check(res, {
    [`${tag} http 200`]: (r) => r.status === 200,
    [`${tag} code 200`]: (r) => r.json('code') === 200,
  });
}

/**
 * 断言 HTTP 响应为预期的业务错误码（允许多个）。
 * 适用于"预约重复""档期已满"等属于正常业务逻辑的拒绝。
 * @param {object} res          k6 Response 对象
 * @param {string} tag          用于 check 报告的标签
 * @param {number[]} allowedCodes  允许的 ResultCode 列表
 */
export function assertBizOr(res, tag, allowedCodes) {
  return check(res, {
    [`${tag} http 200`]: (r) => r.status === 200,
    [`${tag} expected code`]: (r) => allowedCodes.includes(r.json('code')),
  });
}
