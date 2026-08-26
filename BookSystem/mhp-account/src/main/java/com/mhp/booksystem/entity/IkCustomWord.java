package com.mhp.booksystem.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * IK 分词器自定义词库实体。
 *
 * 字段设计说明：
 *
 *   word        — 词条文本（如"妆娘"、"洛天依"），IK 读取后作为原子词不再拆分。
 *
 *   type        — 词条类型：
 *                   1 = 扩展词（让 IK 认识新词，影响 analyzer 建索引和 searchAnalyzer 搜索）
 *                   2 = 停止词（让 IK 过滤无意义词，如语气词、礼貌用语）
 *
 *   category    — 词条分类（便于批量管理和统计）：
 *                   character       — 二次元角色名
 *                   anime/game      — 作品/游戏名
 *                   service         — 行业服务术语（妆娘、修图师、档期…）
 *                   event           — 活动/展会名称（漫展、ChinaJoy…）
 *                   slang           — 圈内网络用语
 *                   manual          — 无特定分类，人工添加
 *
 *   source      — 词条来源（便于追溯和去重）：
 *                   manual          — 人工运营添加
 *                   search_log      — 从搜索日志中挖掘的高频未识别词
 *                   merchant_content — 从商家简介中挖掘的行业词汇
 *                   trend           — 实时热搜趋势（微博/B站热搜）
 *
 *   status      — 生命周期：0=禁用  1=生效  2=待审核
 *                   待审核状态：从日志挖掘出的词需人工确认才上线，
 *                   避免把误拼或敏感词直接推入分词器。
 *
 *   hit_count   — 搜索命中次数，由搜索日志定期更新。
 *                   用途：命中越少的词可能是噪音词，可定期清理；
 *                   命中激增的词可能是新热词，提示运营人员关注。
 *
 *   update_time — IK 远程词库热更新的核心依据：
 *                   IK 定期 GET /internal/ik/ext-words，
 *                   响应头 Last-Modified = MAX(update_time WHERE type=1 AND status=1)，
 *                   若与上次拉取时相同则跳过加载，有变化才重新拉取词表。
 *                   所以词条的任何变更（新增/修改/禁用）都会触发 update_time 更新，
 *                   进而在下一个轮询周期自动推送到 ES。
 */
@Data
@TableName("ik_custom_word")
public class IkCustomWord {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 词条文本，与 type 联合唯一（uk_word_type） */
    private String word;

    /** 1=扩展词  2=停止词 */
    private Integer type;

    /** character/anime/game/service/event/slang/manual */
    private String category;

    /** manual/search_log/merchant_content/trend */
    private String source;

    /** 0=禁用  1=生效  2=待审核 */
    private Integer status;

    /** 搜索命中次数，用于评估词条价值 */
    private Integer hitCount;

    /** 添加理由 / 词条说明 */
    private String remark;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    /** ON UPDATE CURRENT_TIMESTAMP — IK 以此判断词库是否发生变更 */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
