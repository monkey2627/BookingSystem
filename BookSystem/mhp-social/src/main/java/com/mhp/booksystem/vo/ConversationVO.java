package com.mhp.booksystem.vo;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class ConversationVO {

    private Long userId;

    private String nickname;

    private String avatar;

    private String lastMessage;

    private Integer unreadCount;

    private LocalDateTime lastTime;
}
