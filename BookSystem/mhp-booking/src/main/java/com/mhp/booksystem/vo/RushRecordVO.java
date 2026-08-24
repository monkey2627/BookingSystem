package com.mhp.booksystem.vo;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class RushRecordVO {

    private Long id;

    private Long scheduleId;

    private Long userId;

    private String userNickname;

    private Integer rankNo;

    /** 0=排队中 1=已联系 2=已转化 3=已放弃 */
    private Integer status;

    private LocalDateTime rushTime;
}
