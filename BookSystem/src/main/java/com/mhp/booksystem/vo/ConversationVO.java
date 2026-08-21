package com.mhp.booksystem.vo;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class ConversationVO {

    /** 对话方用户 ID */
    private Long userId;

    private String nickname;

    private String avatar;

    /** 最后一条消息内容 */
    private String lastMessage;

    /** 未读消息数（对方发给我的） */
    private Integer unreadCount;

    private LocalDateTime lastTime;
}
