// 所有测试脚本共享的常量，修改这里即可全局生效

export const BASE_URL = 'http://localhost:80';

// 测试账号密码（由 data/01_seed_users.js 创建）
export const USER_PASSWORD = 'Test@123456';
export const USER_COUNT = 200;  // 与 seed_users 保持一致

// 超时阈值（SLA 基线）
export const THRESHOLDS = {
  http_req_duration: ['p(95)<500', 'p(99)<1000'],
  http_req_failed:   ['rate<0.01'],
};

// 各接口路径
export const ENDPOINTS = {
  login:           '/api/user/login',
  merchantSearch:  '/api/merchant/search',
  merchantDetail:  '/api/merchant',
  scheduleByMonth: '/api/schedule/month',
  booking:         '/api/booking',
  rush:            '/api/schedule',       // POST /api/schedule/{id}/rush
  queue:           '/api/schedule',       // GET  /api/schedule/{id}/queue
};

// ResultCode 错误码（与 com.mhp.booksystem.common.ResultCode 保持一致）
export const CODE = {
  OK:                   200,
  SCHEDULE_FULL:        10001,
  SCHEDULE_NOT_OPEN:    10002,
  SCHEDULE_NOT_FOUND:   10003,
  SCHEDULE_NOT_AVAILABLE: 10004,
  RUSH_ALREADY_JOINED:  10006,
  BOOKING_DUPLICATE:    20001,
};
