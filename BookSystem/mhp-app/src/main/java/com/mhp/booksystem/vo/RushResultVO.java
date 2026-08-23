package com.mhp.booksystem.vo;

import lombok.Data;

@Data
public class RushResultVO {

    /** 排队名次，从 1 开始 */
    private Integer rankNo;

    /** 提示语，如"您已排在第3位，商家会依序联系您" */
    private String message;
}
