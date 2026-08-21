package com.mhp.booksystem.dto;

import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

@Data
public class MerchantUpdateDTO {

    /** 服务类型：1=妆娘 2=摄影 3=毛娘 4=后勤 5=后期，可多选 */
    private List<Integer> serviceTypes;

    @Size(max = 50, message = "城市名称过长")
    private String city;

    @Size(max = 500, message = "简介不能超过500字")
    private String intro;

    private String alipayLink;

    private String xianyuLink;

    private String xiaohongshuLink;

    private String weiboLink;
}
