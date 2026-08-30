-- ============================================================
-- MHP 档期预约平台 — 完整建库建表脚本
-- 适用场景：全新服务器首次部署，mhp 库为空时执行
-- 执行顺序：直接整体执行，建完后再执行 migration.sql（添加 IK 词库初始数据）
-- ============================================================

CREATE DATABASE IF NOT EXISTS mhp DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE mhp;

-- ============================================================
-- 1. user — 用户表
--    所有人（客人和商家）共用同一张表，不区分角色。
--    是否是商家由 merchant 表有无对应记录决定。
-- ============================================================
CREATE TABLE IF NOT EXISTS `user` (
  `id`          BIGINT       NOT NULL AUTO_INCREMENT              COMMENT '主键',
  `phone`       VARCHAR(20)  NOT NULL                            COMMENT '手机号，唯一登录凭证',
  `password`    VARCHAR(64)  NOT NULL                            COMMENT 'MD5 加密密码（Hutool SecureUtil.md5）',
  `nickname`    VARCHAR(50)  NULL                                COMMENT '昵称',
  `avatar`      VARCHAR(255) NULL                                COMMENT '头像 URL（七牛云 CDN 链接）',
  `gender`      TINYINT      NOT NULL DEFAULT 0                  COMMENT '0=未填 1=男 2=女 3=其他',
  `birthday`    DATE         NULL                                COMMENT '生日',
  `create_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP  COMMENT '创建时间',
  `update_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP
                             ON UPDATE CURRENT_TIMESTAMP         COMMENT '更新时间',
  `is_deleted`  TINYINT      NOT NULL DEFAULT 0                  COMMENT '逻辑删除：0=正常 1=已删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_phone` (`phone`),
  KEY `idx_is_deleted` (`is_deleted`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户表';

-- ============================================================
-- 2. merchant — 商家资料表
--    与 user 是 1:1 关系，userId = user.id。
--    avgScore / reviewCount 由 ReviewService 评价后通过 Feign 回写。
-- ============================================================
CREATE TABLE IF NOT EXISTS `merchant` (
  `id`               BIGINT        NOT NULL AUTO_INCREMENT              COMMENT '主键',
  `user_id`          BIGINT        NOT NULL                            COMMENT '关联 user.id，1:1',
  `service_types`    JSON          NULL                                COMMENT '服务类型 JSON 数组，如 [1,3]；1=妆 2=摄影 3=假发 4=影棚 5=后勤 6=后期 7=其他',
  `city`             VARCHAR(50)   NULL                                COMMENT '所在城市',
  `intro`            VARCHAR(300)  NULL                                COMMENT '商家简介，ES 搜索字段',
  `alipay_link`      VARCHAR(255)  NULL                                COMMENT '支付宝外链',
  `xianyu_link`      VARCHAR(255)  NULL                                COMMENT '闲鱼外链',
  `xiaohongshu_link` VARCHAR(255)  NULL                                COMMENT '小红书外链',
  `weibo_link`       VARCHAR(255)  NULL                                COMMENT '微博外链',
  `avg_score`        DECIMAL(3,2)  NULL                                COMMENT '平均评分（0.00~5.00），冗余字段由 ReviewService 更新',
  `review_count`     INT           NOT NULL DEFAULT 0                  COMMENT '评价总数，冗余字段',
  `price_min`        DECIMAL(10,2) NULL                                COMMENT '价格下限（元），未设置时前端不展示',
  `price_max`        DECIMAL(10,2) NULL                                COMMENT '价格上限（元）',
  `booking_notice`   VARCHAR(500)  NULL                                COMMENT '预约须知，展示在预约对话框顶部',
  `create_time`      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP  COMMENT '创建时间',
  `update_time`      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP
                                  ON UPDATE CURRENT_TIMESTAMP          COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_id` (`user_id`),
  KEY `idx_city` (`city`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='商家资料表';

-- ============================================================
-- 3. schedule — 档期表
--    商家发布的可预约时间段。
--    两种模式：直接预约（bookType=0）和抢档期（bookType=1）。
-- ============================================================
CREATE TABLE IF NOT EXISTS `schedule` (
  `id`             BIGINT      NOT NULL AUTO_INCREMENT              COMMENT '主键',
  `merchant_id`    BIGINT      NOT NULL                            COMMENT '关联 merchant.id',
  `date`           DATE        NOT NULL                            COMMENT '档期日期',
  `time_slot`      VARCHAR(20) NULL                                COMMENT '时间段描述，如 "09:00-12:00"，null 表示全天',
  `status`         TINYINT     NOT NULL DEFAULT 0                  COMMENT '0=空闲 1=已预约 2=不可用',
  `book_type`      TINYINT     NOT NULL DEFAULT 0                  COMMENT '0=直接预约 1=抢档期',
  `rush_open_time` DATETIME    NULL                                COMMENT '抢档期开放时间，未到此时间前不允许抢',
  `max_queue_size` INT         NOT NULL DEFAULT 10                 COMMENT '抢档期队列最大人数',
  `service_type`   TINYINT     NOT NULL DEFAULT 7                  COMMENT '1=妆 2=摄影 3=假发 4=影棚 5=后勤 6=后期 7=其他',
  `create_time`    DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP  COMMENT '创建时间',
  `update_time`    DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP
                               ON UPDATE CURRENT_TIMESTAMP         COMMENT '更新时间',
  PRIMARY KEY (`id`),
  KEY `idx_merchant_date` (`merchant_id`, `date`),
  KEY `idx_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='档期表';

-- ============================================================
-- 4. booking — 预约表
--    状态机：0=待确认 → 2=已定档 → 3=已完成
--             0=待确认 → 4=已取消（任意阶段可取消）
--    状态 1（待付款）保留枚举但当前未使用（无支付集成）。
--    orderNo 对外展示用 UUID，防止客人通过连续 id 猜出总量。
-- ============================================================
CREATE TABLE IF NOT EXISTS `booking` (
  `id`                   BIGINT       NOT NULL AUTO_INCREMENT              COMMENT '主键（内部使用）',
  `order_no`             VARCHAR(36)  NOT NULL                            COMMENT 'UUID 对外订单号，前端展示用',
  `user_id`              BIGINT       NOT NULL                            COMMENT '客人 user.id',
  `merchant_id`          BIGINT       NOT NULL                            COMMENT '商家 merchant.id',
  `schedule_id`          BIGINT       NOT NULL                            COMMENT '关联 schedule.id',
  `status`               TINYINT      NOT NULL DEFAULT 0                  COMMENT '0=待确认 1=待付款(未用) 2=已定档 3=已完成 4=已取消',
  `remark`               VARCHAR(200) NULL                                COMMENT '客人备注',
  `questionnaire_answer` TEXT         NULL                                COMMENT '问卷答案 JSON，前端拼接后透传存储',
  `service_type`         TINYINT      NOT NULL DEFAULT 7                  COMMENT '冗余自 schedule.service_type，查列表时免 JOIN',
  `create_time`          DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP  COMMENT '预约创建时间',
  `update_time`          DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP
                                     ON UPDATE CURRENT_TIMESTAMP          COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_order_no` (`order_no`),
  KEY `idx_user_id` (`user_id`),
  KEY `idx_merchant_id` (`merchant_id`),
  KEY `idx_schedule_id` (`schedule_id`),
  KEY `idx_status_create` (`status`, `create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='预约表';

-- ============================================================
-- 5. rush_record — 抢档期排队记录表
--    Lua 脚本原子写入 Redis ZSET，成功后落库。
--    userId + scheduleId 联合唯一，防止重复抢。
-- ============================================================
CREATE TABLE IF NOT EXISTS `rush_record` (
  `id`          BIGINT   NOT NULL AUTO_INCREMENT              COMMENT '主键',
  `schedule_id` BIGINT   NOT NULL                            COMMENT '关联 schedule.id',
  `user_id`     BIGINT   NOT NULL                            COMMENT '排队用户 user.id',
  `rank_no`     INT      NOT NULL                            COMMENT '排队名次（1=第一个）',
  `status`      TINYINT  NOT NULL DEFAULT 0                  COMMENT '0=排队中 1=已联系 2=已转化 3=已放弃',
  `rush_time`   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP  COMMENT '抢档期时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_schedule_user` (`schedule_id`, `user_id`),
  KEY `idx_schedule_id` (`schedule_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='抢档期排队记录表';

-- ============================================================
-- 6. questionnaire_template — 问卷模板表
--    商家配置预约前需客人填写的问题，isRequired=1 时预约必须填。
--    questions 字段存 JSON 数组，含题目/类型/选项/是否必填。
-- ============================================================
CREATE TABLE IF NOT EXISTS `questionnaire_template` (
  `id`          BIGINT       NOT NULL AUTO_INCREMENT              COMMENT '主键',
  `merchant_id` BIGINT       NOT NULL                            COMMENT '关联 merchant.id',
  `title`       VARCHAR(100) NULL                                COMMENT '问卷标题',
  `questions`   TEXT         NOT NULL                            COMMENT '题目 JSON 数组',
  `is_required` TINYINT      NOT NULL DEFAULT 0                  COMMENT '0=非必填 1=预约时必须填写',
  `create_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP  COMMENT '创建时间',
  `update_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP
                             ON UPDATE CURRENT_TIMESTAMP         COMMENT '更新时间',
  PRIMARY KEY (`id`),
  KEY `idx_merchant_id` (`merchant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='预约问卷模板表';

-- ============================================================
-- 7. post — 动态表
--    商家发布的作品/动态，支持图片。
--    likeCount 冗余字段，由 PostService.toggleLike() 通过 Redis 原子计数后回写。
-- ============================================================
CREATE TABLE IF NOT EXISTS `post` (
  `id`          BIGINT   NOT NULL AUTO_INCREMENT              COMMENT '主键',
  `merchant_id` BIGINT   NOT NULL                            COMMENT '发布者 merchant.id',
  `content`     TEXT     NULL                                COMMENT '动态正文',
  `images`      TEXT     NULL                                COMMENT '图片 URL JSON 数组（七牛云链接）',
  `like_count`  INT      NOT NULL DEFAULT 0                  COMMENT '点赞数，Redis 计数后定期回写',
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP  COMMENT '发布时间',
  `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
                         ON UPDATE CURRENT_TIMESTAMP         COMMENT '更新时间',
  `is_deleted`  TINYINT  NOT NULL DEFAULT 0                  COMMENT '逻辑删除：0=正常 1=已删除',
  PRIMARY KEY (`id`),
  KEY `idx_merchant_id` (`merchant_id`),
  KEY `idx_create_time` (`create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='商家动态表';

-- ============================================================
-- 8. follow — 关注表
--    客人关注商家，userId + merchantId 联合唯一防重复关注。
-- ============================================================
CREATE TABLE IF NOT EXISTS `follow` (
  `id`          BIGINT   NOT NULL AUTO_INCREMENT              COMMENT '主键',
  `user_id`     BIGINT   NOT NULL                            COMMENT '关注者 user.id',
  `merchant_id` BIGINT   NOT NULL                            COMMENT '被关注商家 merchant.id',
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP  COMMENT '关注时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_merchant` (`user_id`, `merchant_id`),
  KEY `idx_merchant_id` (`merchant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='关注表';

-- ============================================================
-- 9. review — 评价表
--    一条 booking 只能评价一次（order_id 唯一）。
--    评价成功后，ReviewService 通过 Feign 更新 merchant.avg_score 和 review_count。
-- ============================================================
CREATE TABLE IF NOT EXISTS `review` (
  `id`          BIGINT       NOT NULL AUTO_INCREMENT              COMMENT '主键',
  `order_id`    BIGINT       NOT NULL                            COMMENT '关联 booking.id，一次预约只能评价一次',
  `user_id`     BIGINT       NOT NULL                            COMMENT '评价人 user.id',
  `merchant_id` BIGINT       NOT NULL                            COMMENT '被评价商家 merchant.id',
  `score`       TINYINT      NOT NULL                            COMMENT '评分 1~5',
  `content`     VARCHAR(500) NULL                                COMMENT '评价内容',
  `images`      TEXT         NULL                                COMMENT '评价图片 URL JSON 数组',
  `reply`       VARCHAR(300) NULL                                COMMENT '商家回复',
  `create_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP  COMMENT '评价时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_order_id` (`order_id`),
  KEY `idx_merchant_id` (`merchant_id`),
  KEY `idx_user_id` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='评价表';

-- ============================================================
-- 10. complaint — 投诉表
--     对已完成/已取消的预约发起投诉。
--     complainantId=举报人，respondentId=被举报人。
--     adminReply 由管理员后台填写（后台功能未完成，字段预留）。
-- ============================================================
CREATE TABLE IF NOT EXISTS `complaint` (
  `id`             BIGINT       NOT NULL AUTO_INCREMENT              COMMENT '主键',
  `order_id`       BIGINT       NOT NULL                            COMMENT '关联 booking.id',
  `complainant_id` BIGINT       NOT NULL                            COMMENT '投诉发起人 user.id',
  `respondent_id`  BIGINT       NOT NULL                            COMMENT '被投诉人 user.id',
  `reason`         VARCHAR(500) NOT NULL                            COMMENT '投诉原因',
  `evidence`       TEXT         NULL                                COMMENT '证据图片 URL JSON 数组',
  `status`         TINYINT      NOT NULL DEFAULT 0                  COMMENT '0=待处理 1=处理中 2=已处理',
  `admin_reply`    VARCHAR(500) NULL                                COMMENT '管理员处理回复',
  `create_time`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP  COMMENT '投诉时间',
  PRIMARY KEY (`id`),
  KEY `idx_order_id` (`order_id`),
  KEY `idx_complainant_id` (`complainant_id`),
  KEY `idx_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='投诉表';

-- ============================================================
-- 11. message — 私信表
--     点对点聊天，fromUserId → toUserId 发消息。
--     isRead 由 MessageService 消费方更新，前端 WebSocket 实时推送。
--     索引设计：查会话列表需按 (fromUserId, toUserId) 组合查。
-- ============================================================
CREATE TABLE IF NOT EXISTS `message` (
  `id`           BIGINT   NOT NULL AUTO_INCREMENT              COMMENT '主键',
  `from_user_id` BIGINT   NOT NULL                            COMMENT '发送方 user.id',
  `to_user_id`   BIGINT   NOT NULL                            COMMENT '接收方 user.id',
  `content`      TEXT     NOT NULL                            COMMENT '消息内容（文字或图片 URL）',
  `msg_type`     TINYINT  NOT NULL DEFAULT 0                  COMMENT '0=文字 1=图片',
  `is_read`      TINYINT  NOT NULL DEFAULT 0                  COMMENT '0=未读 1=已读',
  `create_time`  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP  COMMENT '发送时间',
  PRIMARY KEY (`id`),
  KEY `idx_to_user_id` (`to_user_id`, `is_read`),
  KEY `idx_conversation` (`from_user_id`, `to_user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='私信表';

-- ============================================================
-- 12. ik_custom_word — IK 分词器自定义词库表
--     type=1 扩展词（让 IK 识别新词）；type=2 停止词（让 IK 过滤无意义词）。
--     update_time 是 IK 远程词库热更新的触发信号：
--       GET /internal/ik/ext-words 响应头 Last-Modified = MAX(update_time)，
--       IK 对比后决定是否重新加载词表。
-- ============================================================
CREATE TABLE IF NOT EXISTS `ik_custom_word` (
  `id`          BIGINT       NOT NULL AUTO_INCREMENT                                  COMMENT '主键',
  `word`        VARCHAR(64)  NOT NULL                                                 COMMENT '词条文本',
  `type`        TINYINT      NOT NULL DEFAULT 1                                       COMMENT '1=扩展词 2=停止词',
  `category`    VARCHAR(32)  NOT NULL DEFAULT 'manual'                               COMMENT 'character/anime/game/service/event/slang/manual',
  `source`      VARCHAR(32)  NOT NULL DEFAULT 'manual'                               COMMENT 'manual/search_log/merchant_content/trend',
  `status`      TINYINT      NOT NULL DEFAULT 1                                       COMMENT '0=禁用 1=生效 2=待审核',
  `hit_count`   INT          NOT NULL DEFAULT 0                                       COMMENT '搜索命中次数，评估词条价值',
  `remark`      VARCHAR(256) NULL                                                     COMMENT '添加理由 / 词条说明',
  `create_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP                      COMMENT '创建时间',
  `update_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP
                             ON UPDATE CURRENT_TIMESTAMP                             COMMENT '最后更新时间，IK 以此判断词库是否变更',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_word_type` (`word`, `type`),
  KEY `idx_type_status` (`type`, `status`),
  KEY `idx_update_time` (`update_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='IK 分词器自定义词库';

-- ============================================================
-- IK 词库初始数据（INSERT IGNORE，重复执行安全）
-- ============================================================

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
('五条悟',     1, 'character', 'manual', '咒术回战，中文圈热度最高角色之一'),
('伏黑惠',     1, 'character', 'manual', '咒术回战角色'),
('釘崎野蔷薇', 1, 'character', 'manual', '咒术回战角色'),
('虎杖悠仁',   1, 'character', 'manual', '咒术回战主角'),
('夏油杰',     1, 'character', 'manual', '咒术回战反派'),
('乙骨忧太',   1, 'character', 'manual', '咒术回战角色'),
('狗卷棘',     1, 'character', 'manual', '咒术回战角色');

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
('白鹿', 1, 'character', 'manual', '斗破苍穹等国漫角色，需结合语境'),
('无极', 1, 'character', 'manual', '国漫角色'),
('重云', 1, 'character', 'manual', '原神国漫改编角色');

-- Cosplay 行业服务术语
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
('漫展',          1, 'event', 'manual', 'ACG 动漫展览，IK 可能拆分为"漫"+"展"'),
('ChinaJoy',      1, 'event', 'manual', '中国最大游戏展，英文词 IK 无法识别'),
('BilibiliWorld',  1, 'event', 'manual', 'B站线下展，IK 无法识别'),
('漫博',          1, 'event', 'manual', '北京国际漫画节缩写'),
('萤火虫',        1, 'event', 'manual', '广州萤火虫漫展，知名度高'),
('CP展',          1, 'event', 'manual', '上海 Comic Party 展会'),
('动漫节',        1, 'event', 'manual', '各地动漫节活动'),
('cosplay大赛',   1, 'event', 'manual', '漫展内的 Cosplay 比赛'),
('ACG',           1, 'slang', 'manual', '动漫/漫画/游戏的统称，IK 不识别缩写');

-- 行业网络用语
INSERT IGNORE INTO ik_custom_word (word, type, category, source, remark) VALUES
('二次元',  1, 'slang', 'manual', 'IK 可能识别，保险起见加入'),
('三次元',  1, 'slang', 'manual', '现实世界，Cosplay 语境常用'),
('破防',    1, 'slang', 'manual', '被触动，网络用语'),
('整活',    1, 'slang', 'manual', '搞有趣的事，圈内用语'),
('出警',    1, 'slang', 'manual', '批评/维权行动，圈内用语'),
('塌房',    1, 'slang', 'manual', '偶像人设崩塌，圈内用语'),
('太美了',  1, 'slang', 'manual', '常见搜索短语，避免被拆分'),
('1:1还原', 1, 'slang', 'manual', '高度还原角色，服务卖点词'),
('高还原',  1, 'slang', 'manual', '高还原度，常见商家描述词');

-- 热门外拍取景地
INSERT IGNORE INTO ik_custom_word (word, type, category, source, remark) VALUES
('陶然亭',       1, 'manual', 'manual', '北京热门外拍公园，IK 拆分导致搜不到'),
('紫竹院',       1, 'manual', 'manual', '北京热门外拍公园'),
('颐和园',       1, 'manual', 'manual', '北京古典园林，汉服/古风外拍热门'),
('北海公园',     1, 'manual', 'manual', '北京，白塔景区'),
('圆明园',       1, 'manual', 'manual', '北京，废墟风格外拍'),
('奥林匹克公园', 1, 'manual', 'manual', '北京，现代建筑外拍'),
('朝阳公园',     1, 'manual', 'manual', '北京，商业区附近'),
('什刹海',       1, 'manual', 'manual', '北京，胡同风格外拍'),
('国家植物园',   1, 'manual', 'manual', '北京，季节性外拍热门'),
('世纪公园',     1, 'manual', 'manual', '上海，大面积草坪外拍'),
('迪士尼',       1, 'manual', 'manual', '上海迪士尼乐园，角色扮演热门地'),
('豫园',         1, 'manual', 'manual', '上海传统园林，古风外拍'),
('人民广场',     1, 'manual', 'manual', '上海地标'),
('宽窄巷子',     1, 'manual', 'manual', '成都，古风外拍热门'),
('天府广场',     1, 'manual', 'manual', '成都地标'),
('西湖',         1, 'manual', 'manual', '杭州，汉服外拍圣地'),
('白云山',       1, 'manual', 'manual', '广州，自然风景外拍'),
('长隆',         1, 'manual', 'manual', '广州长隆主题公园');

-- 展馆 / 活动场地
INSERT IGNORE INTO ik_custom_word (word, type, category, source, remark) VALUES
('国家会议中心',     1, 'event', 'manual', '北京，ChinaJoy/漫博常用场馆'),
('首都体育馆',       1, 'event', 'manual', '北京，大型动漫展场馆'),
('国家体育馆',       1, 'event', 'manual', '北京，鸟巢附近，大型活动场馆'),
('上海世博展览馆',   1, 'event', 'manual', '上海，BiliBili World 等展会场馆'),
('广州国际会展中心', 1, 'event', 'manual', '广州，动漫节场馆'),
('成都世纪城',       1, 'event', 'manual', '成都国际会展中心别称'),
('北京国际展览中心', 1, 'event', 'manual', '北京，展会场馆');

-- 停止词（Cosplay 语境无意义词）
INSERT IGNORE INTO ik_custom_word (word, type, category, source, remark) VALUES
('求',   2, 'slang', 'manual', '"求妆娘"中的"求"单独出现无意义'),
('求求', 2, 'slang', 'manual', '网络语气词，搜索无意义'),
('哪里', 2, 'slang', 'manual', '"哪里有妆娘"中的"哪里"无检索价值'),
('怎么', 2, 'slang', 'manual', '疑问词，搜索无意义'),
('好的', 2, 'slang', 'manual', '应答词，无检索价值'),
('谢谢', 2, 'slang', 'manual', '礼貌用语，无检索价值'),
('嗯嗯', 2, 'slang', 'manual', '语气词，无检索价值'),
('大佬', 2, 'slang', 'manual', '口头语，单独出现无意义');
