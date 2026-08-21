package com.mhp.booksystem.vo;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
public class PostVO {

    private Long id;

    private Long merchantId;

    private String merchantNickname;

    private String merchantAvatar;

    private String content;

    private List<String> images;

    private Integer likeCount;

    private boolean liked;

    private LocalDateTime createTime;
}
