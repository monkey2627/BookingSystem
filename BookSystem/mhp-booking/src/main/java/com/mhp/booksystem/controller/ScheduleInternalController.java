package com.mhp.booksystem.controller;

import com.mhp.booksystem.common.Result;
import com.mhp.booksystem.dto.feign.ScheduleDTO;
import com.mhp.booksystem.service.ScheduleService;
import com.mhp.booksystem.vo.ScheduleVO;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 内部接口 — 不经过 Gateway 对外暴露，仅供其他微服务通过 Feign 调用。
 * SaTokenConfig 已将 /internal/** 加入白名单，无需 token。
 */
@RestController
@RequestMapping("/internal/schedule")
@RequiredArgsConstructor
public class ScheduleInternalController {

    private final ScheduleService scheduleService;

    /**
     * 查询给定商家列表中所有有效抢档期（bookType=1, status=0, 日期在今天到两个月后）。
     * 供 mhp-social 抢档大厅使用。
     */
    @GetMapping("/rush")
    public Result<List<ScheduleDTO>> getRushSchedules(@RequestParam List<Long> merchantIds) {
        List<ScheduleVO> vos = scheduleService.getRushSchedulesByMerchants(merchantIds);
        List<ScheduleDTO> dtos = vos.stream().map(vo -> {
            ScheduleDTO dto = new ScheduleDTO();
            dto.setId(vo.getId());
            dto.setMerchantId(vo.getMerchantId());
            dto.setDate(vo.getDate());
            dto.setTimeSlot(vo.getTimeSlot());
            dto.setStatus(vo.getStatus());
            dto.setBookType(vo.getBookType());
            dto.setServiceType(vo.getServiceType());
            dto.setRushOpenTime(vo.getRushOpenTime());
            dto.setMaxQueueSize(vo.getMaxQueueSize());
            dto.setCurrentQueueSize(vo.getCurrentQueueSize());
            return dto;
        }).collect(Collectors.toList());
        return Result.ok(dtos);
    }
}
