package com.mhp.booksystem.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class BookingCreateDTO {

    @NotNull(message = "档期不能为空")
    private Long scheduleId;

    private String remark;

    private String questionnaireAnswer;
}
