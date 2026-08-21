package com.mhp.booksystem.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.mhp.booksystem.entity.Merchant;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface MerchantMapper extends BaseMapper<Merchant> {

    /**
     * 动态条件搜索商家，支持 JSON_CONTAINS 过滤服务类型。
     * 第一个参数是 IPage，MyBatis-Plus 自动识别并注入分页 SQL。
     * 复杂条件写在 XML 里，保持 Service 层不出现原生 SQL。
     */
    IPage<Merchant> searchPage(IPage<Merchant> page,
                               @Param("city") String city,
                               @Param("serviceType") Integer serviceType,
                               @Param("keyword") String keyword);
}
