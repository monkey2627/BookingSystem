package com.mhp.booksystem.vo;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
public class RushLobbyItemVO {

    private Long scheduleId;
    private Long merchantId;
    private String merchantNickname;
    private String merchantAvatar;
    private List<Integer> serviceTypes;
    private String date;
    private String timeSlot;
    private Integer serviceType;
    private LocalDateTime rushOpenTime;
    private Integer maxQueueSize;
    private Integer currentQueueSize;
    /** true=已开放（rushOpenTime 已过或为 null），false=尚未开放 */
    private boolean open;
}
