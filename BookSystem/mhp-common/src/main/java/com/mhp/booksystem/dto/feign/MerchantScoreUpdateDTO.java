package com.mhp.booksystem.dto.feign;

import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;

@Data
public class MerchantScoreUpdateDTO implements Serializable {
    private static final long serialVersionUID = 1L;
    private BigDecimal avgScore;
    private Integer reviewCount;
}
