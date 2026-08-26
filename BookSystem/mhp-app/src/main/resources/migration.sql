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

-- 6. IK 分词器自定义词库表
--
-- 设计目标：
--   a. 存储需要被 IK 识别的 Cosplay 领域词条（角色名、活动名、行话等）
--   b. 存储需要被 IK 过滤的停止词（无意义词，如"的""了"等行业停止词）
--   c. update_time 列作为 IK 远程词库轮询的变更信号：
--      IK 定期 GET /internal/ik/ext-words，对比响应头 Last-Modified 与上次拉取时间，
--      有更新才重新加载词表，避免无效热更新。
--
-- type 枚举：1=扩展词（让 IK 认识新词）  2=停止词（让 IK 忽略无意义词）
-- category 枚举：character/anime/game/service/event/slang/manual
-- source 枚举：manual（人工）/ search_log（搜索日志挖掘）/ merchant_content（商家简介挖掘）/ trend（热搜趋势）
-- status 枚举：0=禁用  1=生效  2=待审核
CREATE TABLE IF NOT EXISTS ik_custom_word (
  id          BIGINT       NOT NULL AUTO_INCREMENT                                  COMMENT '主键',
  word        VARCHAR(64)  NOT NULL                                                 COMMENT '词条文本，IK 按此分词',
  type        TINYINT      NOT NULL DEFAULT 1                                       COMMENT '1=扩展词 2=停止词',
  category    VARCHAR(32)  NOT NULL DEFAULT 'manual'                               COMMENT 'character/anime/game/service/event/slang/manual',
  source      VARCHAR(32)  NOT NULL DEFAULT 'manual'                               COMMENT 'manual/search_log/merchant_content/trend',
  status      TINYINT      NOT NULL DEFAULT 1                                       COMMENT '0=禁用 1=生效 2=待审核',
  hit_count   INT          NOT NULL DEFAULT 0                                       COMMENT '搜索命中次数，用于发现热词、评估词条价值',
  remark      VARCHAR(256) NULL                                                     COMMENT '添加理由 / 词条说明',
  create_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP                      COMMENT '创建时间',
  update_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '最后更新时间，IK 以此判断词库是否变更',
  PRIMARY KEY (id),
  UNIQUE KEY uk_word_type (word, type),          -- 同一词条同一类型只存一条
  KEY idx_type_status (type, status),             -- 按类型+状态查询（热路径）
  KEY idx_update_time (update_time)               -- IK 轮询时查最大 update_time
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='IK 分词器自定义词库，热更新远程词典的数据源';

-- ──────────────────────────────────────────────────────────
-- 初始扩展词（type=1）— Cosplay 领域核心词汇
-- 覆盖 IK 默认词库缺失的：角色名、活动名、行业术语、网络用语
-- ──────────────────────────────────────────────────────────

-- 虚拟歌手 / 洛天依系列
INSERT IGNORE INTO ik_custom_word (word, type, category, source, remark) VALUES
('洛天依',   1, 'character', 'manual', '中国虚拟歌手，Cosplay 热门角色'),
('言和',     1, 'character', 'manual', '虚拟歌手，洛天依搭档'),
('乐正绫',   1, 'character', 'manual', '虚拟歌手'),
('初音未来', 1, 'character', 'manual', '日本 VOCALOID，国内 Cosplay 基本盘'),
('镜音双子', 1, 'character', 'manual', '初音系列角色'),
('巡音流歌', 1, 'character', 'manual', '初音系列角色');

-- 原神角色（高频搜索）
INSERT IGNORE INTO ik_custom_word (word, type, category, source, remark) VALUES
('雷电将军', 1, 'character', 'manual', '原神角色，妆娘接单热门'),
('胡桃',     1, 'character', 'manual', '原神角色'),
('香菱',     1, 'character', 'manual', '原神角色'),
('芙宁娜',   1, 'character', 'manual', '原神角色，4.2 版本后热度高'),
('纳西妲',   1, 'character', 'manual', '原神角色'),
('神里绫华', 1, 'character', 'manual', '原神角色'),
('刻晴',     1, 'character', 'manual', '原神角色'),
('甘雨',     1, 'character', 'manual', '原神角色'),
('可莉',     1, 'character', 'manual', '原神角色'),
('宵宫',     1, 'character', 'manual', '原神角色'),
('申鹤',     1, 'character', 'manual', '原神角色'),
('八重神子', 1, 'character', 'manual', '原神角色'),
('夜兰',     1, 'character', 'manual', '原神角色'),
('迪卢克',   1, 'character', 'manual', '原神角色'),
('凌华',     1, 'character', 'manual', '原神角色别称'),
('钟离',     1, 'character', 'manual', '原神角色'),
('温迪',     1, 'character', 'manual', '原神角色'),
('班尼特',   1, 'character', 'manual', '原神角色');

-- 崩坏：星穹铁道
INSERT IGNORE INTO ik_custom_word (word, type, category, source, remark) VALUES
('星穹铁道', 1, 'game',      'manual', '米哈游新游，Cosplay 热度快速增长'),
('希儿',     1, 'character', 'manual', '星穹铁道角色'),
('银狼',     1, 'character', 'manual', '星穹铁道角色'),
('符玄',     1, 'character', 'manual', '星穹铁道角色'),
('布洛妮娅', 1, 'character', 'manual', '崩坏3+星穹铁道共用角色'),
('姬子',     1, 'character', 'manual', '崩坏3角色'),
('卡芙卡',   1, 'character', 'manual', '星穹铁道角色');

-- 咒术回战
INSERT IGNORE INTO ik_custom_word (word, type, category, source, remark) VALUES
('五条悟',   1, 'character', 'manual', '咒术回战，中文圈热度最高角色之一'),
('伏黑惠',   1, 'character', 'manual', '咒术回战角色'),
('釘崎野蔷薇', 1, 'character', 'manual', '咒术回战角色'),
('虎杖悠仁', 1, 'character', 'manual', '咒术回战主角'),
('夏油杰',   1, 'character', 'manual', '咒术回战反派'),
('乙骨忧太', 1, 'character', 'manual', '咒术回战角色'),
('狗卷棘',   1, 'character', 'manual', '咒术回战角色');

-- 鬼灭之刃
INSERT IGNORE INTO ik_custom_word (word, type, category, source, remark) VALUES
('竈门炭治郎', 1, 'character', 'manual', '鬼灭之刃主角'),
('胡蝶忍',     1, 'character', 'manual', '鬼灭之刃角色，妆娘热门'),
('甘露寺蜜璃', 1, 'character', 'manual', '鬼灭之刃角色'),
('伊之助',     1, 'character', 'manual', '鬼灭之刃角色');

-- 进击的巨人 / 其他热门
INSERT IGNORE INTO ik_custom_word (word, type, category, source, remark) VALUES
('艾伦耶格尔', 1, 'character', 'manual', '进击的巨人主角'),
('米卡萨',     1, 'character', 'manual', '进击的巨人角色'),
('利威尔',     1, 'character', 'manual', '进击的巨人角色，Cosplay 热门'),
('爱蜜莉雅',   1, 'character', 'manual', '关于我转生为史莱姆，Re:Zero角色');

-- 国产动漫 / 国游
INSERT IGNORE INTO ik_custom_word (word, type, category, source, remark) VALUES
('白鹿',     1, 'character', 'manual', '斗破苍穹等国漫角色，需结合语境'),
('无极',     1, 'character', 'manual', '国漫角色'),
('重云',     1, 'character', 'manual', '原神国漫改编角色');

-- Cosplay 行业服务术语（IK 默认分词会将"妆娘"拆开）
INSERT IGNORE INTO ik_custom_word (word, type, category, source, remark) VALUES
('妆娘',     1, 'service', 'manual', 'Cosplay 造型服务方，IK 默认会拆分此词'),
('摄影师',   1, 'service', 'manual', '拍摄服务方'),
('摄影棚',   1, 'service', 'manual', '室内拍摄场地'),
('毛娘',     1, 'service', 'manual', '兽装 Cosplay，IK 不识别'),
('兽装',     1, 'service', 'manual', 'Fursuit Cosplay 类型'),
('假发片',   1, 'service', 'manual', '假发配件，区别于整顶假发'),
('假发梳理', 1, 'service', 'manual', '假发打理服务'),
('修图师',   1, 'service', 'manual', '后期处理服务方'),
('修图',     1, 'service', 'manual', '后期修图服务'),
('精修',     1, 'service', 'manual', '高质量后期修图'),
('直出',     1, 'service', 'manual', '不修图直接出片，行业术语'),
('出片',     1, 'service', 'manual', '交付照片，行业术语'),
('成片',     1, 'service', 'manual', '最终照片，行业术语'),
('档期',     1, 'service', 'manual', '服务时间段，本平台核心词'),
('接单',     1, 'service', 'manual', '接受订单，行业用语'),
('约拍',     1, 'service', 'manual', '预约拍摄，行业用语'),
('棚拍',     1, 'service', 'manual', '影棚内拍摄'),
('外拍',     1, 'service', 'manual', '室外拍摄'),
('跟妆',     1, 'service', 'manual', '跟随拍摄全程化妆服务'),
('三件套',   1, 'service', 'manual', 'Cosplay 行业指：假发+服装+妆容');

-- 活动 / 展会
INSERT IGNORE INTO ik_custom_word (word, type, category, source, remark) VALUES
('漫展',         1, 'event', 'manual', 'ACG 动漫展览，IK 可能拆分为"漫"+"展"'),
('ChinaJoy',     1, 'event', 'manual', '中国最大游戏展，英文词 IK 无法识别'),
('BilibiliWorld', 1, 'event', 'manual', 'B站线下展，IK 无法识别'),
('漫博',         1, 'event', 'manual', '北京国际漫画节缩写'),
('萤火虫',       1, 'event', 'manual', '广州萤火虫漫展，知名度高'),
('CP展',         1, 'event', 'manual', '上海 Comic Party 展会'),
('动漫节',       1, 'event', 'manual', '各地动漫节活动'),
('cosplay大赛',  1, 'event', 'manual', '漫展内的 Cosplay 比赛'),
('ACG',          1, 'slang', 'manual', '动漫/漫画/游戏的统称，IK 不识别缩写');

-- 行业网络用语
INSERT IGNORE INTO ik_custom_word (word, type, category, source, remark) VALUES
('二次元',   1, 'slang', 'manual', 'IK 可能识别，保险起见加入'),
('三次元',   1, 'slang', 'manual', '现实世界，Cosplay 语境常用'),
('破防',     1, 'slang', 'manual', '被触动，网络用语'),
('整活',     1, 'slang', 'manual', '搞有趣的事，圈内用语'),
('出警',     1, 'slang', 'manual', '批评/维权行动，圈内用语'),
('塌房',     1, 'slang', 'manual', '偶像人设崩塌，圈内用语'),
('太美了',   1, 'slang', 'manual', '常见搜索短语，避免被拆分'),
('1:1还原',  1, 'slang', 'manual', '高度还原角色，服务卖点词'),
('高还原',   1, 'slang', 'manual', '高还原度，常见商家描述词');

-- 地标 / 公园（热门外拍取景地，IK 会把"陶然亭"拆成"陶然"+"亭"）
INSERT IGNORE INTO ik_custom_word (word, type, category, source, remark) VALUES
-- 北京
('陶然亭',     1, 'manual', 'manual', '北京热门外拍公园，IK 拆分导致搜不到'),
('紫竹院',     1, 'manual', 'manual', '北京热门外拍公园'),
('颐和园',     1, 'manual', 'manual', '北京古典园林，汉服/古风外拍热门'),
('北海公园',   1, 'manual', 'manual', '北京，白塔景区'),
('圆明园',     1, 'manual', 'manual', '北京，废墟风格外拍'),
('奥林匹克公园', 1, 'manual', 'manual', '北京，现代建筑外拍'),
('朝阳公园',   1, 'manual', 'manual', '北京，商业区附近'),
('什刹海',     1, 'manual', 'manual', '北京，胡同风格外拍'),
('国家植物园', 1, 'manual', 'manual', '北京，季节性外拍热门'),
-- 上海
('世纪公园',   1, 'manual', 'manual', '上海，大面积草坪外拍'),
('迪士尼',     1, 'manual', 'manual', '上海迪士尼乐园，角色扮演热门地'),
('豫园',       1, 'manual', 'manual', '上海传统园林，古风外拍'),
('人民广场',   1, 'manual', 'manual', '上海地标'),
-- 成都 / 其他城市
('宽窄巷子',   1, 'manual', 'manual', '成都，古风外拍热门'),
('天府广场',   1, 'manual', 'manual', '成都地标'),
('西湖',       1, 'manual', 'manual', '杭州，汉服外拍圣地'),
('白云山',     1, 'manual', 'manual', '广州，自然风景外拍'),
('长隆',       1, 'manual', 'manual', '广州长隆主题公园');

-- 展馆 / 活动场地（IK 会把"国家会议中心"分割）
INSERT IGNORE INTO ik_custom_word (word, type, category, source, remark) VALUES
('国家会议中心', 1, 'event', 'manual', '北京，ChinaJoy/漫博常用场馆'),
('首都体育馆',   1, 'event', 'manual', '北京，大型动漫展场馆'),
('国家体育馆',   1, 'event', 'manual', '北京，鸟巢附近，大型活动场馆'),
('上海世博展览馆', 1, 'event', 'manual', '上海，BiliBili World 等展会场馆'),
('广州国际会展中心', 1, 'event', 'manual', '广州，动漫节场馆'),
('成都世纪城',   1, 'event', 'manual', '成都国际会展中心别称'),
('北京国际展览中心', 1, 'event', 'manual', '北京，展会场馆');

-- ──────────────────────────────────────────────────────────
-- 初始停止词（type=2）— Cosplay 语境无意义词
-- 补充 IK 默认停止词表未涵盖的行业停止词
-- ──────────────────────────────────────────────────────────
INSERT IGNORE INTO ik_custom_word (word, type, category, source, remark) VALUES
('求',   2, 'slang', 'manual', '"求妆娘"中的"求"单独出现无意义'),
('求求', 2, 'slang', 'manual', '网络语气词，搜索无意义'),
('哪里', 2, 'slang', 'manual', '"哪里有妆娘"中的"哪里"无检索价值'),
('怎么', 2, 'slang', 'manual', '疑问词，搜索无意义'),
('好的', 2, 'slang', 'manual', '应答词，无检索价值'),
('谢谢', 2, 'slang', 'manual', '礼貌用语，无检索价值'),
('嗯嗯', 2, 'slang', 'manual', '语气词，无检索价值'),
('大佬', 2, 'slang', 'manual', '口头语，单独出现无意义');
