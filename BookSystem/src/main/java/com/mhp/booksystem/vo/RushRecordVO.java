package com.mhp.booksystem.vo;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 排队记录展示 VO（供商家查看某档期的抢购队列）
 */
@Data
public class RushRecordVO {

    private Long id;

    private Long scheduleId;

    private Long userId;

    /** 客人昵称，关联 user 表查得 */
    private String userNickname;

    /** 排队名次（从 1 开始） */
    private Integer rankNo;

    /** 0=排队中 1=已联系 2=已转化 3=已放弃 */
    private Integer status;

    private LocalDateTime rushTime;
}
