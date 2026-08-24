package com.mhp.booksystem.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mhp.booksystem.entity.Booking;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface BookingMapper extends BaseMapper<Booking> {
}
