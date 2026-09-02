package com.mhp.booksystem.controller;

import com.mhp.booksystem.security.SecurityUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.mhp.booksystem.common.Result;
import com.mhp.booksystem.dto.feign.MerchantDTO;
import com.mhp.booksystem.dto.feign.ScheduleDTO;
import com.mhp.booksystem.dto.feign.UserDTO;
import com.mhp.booksystem.entity.Follow;
import com.mhp.booksystem.feign.AccountFeignClient;
import com.mhp.booksystem.feign.BookingFeignClient;
import com.mhp.booksystem.mapper.FollowMapper;
import com.mhp.booksystem.vo.RushLobbyItemVO;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 抢档大厅 — 聚合当前用户关注的商家中，所有有效抢档期。
 *
 * 数据流：
 *   1. 查当前用户关注的 merchantId 列表（FollowMapper）
 *   2. Feign 调 mhp-booking 内部接口，批量拉取这些商家的抢档期（bookType=1, status=0）
 *   3. Feign 调 mhp-account 批量拉取商家和用户信息（拼昵称/头像）
 *   4. 已开放（rushOpenTime <= now 或 null）和未开放分别标记，前端分区展示
 */
@RestController
@RequestMapping("/api/rush")
@RequiredArgsConstructor
public class RushLobbyController {

    private final FollowMapper followMapper;
    private final BookingFeignClient bookingFeignClient;
    private final AccountFeignClient accountFeignClient;

    @GetMapping("/lobby")
    public Result<List<RushLobbyItemVO>> getLobby() {
        Long userId = SecurityUtil.getCurrentUserId();

        // 1. 当前用户关注的商家
        List<Long> merchantIds = followMapper.selectList(
                        new LambdaQueryWrapper<Follow>().eq(Follow::getUserId, userId))
                .stream().map(Follow::getMerchantId).collect(Collectors.toList());

        if (merchantIds.isEmpty()) {
            return Result.ok(Collections.emptyList());
        }

        // 2. 批量拉取抢档期
        List<ScheduleDTO> schedules = bookingFeignClient.getRushSchedules(merchantIds).getData();
        if (schedules == null || schedules.isEmpty()) {
            return Result.ok(Collections.emptyList());
        }

        // 3. 批量拉取商家信息
        List<MerchantDTO> merchants = accountFeignClient.batchGetMerchants(merchantIds).getData();
        Map<Long, MerchantDTO> merchantMap = merchants == null ? Collections.emptyMap()
                : merchants.stream().collect(Collectors.toMap(MerchantDTO::getId, m -> m));

        // 批量拉取用户信息（获取商家昵称/头像）
        List<Long> userIds = merchantMap.values().stream()
                .map(MerchantDTO::getUserId).distinct().collect(Collectors.toList());
        List<UserDTO> users = accountFeignClient.batchGetUsers(userIds).getData();
        Map<Long, UserDTO> userMap = users == null ? Collections.emptyMap()
                : users.stream().collect(Collectors.toMap(UserDTO::getId, u -> u));

        // 4. 组装 VO
        LocalDateTime now = LocalDateTime.now();
        List<RushLobbyItemVO> result = schedules.stream().map(s -> {
            RushLobbyItemVO vo = new RushLobbyItemVO();
            vo.setScheduleId(s.getId());
            vo.setMerchantId(s.getMerchantId());
            vo.setDate(s.getDate() != null ? s.getDate().toString() : null);
            vo.setTimeSlot(s.getTimeSlot());
            vo.setServiceType(s.getServiceType());
            vo.setRushOpenTime(s.getRushOpenTime());
            vo.setMaxQueueSize(s.getMaxQueueSize());
            vo.setCurrentQueueSize(s.getCurrentQueueSize());
            vo.setOpen(s.getRushOpenTime() == null || !s.getRushOpenTime().isAfter(now));

            MerchantDTO merchant = merchantMap.get(s.getMerchantId());
            if (merchant != null) {
                if (merchant.getServiceTypes() != null) {
                    vo.setServiceTypes(JSONUtil.toList(JSONUtil.parseArray(merchant.getServiceTypes()), Integer.class));
                }
                UserDTO user = userMap.get(merchant.getUserId());
                if (user != null) {
                    vo.setMerchantNickname(user.getNickname());
                    vo.setMerchantAvatar(user.getAvatar());
                }
            }
            return vo;
        }).collect(Collectors.toList());

        return Result.ok(result);
    }
}
