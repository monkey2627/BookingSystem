package com.mhp.booksystem.document;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.elasticsearch.annotations.Document;
import org.springframework.data.elasticsearch.annotations.Field;
import org.springframework.data.elasticsearch.annotations.FieldType;

import java.util.List;

/**
 * ES 文档类，对应 ES 索引 "merchant"。
 *
 * ── 字段类型选择说明 ───────────────────────────────────────────────────────
 *
 * Text（中文全文搜索字段）：
 *   nickname、intro — 存储时用 ik_max_word 切最细粒度词（提升召回），
 *                     搜索时用 ik_smart 切最粗粒度词（提升精准度），两者组合效果最佳。
 *   ik_max_word：把"妆娘服务"切成"妆娘"+"服务"+"妆娘服务"，建索引词多，找得到的多。
 *   ik_smart：把"妆娘服务"只切成"妆娘"+"服务"，搜索词少，精准命中。
 *
 * Keyword（精确匹配/过滤字段）：
 *   city — 城市是枚举值（北京/上海…），用 term 精确过滤，不需要分词。
 *   avatar — 不参与搜索（index=false），只存储用于返回给前端。
 *
 * Integer（数组 term 过滤）：
 *   serviceTypes — [1, 3] 等整数数组，用 term filter 匹配"包含某种服务类型"。
 *
 * ── 与 MySQL 字段的对应关系 ────────────────────────────────────────────────
 * nickname / avatar  ← user 表（Canal 监听 user 表变更同步）
 * 其余字段            ← merchant 表（Canal 监听 merchant 表变更同步）
 */
@Data
@Document(indexName = "merchant")
public class MerchantDoc {

    @Id
    private Long id;

    /** 关联的用户 id（merchant.user_id） */
    private Long userId;

    /** 昵称：来自 user 表，Text 类型支持中文全文搜索，权重为 intro 的 2 倍（搜索时在 query 层配置） */
    @Field(type = FieldType.Text, analyzer = "ik_max_word", searchAnalyzer = "ik_smart")
    private String nickname;

    /** 头像 URL：不参与搜索，index=false 节省 ES 存储空间 */
    @Field(type = FieldType.Keyword, index = false)
    private String avatar;

    /** 城市：精确 term filter，不分词 */
    @Field(type = FieldType.Keyword)
    private String city;

    /** 服务类型数组（如 [1,3]）：用 term filter 判断是否包含某个值 */
    @Field(type = FieldType.Integer)
    private List<Integer> serviceTypes;

    /** 商家简介：中文全文搜索 */
    @Field(type = FieldType.Text, analyzer = "ik_max_word", searchAnalyzer = "ik_smart")
    private String intro;

    /** 平均评分：用于无关键词时按评分降序排序 */
    @Field(type = FieldType.Double)
    private Double avgScore;

    @Field(type = FieldType.Integer)
    private Integer reviewCount;

    @Field(type = FieldType.Double)
    private Double priceMin;

    @Field(type = FieldType.Double)
    private Double priceMax;
}
