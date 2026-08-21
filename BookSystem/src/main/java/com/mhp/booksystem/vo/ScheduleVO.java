package com.mhp.booksystem.vo;

import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
public class ScheduleVO {

    private Long id;

    private Long merchantId;

    private LocalDate date;

    private String timeSlot;

    /** 0=空闲 1=已预约 2=不可用 */
    private Integer status;

    /** 0=直接预约 1=抢档期 */
    private Integer bookType;

    /** 1=妆 2=摄影 3=假发 4=影棚 5=后勤 6=后期 7=其他 */
    private Integer serviceType;

    private LocalDateTime rushOpenTime;

    private Integer maxQueueSize;

    /** 当前排队人数，仅 bookType=1 时有意义 */
    private Integer currentQueueSize;
}
