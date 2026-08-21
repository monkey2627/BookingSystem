package com.mhp.booksystem.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.mhp.booksystem.dto.BatchScheduleDTO;
import com.mhp.booksystem.dto.ScheduleCreateDTO;
import com.mhp.booksystem.entity.Schedule;
import com.mhp.booksystem.vo.RushRecordVO;
import com.mhp.booksystem.vo.RushResultVO;
import com.mhp.booksystem.vo.ScheduleVO;

import java.util.List;

public interface ScheduleService extends IService<Schedule> {

    /** 商家创建档期 */
    void create(ScheduleCreateDTO dto);

    /** 商家批量创建档期（按日期范围 + 星期过滤，跳过已存在的日期） */
    void batchCreate(BatchScheduleDTO dto);

    /** 商家删除档期（仅限 status=0 空闲状态） */
    void deleteSchedule(Long scheduleId);

    /** 查询某商家某月的所有档期，month 格式为 "2024-01" */
    List<ScheduleVO> listByMonth(Long merchantId, String month);

    /** 客人抢档期（基础版，Redis+Lua 优化在缓存阶段再加） */
    RushResultVO rush(Long scheduleId);

    /** 商家查看某档期的抢购排队列表，按 rankNo 升序 */
    List<RushRecordVO> getQueue(Long scheduleId);

    /** 商家更新某条排队记录的状态（0=排队中/1=已联系/2=已转化/3=已放弃） */
    void updateRushStatus(Long rushId, Integer status);
}
