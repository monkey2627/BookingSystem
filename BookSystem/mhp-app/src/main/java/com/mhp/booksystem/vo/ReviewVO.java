package com.mhp.booksystem.vo;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
public class ReviewVO {

    private Long id;

    private Long userId;

    private String userNickname;

    private String userAvatar;

    private Integer score;

    private String content;

    private List<String> images;

    private String reply;

    private LocalDateTime createTime;
}
