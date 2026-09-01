package com.mhp.booksystem.feign;

import com.mhp.booksystem.common.Result;
import com.mhp.booksystem.dto.feign.ScheduleDTO;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

/**
 * 声明式 HTTP 客户端 — 对应 mhp-booking 服务的内部接口。
 * 调用方：mhp-social（抢档大厅聚合关注商家的抢档期）
 */
@FeignClient(name = "mhp-booking")
public interface BookingFeignClient {

    /** 查询给定商家列表中所有开放中或即将开放的抢档期（供抢档大厅使用） */
    @GetMapping("/internal/schedule/rush")
    Result<List<ScheduleDTO>> getRushSchedules(@RequestParam("merchantIds") List<Long> merchantIds);
}
