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

    void create(ScheduleCreateDTO dto);

    void batchCreate(BatchScheduleDTO dto);

    void deleteSchedule(Long scheduleId);

    List<ScheduleVO> listByMonth(Long merchantId, String month);

    RushResultVO rush(Long scheduleId);

    List<RushRecordVO> getQueue(Long scheduleId);

    void updateRushStatus(Long rushId, Integer status);

    List<ScheduleVO> getRushSchedulesByMerchants(List<Long> merchantIds);
}
