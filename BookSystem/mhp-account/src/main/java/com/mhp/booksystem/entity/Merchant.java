package com.mhp.booksystem.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
public class Merchant {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    /** JSON 数组，如 [1,2]：1=妆 2=摄影 3=假发 4=影棚 5=后勤 6=后期 7=其他 */
    private String serviceTypes;

    private String city;

    private String intro;

    private String alipayLink;

    private String xianyuLink;

    private String xiaohongshuLink;

    private String weiboLink;

    private BigDecimal avgScore;

    private Integer reviewCount;

    private BigDecimal priceMin;

    private BigDecimal priceMax;

    private String bookingNotice;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
