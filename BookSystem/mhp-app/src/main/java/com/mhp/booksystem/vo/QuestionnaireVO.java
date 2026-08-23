package com.mhp.booksystem.vo;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class QuestionnaireVO {

    private Long id;

    private Long merchantId;

    private String title;

    /** questions 解析为 JSON 树，前端直接使用，无需再次解析 */
    private JsonNode questions;

    /** true = 预约时强制填写 */
    private Boolean isRequired;

    private LocalDateTime createTime;
}
