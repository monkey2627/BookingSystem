package com.mhp.booksystem.service.impl;

import cn.dev33.satoken.stp.StpUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.mhp.booksystem.common.ResultCode;
import com.mhp.booksystem.common.exception.BusinessException;
import com.mhp.booksystem.dto.ComplaintCreateDTO;
import com.mhp.booksystem.entity.Booking;
import com.mhp.booksystem.entity.Complaint;
import com.mhp.booksystem.entity.Merchant;
import com.mhp.booksystem.mapper.BookingMapper;
import com.mhp.booksystem.mapper.ComplaintMapper;
import com.mhp.booksystem.mapper.MerchantMapper;
import com.mhp.booksystem.service.ComplaintService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ComplaintServiceImpl extends ServiceImpl<ComplaintMapper, Complaint> implements ComplaintService {

    private final BookingMapper bookingMapper;
    private final MerchantMapper merchantMapper;

    @Override
    public void create(ComplaintCreateDTO dto) {
        Long complainantId = StpUtil.getLoginIdAsLong();

        Booking booking = bookingMapper.selectById(dto.getOrderId());
        if (booking == null) {
            throw new BusinessException(ResultCode.COMPLAINT_BOOKING_NOT_FOUND);
        }

        // 确认投诉人是预约的客人或商家，并确定被投诉方
        Long respondentId;
        if (booking.getUserId().equals(complainantId)) {
            // 客人投诉商家：找到商家对应的 userId
            Merchant merchant = merchantMapper.selectById(booking.getMerchantId());
            if (merchant == null) {
                throw new BusinessException(ResultCode.MERCHANT_NOT_FOUND);
            }
            respondentId = merchant.getUserId();
        } else {
            // 商家投诉客人
            Merchant merchant = merchantMapper.selectOne(
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Merchant>()
                            .eq(Merchant::getUserId, complainantId));
            if (merchant == null || !booking.getMerchantId().equals(merchant.getId())) {
                throw new BusinessException(ResultCode.FORBIDDEN.getCode(), "您不是该预约的相关方");
            }
            respondentId = booking.getUserId();
        }

        Complaint complaint = new Complaint();
        complaint.setOrderId(dto.getOrderId());
        complaint.setComplainantId(complainantId);
        complaint.setRespondentId(respondentId);
        complaint.setReason(dto.getReason());
        complaint.setStatus(0);
        if (dto.getEvidence() != null && !dto.getEvidence().isEmpty()) {
            complaint.setEvidence(JSONUtil.toJsonStr(dto.getEvidence()));
        }
        save(complaint);
    }
}
