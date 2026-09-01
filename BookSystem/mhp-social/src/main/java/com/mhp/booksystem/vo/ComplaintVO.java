package com.mhp.booksystem.vo;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class ComplaintVO {

    private Long id;
    private Long orderId;
    private Long complainantId;
    private String complainantNickname;
    private String reason;
    private String evidence;
    /** 0=待处理 1=处理中 2=已处理 */
    private Integer status;
    private String adminReply;
    private LocalDateTime createTime;
}
