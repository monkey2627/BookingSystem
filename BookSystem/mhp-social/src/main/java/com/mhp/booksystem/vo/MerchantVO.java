package com.mhp.booksystem.vo;

import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

@Data
public class MerchantVO {

    private Long id;

    private Long userId;

    private String nickname;

    private String avatar;

    private List<Integer> serviceTypes;

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
