package com.mhp.booksystem.vo;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 商家数据看板统计 VO
 */
@Data
public class MerchantStatsVO {

    /** 本月总订单数 */
    private Integer totalOrders;

    /** 本月已完成订单数 */
    private Integer completedOrders;

    /** 本月已取消订单数 */
    private Integer cancelledOrders;

    /** 当前待确认订单数（status=0） */
    private Integer pendingOrders;

    /** 综合平均评分 */
    private BigDecimal avgScore;

    /** 累计评价数 */
    private Integer reviewCount;
}
