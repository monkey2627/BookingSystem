package com.mhp.booksystem.vo;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class MerchantStatsVO {

    private Integer totalOrders;

    private Integer completedOrders;

    private Integer cancelledOrders;

    private Integer pendingOrders;

    private BigDecimal avgScore;

    private Integer reviewCount;
}
