package com.mhp.booksystem.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 商家资料表 — 与 user 表是 1:1 关系（一个用户最多一份商家资料）。
 *
 * 设计说明：
 *   - 不存在"商家账号"，任何用户填写此表后即成为商家，id=user.id 的用户
 *     在前端展示时会多出"档期管理"等菜单（由 hasMerchantProfile 字段控制）。
 *   - serviceTypes 用 JSON 数组存储支持多选，MySQL JSON_CONTAINS 函数搜索，
 *     不需要建中间表，也不需要 Elasticsearch。
 *   - avgScore / reviewCount 是冗余字段，由 mhp-social 的 ReviewService 评价后
 *     通过 Feign 回写，避免每次展示商家都要实时聚合 review 表。
 */
@Data
public class Merchant {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 关联 user.id，通过此字段在 userId → merchantId 之间相互转换 */
    private Long userId;

    /** JSON 数组，如 [1,3]：1=妆 2=摄影 3=假发 4=影棚 5=后勤 6=后期 7=其他 */
    private String serviceTypes;

    private String city;

    /** 商家简介，最大 300 字，MerchantMapper.xml 按此字段做关键字模糊搜索 */
    private String intro;

    /** 以下为对外联系方式，均为外链，由商家自行填写 */
    private String alipayLink;
    private String xianyuLink;
    private String xiaohongshuLink;
    private String weiboLink;

    /** 冗余自 review 表，由 ReviewService 评价成功后通过 Feign 更新 */
    private BigDecimal avgScore;
    private Integer reviewCount;

    /** 价格区间（元），nullable，未设置时前端不展示 */
    private BigDecimal priceMin;
    private BigDecimal priceMax;

    /** 预约须知，展示在预约对话框顶部，最多 500 字 */
    private String bookingNotice;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
