package com.mhp.booksystem.dto.feign;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class MerchantScoreUpdateDTO {
    private BigDecimal avgScore;
    private Integer reviewCount;
}
