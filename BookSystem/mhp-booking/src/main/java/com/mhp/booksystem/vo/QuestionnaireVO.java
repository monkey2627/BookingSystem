package com.mhp.booksystem.vo;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class QuestionnaireVO {

    private Long id;

    private Long merchantId;

    private String title;

    private JsonNode questions;

    private Boolean isRequired;

    private LocalDateTime createTime;
}
