package com.mhp.booksystem.dto.feign;

import lombok.Data;

@Data
public class UserDTO {
    private Long id;
    private String nickname;
    private String avatar;
}
