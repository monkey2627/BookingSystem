package com.mhp.booksystem.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class TokenRefreshDTO {

    @NotBlank(message = "refreshToken不能为空")
    private String refreshToken;
}
