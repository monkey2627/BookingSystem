package com.mhp.booksystem.service.impl;

import com.mhp.booksystem.security.SecurityUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.mhp.booksystem.common.ResultCode;
import com.mhp.booksystem.common.exception.BusinessException;
import com.mhp.booksystem.dto.ComplaintCreateDTO;
import com.mhp.booksystem.dto.feign.BookingDTO;
import com.mhp.booksystem.dto.feign.MerchantDTO;
import com.mhp.booksystem.dto.feign.UserDTO;
import com.mhp.booksystem.entity.Complaint;
import com.mhp.booksystem.feign.AccountFeignClient;
import com.mhp.booksystem.mq.NotifyMessage;
import com.mhp.booksystem.rpc.RpcBookingService;
import com.mhp.booksystem.rpc.RpcMerchantService;
import com.mhp.booksystem.mapper.ComplaintMapper;
import com.mhp.booksystem.service.ComplaintService;
import com.mhp.booksystem.vo.ComplaintVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.dubbo.config.annotation.DubboReference;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ComplaintServiceImpl extends ServiceImpl<ComplaintMapper, Complaint> implements ComplaintService {

    private final AccountFeignClient accountFeignClient;
    private final SimpMessagingTemplate messagingTemplate;

    @DubboReference(version = "1.0.0")
    private RpcMerchantService rpcMerchantService;

    @DubboReference(version = "1.0.0")
    private RpcBookingService rpcBookingService;

    @Override
    public void create(ComplaintCreateDTO dto) {
        Long complainantId = SecurityUtil.getCurrentUserId();

        BookingDTO booking = rpcBookingService.getBookingById(dto.getOrderId());
        if (booking == null) {
            throw new BusinessException(ResultCode.COMPLAINT_BOOKING_NOT_FOUND);
        }

        Long respondentId;
        if (booking.getUserId().equals(complainantId)) {
            MerchantDTO merchant = accountFeignClient.getMerchant(booking.getMerchantId()).getData();
            if (merchant == null) {
                throw new BusinessException(ResultCode.MERCHANT_NOT_FOUND);
            }
            respondentId = merchant.getUserId();
        } else {
            MerchantDTO merchant = rpcMerchantService.getMerchantByUserId(complainantId);
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

        // 实时推送 WebSocket 通知给被投诉方
        try {
            NotifyMessage notify = new NotifyMessage();
            notify.setMsgId(UUID.randomUUID().toString());
            notify.setType("COMPLAINT_RECEIVED");
            notify.setToUserId(respondentId);
            notify.setContent("您收到了一条新投诉，请前往预约记录查看。");
            messagingTemplate.convertAndSendToUser(respondentId.toString(), "/queue/notify", notify);
        } catch (Exception e) {
            log.warn("[Complaint] WebSocket 推送失败，不影响投诉创建", e);
        }
    }

    @Override
    public List<ComplaintVO> listReceived() {
        Long userId = SecurityUtil.getCurrentUserId();
        List<Complaint> complaints = lambdaQuery()
                .eq(Complaint::getRespondentId, userId)
                .orderByDesc(Complaint::getCreateTime)
                .list();

        // 批量拉取投诉人昵称
        List<Long> complainantIds = complaints.stream()
                .map(Complaint::getComplainantId).distinct().collect(Collectors.toList());
        var userMap = complainantIds.isEmpty()
                ? java.util.Collections.<Long, UserDTO>emptyMap()
                : accountFeignClient.batchGetUsers(complainantIds).getData().stream()
                        .collect(Collectors.toMap(UserDTO::getId, u -> u));

        return complaints.stream().map(c -> {
            ComplaintVO vo = new ComplaintVO();
            vo.setId(c.getId());
            vo.setOrderId(c.getOrderId());
            vo.setComplainantId(c.getComplainantId());
            UserDTO u = userMap.get(c.getComplainantId());
            vo.setComplainantNickname(u != null ? u.getNickname() : "用户" + c.getComplainantId());
            vo.setReason(c.getReason());
            vo.setEvidence(c.getEvidence());
            vo.setStatus(c.getStatus());
            vo.setAdminReply(c.getAdminReply());
            vo.setCreateTime(c.getCreateTime());
            return vo;
        }).collect(Collectors.toList());
    }
}
