package com.mhp.booksystem.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;
import com.mhp.booksystem.dto.ReviewCreateDTO;
import com.mhp.booksystem.dto.ReviewReplyDTO;
import com.mhp.booksystem.entity.Review;
import com.mhp.booksystem.vo.ReviewVO;

public interface ReviewService extends IService<Review> {

    /** 客人提交评价 */
    void create(ReviewCreateDTO dto);

    /** 查商家评价列表（页码分页） */
    Page<ReviewVO> listByMerchant(Long merchantId, int page, int size);

    /** 商家回复评价 */
    void reply(Long reviewId, ReviewReplyDTO dto);
}
