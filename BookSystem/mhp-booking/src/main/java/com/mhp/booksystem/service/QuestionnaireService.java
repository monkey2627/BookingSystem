package com.mhp.booksystem.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.mhp.booksystem.dto.QuestionnaireCreateDTO;
import com.mhp.booksystem.entity.QuestionnaireTemplate;
import com.mhp.booksystem.vo.QuestionnaireVO;

import java.util.List;

public interface QuestionnaireService extends IService<QuestionnaireTemplate> {

    void create(QuestionnaireCreateDTO dto);

    List<QuestionnaireVO> listByMerchant(Long merchantId);

    List<QuestionnaireVO> getMyTemplates();

    void deleteTemplate(Long id);
}
