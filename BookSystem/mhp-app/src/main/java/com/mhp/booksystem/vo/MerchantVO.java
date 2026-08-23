package com.mhp.booksystem.vo;

import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

@Data
public class MerchantVO {

    private Long id;

    private Long userId;

    /** 昵称，来自 user 表 */
    private String nickname;

    /** 头像，来自 user 表 */
    private String avatar;

    /** 服务类型列表，如 [1, 2] */
    private List<Integer> serviceTypes;

    private String city;

    private String intro;

    private String alipayLink;

    private String xianyuLink;

    private String xiaohongshuLink;

    private String weiboLink;

    private BigDecimal avgScore;

    private Integer reviewCount;
}
