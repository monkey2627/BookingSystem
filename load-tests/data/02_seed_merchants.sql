-- 数据播种脚本 2：为测试用户创建商家身份
--
-- 前置条件：01_seed_users.js 已执行完毕（test_user_1 ~ test_user_50 已注册）
--
-- 运行方式：
--   docker exec -i mhp-mysql mysql -uroot -p222333dyh mhp < data/02_seed_merchants.sql
--
-- 说明：
--   - 取前 50 个测试用户（id 最小的 50 个）注册为商家
--   - 使用子查询获取真实 user_id，不硬编码
--   - 幂等：INSERT IGNORE 遇到唯一键冲突时跳过

INSERT IGNORE INTO merchant (user_id, service_types, intro, city, avg_score, review_count)
SELECT
  id,
  JSON_ARRAY(FLOOR(1 + RAND() * 3)),   -- 随机服务类型 1~3
  CONCAT('测试商家简介-', nickname),
  CASE FLOOR(RAND() * 5)
    WHEN 0 THEN '北京'
    WHEN 1 THEN '上海'
    WHEN 2 THEN '广州'
    WHEN 3 THEN '深圳'
    ELSE '成都'
  END,
  ROUND(3.5 + RAND() * 1.5, 1),        -- 随机评分 3.5~5.0
  FLOOR(RAND() * 100)
FROM user
WHERE phone LIKE '1990000%'
ORDER BY id
LIMIT 50;
