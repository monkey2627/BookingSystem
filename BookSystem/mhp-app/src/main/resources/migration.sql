-- ============================================================
-- 迁移脚本：账号统一 + 服务类型分级
-- 执行顺序：按顺序执行，先备份数据
-- ============================================================

-- 1. Schedule 表新增服务类型列
ALTER TABLE schedule ADD COLUMN service_type TINYINT NOT NULL DEFAULT 7
  COMMENT '1=妆 2=摄影 3=假发 4=影棚 5=后勤 6=后期 7=其他';

-- 2. Booking 表新增服务类型列（冗余自 Schedule，避免查询时 JOIN）
ALTER TABLE booking ADD COLUMN service_type TINYINT NOT NULL DEFAULT 7
  COMMENT '1=妆 2=摄影 3=假发 4=影棚 5=后勤 6=后期 7=其他';

-- 3. 迁移 merchant.service_types JSON 中的旧枚举值
--    旧：1=妆娘 2=摄影 3=毛娘 4=后勤 5=后期
--    新：1=妆   2=摄影 3=假发 4=影棚 5=后勤 6=后期 7=其他
--    映射：旧4→新5，旧5→新6（1/2/3 值不变，仅语义改名）
--    注意：先替换 5→6，再替换 4→5，防止值被双重替换
UPDATE merchant m
JOIN (
  SELECT id,
    JSON_ARRAYAGG(
      CASE val WHEN 5 THEN 6 WHEN 4 THEN 5 ELSE val END
    ) AS new_types
  FROM merchant,
  JSON_TABLE(service_types, '$[*]' COLUMNS (val INT PATH '$')) jt
  GROUP BY id
) tmp ON m.id = tmp.id
SET m.service_types = tmp.new_types
WHERE JSON_SEARCH(m.service_types, 'one', '4') IS NOT NULL
   OR JSON_SEARCH(m.service_types, 'one', '5') IS NOT NULL;

-- 4. 删除 User.role 列（账号统一后不再区分角色）
ALTER TABLE `user` DROP COLUMN role;

-- 5. Merchant 表新增价格范围和预约须知列
ALTER TABLE merchant
  ADD COLUMN price_min  DECIMAL(10, 2) NULL COMMENT '价格下限（元）',
  ADD COLUMN price_max  DECIMAL(10, 2) NULL COMMENT '价格上限（元）',
  ADD COLUMN booking_notice VARCHAR(500) NULL COMMENT '预约须知';
