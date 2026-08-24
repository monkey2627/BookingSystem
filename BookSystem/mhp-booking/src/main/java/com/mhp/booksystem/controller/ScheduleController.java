package com.mhp.booksystem.controller;

import com.alibaba.csp.sentinel.annotation.SentinelResource;
import com.alibaba.csp.sentinel.slots.block.BlockException;
import com.mhp.booksystem.common.Result;
import com.mhp.booksystem.dto.BatchScheduleDTO;
import com.mhp.booksystem.dto.ScheduleCreateDTO;
import com.mhp.booksystem.service.ScheduleService;
import com.mhp.booksystem.vo.RushRecordVO;
import com.mhp.booksystem.vo.RushResultVO;
import com.mhp.booksystem.vo.ScheduleVO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/schedule")
@RequiredArgsConstructor
public class ScheduleController {

    private final ScheduleService scheduleService;

    @PostMapping
    public Result<?> create(@Valid @RequestBody ScheduleCreateDTO dto) {
        scheduleService.create(dto);
        return Result.ok();
    }

    @GetMapping("/merchant/{merchantId}")
    public Result<List<ScheduleVO>> listByMonth(
            @PathVariable Long merchantId,
            @RequestParam String month) {
        return Result.ok(scheduleService.listByMonth(merchantId, month));
    }

    @PostMapping("/{id}/rush")
    @SentinelResource(value = "rushSchedule", blockHandler = "rushBlockHandler")
    public Result<RushResultVO> rush(@PathVariable Long id) {
        return Result.ok(scheduleService.rush(id));
    }

    public Result<RushResultVO> rushBlockHandler(@PathVariable Long id, BlockException ex) {
        return Result.fail(429, "当前抢购人数过多，请稍后再试");
    }

    @PostMapping("/batch")
    public Result<?> batchCreate(@Valid @RequestBody BatchScheduleDTO dto) {
        scheduleService.batchCreate(dto);
        return Result.ok();
    }

    @DeleteMapping("/{id}")
    public Result<?> deleteSchedule(@PathVariable Long id) {
        scheduleService.deleteSchedule(id);
        return Result.ok();
    }

    @GetMapping("/{id}/queue")
    public Result<List<RushRecordVO>> getQueue(@PathVariable Long id) {
        return Result.ok(scheduleService.getQueue(id));
    }

    @PutMapping("/rush/{rushId}/status")
    public Result<?> updateRushStatus(@PathVariable Long rushId,
                                      @RequestParam Integer status) {
        scheduleService.updateRushStatus(rushId, status);
        return Result.ok();
    }
}
