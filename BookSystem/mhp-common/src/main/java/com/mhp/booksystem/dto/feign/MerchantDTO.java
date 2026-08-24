package com.mhp.booksystem.dto.feign;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class MerchantDTO {
    private Long id;
    private Long userId;
    private String serviceTypes;
    private String city;
    private String intro;
    private String alipayLink;
    private String xianyuLink;
    private String xiaohongshuLink;
    private String weiboLink;
    private BigDecimal avgScore;
    private Integer reviewCount;
    private BigDecimal priceMin;
    private BigDecimal priceMax;
    private String bookingNotice;
}
