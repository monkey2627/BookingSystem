package com.mhp.booksystem.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.mhp.booksystem.dto.QuestionnaireCreateDTO;
import com.mhp.booksystem.entity.QuestionnaireTemplate;
import com.mhp.booksystem.vo.QuestionnaireVO;

import java.util.List;

public interface QuestionnaireService extends IService<QuestionnaireTemplate> {

    /** 商家创建问卷模板 */
    void create(QuestionnaireCreateDTO dto);

    /** 查商家的所有问卷模板 */
    List<QuestionnaireVO> listByMerchant(Long merchantId);

    /** 查当前登录商家自己的所有问卷模板 */
    List<QuestionnaireVO> getMyTemplates();

    /** 删除问卷模板（只能删自己的） */
    void deleteTemplate(Long id);
}
