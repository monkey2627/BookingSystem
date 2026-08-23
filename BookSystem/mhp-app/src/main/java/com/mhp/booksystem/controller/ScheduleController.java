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

/**
 * Sentinel 限流是什么意思？
 *   Sentinel 是阿里巴巴开源的"流量防护组件"。"限流"指的是限制某个接口每秒最多处理多少请求（QPS）。
 *   超过阈值的请求直接被 Sentinel 拦截，不进入业务逻辑，立刻返回降级响应。
 *
 * 为什么抢档期接口需要限流？
 *   抢档期开放的瞬间，大量用户同时点击"抢"，流量会在几秒内暴增（类似秒杀）。
 *   虽然核心抢占逻辑用 Redis Lua 脚本保证了原子性，但每次抢到后还要写 DB（rush_record 表），
 *   如果同时有 1000 个请求打进来，DB 连接池会被打满，整个服务都会变慢甚至崩溃。
 *   Sentinel 把多余的请求在应用层拦下来，让 DB 只承受它能处理的量。
 *
 * 阈值怎么定？
 *   QPS 50~100 是经验值，具体要根据压测结果调整。
 *   配置方式：启动应用后，打开 Sentinel 控制台（需单独部署）→ 流量控制 → 新增规则：
 *     资源名：rushSchedule，阈值类型：QPS，单机阈值：50（或 100）
 *   也可以用代码硬编码规则，但控制台配置更灵活，不需要改代码重启。
 */
@RestController
@RequestMapping("/api/schedule")
@RequiredArgsConstructor
public class ScheduleController {

    private final ScheduleService scheduleService;

    /** 商家创建档期 */
    @PostMapping
    public Result<?> create(@Valid @RequestBody ScheduleCreateDTO dto) {
        scheduleService.create(dto);
        return Result.ok();
    }

    /**
     * 查询某商家某月档期
     * 示例：GET /api/schedule/merchant/1?month=2024-01
     */
    @GetMapping("/merchant/{merchantId}")
    public Result<List<ScheduleVO>> listByMonth(
            @PathVariable Long merchantId,
            @RequestParam String month) {
        return Result.ok(scheduleService.listByMonth(merchantId, month));
    }

    /**
     * 客人抢档期。
     * @SentinelResource：标记这是一个受 Sentinel 保护的资源，资源名 "rushSchedule"。
     * blockHandler：触发限流时调用的降级方法，必须在同一个类里，参数和返回值与原方法一致，
     *               最后多一个 BlockException 参数。
     */
    @PostMapping("/{id}/rush")
    @SentinelResource(value = "rushSchedule", blockHandler = "rushBlockHandler")
    public Result<RushResultVO> rush(@PathVariable Long id) {
        return Result.ok(scheduleService.rush(id));
    }

    /**
     * Sentinel 限流触发时的降级响应。
     * 返回 429（Too Many Requests），告诉客户端"系统忙，稍后再试"，
     * 不让用户以为自己的操作失败了，也不暴露内部错误信息。
     */
    public Result<RushResultVO> rushBlockHandler(@PathVariable Long id, BlockException ex) {
        return Result.fail(429, "当前抢购人数过多，请稍后再试");
    }

    /** 商家批量创建档期（按日期范围 + 星期几，跳过已有档期） */
    @PostMapping("/batch")
    public Result<?> batchCreate(@Valid @RequestBody BatchScheduleDTO dto) {
        scheduleService.batchCreate(dto);
        return Result.ok();
    }

    /** 商家删除档期（仅 status=0 空闲状态可删） */
    @DeleteMapping("/{id}")
    public Result<?> deleteSchedule(@PathVariable Long id) {
        scheduleService.deleteSchedule(id);
        return Result.ok();
    }

    /** 商家查看某档期的抢购排队列表 */
    @GetMapping("/{id}/queue")
    public Result<List<RushRecordVO>> getQueue(@PathVariable Long id) {
        return Result.ok(scheduleService.getQueue(id));
    }

    /** 商家更新排队记录状态（0=排队中 1=已联系 2=已转化 3=已放弃） */
    @PutMapping("/rush/{rushId}/status")
    public Result<?> updateRushStatus(@PathVariable Long rushId,
                                      @RequestParam Integer status) {
        scheduleService.updateRushStatus(rushId, status);
        return Result.ok();
    }
}
