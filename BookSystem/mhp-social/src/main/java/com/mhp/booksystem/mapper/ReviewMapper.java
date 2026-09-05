package com.mhp.booksystem.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mhp.booksystem.entity.Review;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.Map;

@Mapper
public interface ReviewMapper extends BaseMapper<Review> {

    @Select("SELECT ROUND(AVG(score), 1) AS avg_score, COUNT(*) AS review_count " +
            "FROM review WHERE merchant_id = #{merchantId}")
    Map<String, Object> selectScoreStats(@Param("merchantId") Long merchantId);
}
