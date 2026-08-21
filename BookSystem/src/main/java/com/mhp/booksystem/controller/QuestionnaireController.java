package com.mhp.booksystem.controller;

import com.mhp.booksystem.common.Result;
import com.mhp.booksystem.dto.QuestionnaireCreateDTO;
import com.mhp.booksystem.service.QuestionnaireService;
import com.mhp.booksystem.vo.QuestionnaireVO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/questionnaire")
@RequiredArgsConstructor
public class QuestionnaireController {

    private final QuestionnaireService questionnaireService;

    @PostMapping
    public Result<?> create(@Valid @RequestBody QuestionnaireCreateDTO dto) {
        questionnaireService.create(dto);
        return Result.ok();
    }

    @GetMapping("/merchant/{merchantId}")
    public Result<List<QuestionnaireVO>> listByMerchant(@PathVariable Long merchantId) {
        return Result.ok(questionnaireService.listByMerchant(merchantId));
    }

    /** 商家查看自己的所有问卷模板 */
    @GetMapping("/my")
    public Result<List<QuestionnaireVO>> getMyTemplates() {
        return Result.ok(questionnaireService.getMyTemplates());
    }

    /** 商家删除自己的问卷模板 */
    @DeleteMapping("/{id}")
    public Result<?> deleteTemplate(@PathVariable Long id) {
        questionnaireService.deleteTemplate(id);
        return Result.ok();
    }
}
