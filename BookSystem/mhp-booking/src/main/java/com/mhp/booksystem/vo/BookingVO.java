package com.mhp.booksystem.vo;

import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
public class BookingVO {

    private Long id;

    private String orderNo;

    private LocalDate scheduleDate;

    private String timeSlot;

    private Long merchantId;

    /** 商家账号的 userId，用于客人发起聊天 */
    private Long merchantUserId;

    private String merchantNickname;

    private Long userId;

    private String userNickname;

    /** 0=待确认 1=待付款 2=已定档 3=已完成 4=已取消 */
    private Integer status;

    /** 1=妆 2=摄影 3=假发 4=影棚 5=后勤 6=后期 7=其他 */
    private Integer serviceType;

    private String remark;

    private LocalDateTime createTime;
}
