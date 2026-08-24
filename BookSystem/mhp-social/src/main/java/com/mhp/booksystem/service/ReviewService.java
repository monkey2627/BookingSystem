package com.mhp.booksystem.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;
import com.mhp.booksystem.dto.ReviewCreateDTO;
import com.mhp.booksystem.dto.ReviewReplyDTO;
import com.mhp.booksystem.entity.Review;
import com.mhp.booksystem.vo.ReviewVO;

public interface ReviewService extends IService<Review> {

    void create(ReviewCreateDTO dto);

    Page<ReviewVO> listByMerchant(Long merchantId, int page, int size);

    void reply(Long reviewId, ReviewReplyDTO dto);
}
