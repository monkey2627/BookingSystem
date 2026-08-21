package com.mhp.booksystem.service.impl;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mhp.booksystem.common.ResultCode;
import com.mhp.booksystem.common.exception.BusinessException;
import com.mhp.booksystem.dto.QuestionnaireCreateDTO;
import com.mhp.booksystem.entity.Merchant;
import com.mhp.booksystem.entity.QuestionnaireTemplate;
import com.mhp.booksystem.mapper.MerchantMapper;
import com.mhp.booksystem.mapper.QuestionnaireTemplateMapper;
import com.mhp.booksystem.service.QuestionnaireService;
import com.mhp.booksystem.vo.QuestionnaireVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class QuestionnaireServiceImpl extends ServiceImpl<QuestionnaireTemplateMapper, QuestionnaireTemplate>
        implements QuestionnaireService {

    private final MerchantMapper merchantMapper;
    private final ObjectMapper objectMapper;

    @Override
    public void create(QuestionnaireCreateDTO dto) {
        Long userId = StpUtil.getLoginIdAsLong();
        Merchant merchant = merchantMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Merchant>()
                        .eq(Merchant::getUserId, userId));
        if (merchant == null) {
            throw new BusinessException(ResultCode.USER_NOT_MERCHANT);
        }

        // 校验 questions 是合法 JSON
        try {
            objectMapper.readTree(dto.getQuestions());
        } catch (Exception e) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "questions 格式不合法，必须是 JSON 数组");
        }

        QuestionnaireTemplate template = new QuestionnaireTemplate();
        template.setMerchantId(merchant.getId());
        template.setTitle(dto.getTitle());
        template.setQuestions(dto.getQuestions());
        template.setIsRequired(dto.getIsRequired());
        save(template);
    }

    @Override
    public List<QuestionnaireVO> listByMerchant(Long merchantId) {
        List<QuestionnaireTemplate> templates = lambdaQuery()
                .eq(QuestionnaireTemplate::getMerchantId, merchantId)
                .orderByDesc(QuestionnaireTemplate::getCreateTime)
                .list();

        return templates.stream().map(this::toVO).collect(Collectors.toList());
    }

    @Override
    public List<QuestionnaireVO> getMyTemplates() {
        Long userId = StpUtil.getLoginIdAsLong();
        Merchant merchant = merchantMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Merchant>()
                        .eq(Merchant::getUserId, userId));
        if (merchant == null) return Collections.emptyList();
        return listByMerchant(merchant.getId());
    }

    @Override
    public void deleteTemplate(Long id) {
        Long userId = StpUtil.getLoginIdAsLong();
        QuestionnaireTemplate t = getById(id);
        if (t == null) throw new BusinessException(ResultCode.QUESTIONNAIRE_NOT_FOUND);
        Merchant merchant = merchantMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Merchant>()
                        .eq(Merchant::getUserId, userId));
        if (merchant == null || !t.getMerchantId().equals(merchant.getId())) {
            throw new BusinessException(ResultCode.FORBIDDEN);
        }
        removeById(id);
    }

    private QuestionnaireVO toVO(QuestionnaireTemplate t) {
        QuestionnaireVO vo = new QuestionnaireVO();
        vo.setId(t.getId());
        vo.setMerchantId(t.getMerchantId());
        vo.setTitle(t.getTitle());
        vo.setIsRequired(t.getIsRequired() != null && t.getIsRequired() == 1);
        vo.setCreateTime(t.getCreateTime());
        try {
            // 把 questions 字符串解析为 JsonNode，序列化时自动展开为 JSON，前端无需二次解析
            vo.setQuestions(objectMapper.readTree(t.getQuestions()));
        } catch (Exception e) {
            vo.setQuestions(objectMapper.createArrayNode());
        }
        return vo;
    }
}
