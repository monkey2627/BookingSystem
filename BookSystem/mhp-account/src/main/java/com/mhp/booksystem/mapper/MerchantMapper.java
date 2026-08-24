package com.mhp.booksystem.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.mhp.booksystem.entity.Merchant;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface MerchantMapper extends BaseMapper<Merchant> {

    IPage<Merchant> searchPage(IPage<Merchant> page,
                               @Param("city") String city,
                               @Param("serviceType") Integer serviceType,
                               @Param("keyword") String keyword);
}
