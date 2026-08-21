package com.mhp.booksystem.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mhp.booksystem.entity.Follow;
import com.mhp.booksystem.vo.MerchantVO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface FollowMapper extends BaseMapper<Follow> {

    /**
     * 查询用户关注的所有商家，按关注时间倒序。
     * 联表 merchant + user，结果直接映射为 MerchantVO。
     */
    List<MerchantVO> selectFollowedMerchants(@Param("userId") Long userId);
}
