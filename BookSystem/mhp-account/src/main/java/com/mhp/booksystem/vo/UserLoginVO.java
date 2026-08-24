package com.mhp.booksystem.vo;

import lombok.Data;

@Data
public class UserLoginVO {

    private String token;

    private UserInfoVO userInfo;

    @Data
    public static class UserInfoVO {
        private Long id;
        private String nickname;
        private String avatar;
        private Boolean hasMerchantProfile;
    }
}
