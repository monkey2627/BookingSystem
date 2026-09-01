package com.mhp.booksystem.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * 商家资料更新 DTO。所有字段均为可选（支持部分更新），null 字段在 Service 层不会被覆盖。
 * Bean Validation 注解的完整说明见 UserRegisterDTO 类注释。
 */
@Data
public class MerchantUpdateDTO {

    // @Size 用于集合时校验元素个数（min/max），用于字符串时校验字符数
    // 元素值范围（1~5 对应各服务类型）在 Service 层校验，标准注解无法校验集合内元素的值
    @Size(max = 5, message = "服务类型最多选择5项")
    private List<Integer> serviceTypes;

    // @Size 用于字符串时：max = 最大字符数（按 Unicode 码点计，不是字节数）
    @Size(max = 50, message = "城市名称过长")
    private String city;

    @Size(max = 500, message = "简介不能超过500字")
    private String intro;

    // 社交链接用 @Size 而非 @URL，因为用户可能填短链、二维码链接等非标准格式
    @Size(max = 200, message = "支付宝链接过长")
    private String alipayLink;

    @Size(max = 200, message = "闲鱼链接过长")
    private String xianyuLink;

    @Size(max = 200, message = "小红书链接过长")
    private String xiaohongshuLink;

    @Size(max = 200, message = "微博链接过长")
    private String weiboLink;

    // @DecimalMin：BigDecimal 的最小值校验，inclusive=true（默认）表示允许等于边界值
    // null 时跳过（不填价格表示不更新），由 Service 层的 if (dto.getPriceMin() != null) 控制
    @DecimalMin(value = "0", message = "价格不能为负数")
    private BigDecimal priceMin;

    @DecimalMin(value = "0", message = "价格不能为负数")
    private BigDecimal priceMax;

    @Size(max = 500, message = "预约须知不能超过500字")
    private String bookingNotice;

    /** 档期可见度：0=全公开，1=仅忙闲，2=完全私密；null 表示不修改 */
    private Integer scheduleVisibility;
}
