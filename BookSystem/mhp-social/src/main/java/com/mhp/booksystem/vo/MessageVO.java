package com.mhp.booksystem.vo;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class MessageVO {

    private Long id;

    private Long fromUserId;

    private Long toUserId;

    private String content;

    /** 0=文字 1=图片 */
    private Integer msgType;

    private Boolean isRead;

    private LocalDateTime createTime;
}
