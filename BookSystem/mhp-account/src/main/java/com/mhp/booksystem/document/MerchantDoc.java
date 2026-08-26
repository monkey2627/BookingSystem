package com.mhp.booksystem.document;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.elasticsearch.annotations.*;

import java.util.List;

/**
 * ES 文档类，对应 ES 索引 "merchant"。
 *
 * ── 注解说明 ────────────────────────────────────────────────────────────────
 *
 * @Document(indexName = "merchant", createIndex = true)
 *   Spring Data ES 启动时如果 merchant 索引不存在，会自动调用
 *   PUT /merchant 创建索引，并将 @Field 注解翻译成 mapping。
 *   createIndex=true（默认值）：自动建索引
 *
 * @Setting(settingPath = "elasticsearch/merchant-settings.json")
 *   指定索引的 settings（分词器等）所在文件路径（相对于 classpath 根目录）。
 *   文件中定义了自定义 analyzer "ik_pinyin"（IK分词 + 拼音转换），
 *   @InnerField 中引用的 analyzer="ik_pinyin" 必须在这里提前声明，
 *   否则 ES 建索引时报 "Unknown analysis type [ik_pinyin]" 错误。
 *
 *   ⚠️ 注意：修改 @Setting 或字段 mapping 后，需要先删除旧索引再重建：
 *       DELETE http://localhost:9200/merchant
 *       POST   http://localhost:8081/internal/merchant/es/init
 *   ES 不支持修改已存在字段的 mapping 类型。
 *
 * ── 字段类型选择说明 ────────────────────────────────────────────────────────
 *
 * @MultiField — 为同一字段创建多个子字段（multi-field）：
 *   mainField  — 主字段（直接用 nickname、intro 访问），IK 中文分词
 *   innerField — 子字段（用 nickname.pinyin、intro.pinyin 访问），拼音分词
 *   好处：主字段处理中文精准搜索，子字段处理拼音模糊搜索，各司其职
 *
 * Text（中文全文搜索）：
 *   nickname、intro — 建索引用 ik_max_word（切最细，召回率高），
 *                     搜索用 ik_smart（切最粗，精准度高）。
 *   两个 analyzer 的区别：
 *     ik_max_word："专业妆娘" → ["专业", "妆娘", "专业妆娘"]（多切）
 *     ik_smart：   "专业妆娘" → ["专业", "妆娘"]（少切，避免歧义）
 *
 * ik_pinyin（子字段）：
 *   "妆娘" → IK 切词 → ["妆娘"] → pinyin filter → ["妆娘", "zhuang", "niang", "zn"]
 *   搜索 "zhuangniang" → 拼音 filter 切割 → ["zhuang", "niang"] → 命中索引词 ✓
 *   搜索 "zn"（首字母缩写）→ ["zn"] → 命中 ✓
 *
 * pinyin_filter 参数解释（定义在 merchant-settings.json）：
 *   keep_first_letter=true        → "洛天依" 产生首字母缩写 "lty"
 *   keep_full_pinyin=true         → "洛天依" 产生各字拼音 "luo","tian","yi"
 *   keep_joined_full_pinyin=false → 不产生连续拼音 "luotianyī"（减少噪音）
 *   keep_original=true            → 保留原始中文 "洛天依"（拼音字段也支持中文搜索）
 *   none_chinese_pinyin_tokenize=true → 非中文拼音串自动切割："zhuangniang"→["zhuang","niang"]
 *
 * Keyword（精确匹配）：
 *   city — 城市枚举，term 精确过滤，不分词。
 *   avatar — index=false，不参与搜索，只存储并返回给前端。
 *
 * ── 与 MySQL 字段的对应关系 ──────────────────────────────────────────────────
 * nickname / avatar ← user 表（Canal 监听 user 表变更后同步）
 * 其余字段          ← merchant 表（Canal 监听 merchant 表变更后同步）
 */
@Data
@Setting(settingPath = "elasticsearch/merchant-settings.json")
@Document(indexName = "merchant", createIndex = true)
public class MerchantDoc {

    @Id
    private Long id;

    private Long userId;

    /**
     * 昵称：中文全文 + 拼音双索引。
     * mainField  → nickname（ik_max_word/ik_smart）：搜"妆娘"、"洛天依"
     * innerField → nickname.pinyin（ik_pinyin）：搜"zhuangniang"、"lty"
     */
    @MultiField(
        mainField  = @Field(type = FieldType.Text, analyzer = "ik_max_word", searchAnalyzer = "ik_smart"),
        otherFields = {
            @InnerField(suffix = "pinyin", type = FieldType.Text, analyzer = "ik_pinyin", searchAnalyzer = "ik_pinyin")
        }
    )
    private String nickname;

    /** 头像 URL：不参与搜索（index=false），只存储用于返回给前端 */
    @Field(type = FieldType.Keyword, index = false)
    private String avatar;

    /** 城市：精确 term filter，keyword 类型不分词 */
    @Field(type = FieldType.Keyword)
    private String city;

    /** 服务类型数组（如 [1,3]）：term filter 判断是否包含某个值 */
    @Field(type = FieldType.Integer)
    private List<Integer> serviceTypes;

    /**
     * 商家简介：中文全文 + 拼音双索引（同 nickname）
     * mainField  → intro（ik_max_word/ik_smart）
     * innerField → intro.pinyin（ik_pinyin）
     */
    @MultiField(
        mainField  = @Field(type = FieldType.Text, analyzer = "ik_max_word", searchAnalyzer = "ik_smart"),
        otherFields = {
            @InnerField(suffix = "pinyin", type = FieldType.Text, analyzer = "ik_pinyin", searchAnalyzer = "ik_pinyin")
        }
    )
    private String intro;

    /** 平均评分（0~5），用于 function_score 的 avgScore 因子 */
    @Field(type = FieldType.Double)
    private Double avgScore;

    /** 接单数，用于 function_score 的 reviewCount 因子（log1p 变换防止独大） */
    @Field(type = FieldType.Integer)
    private Integer reviewCount;

    @Field(type = FieldType.Double)
    private Double priceMin;

    @Field(type = FieldType.Double)
    private Double priceMax;
}
