package com.mhp.booksystem.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.mhp.booksystem.dto.PostCreateDTO;
import com.mhp.booksystem.entity.Post;
import com.mhp.booksystem.vo.CursorPageVO;
import com.mhp.booksystem.vo.PostVO;

public interface PostService extends IService<Post> {

    void create(PostCreateDTO dto);

    CursorPageVO<PostVO> listByMerchant(Long merchantId, Long lastId, int size);

    void delete(Long postId);

    void toggleLike(Long postId);

    CursorPageVO<PostVO> listAll(Long lastId, int size, Long currentUserId);

    CursorPageVO<PostVO> followedFeed(Long lastId, int size, Long currentUserId);
}
