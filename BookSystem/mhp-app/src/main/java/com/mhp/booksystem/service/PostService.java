package com.mhp.booksystem.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.mhp.booksystem.dto.PostCreateDTO;
import com.mhp.booksystem.entity.Post;
import com.mhp.booksystem.vo.CursorPageVO;
import com.mhp.booksystem.vo.PostVO;

public interface PostService extends IService<Post> {

    /** 商家发布动态 */
    void create(PostCreateDTO dto);

    /** 查某商家动态列表（游标分页） */
    CursorPageVO<PostVO> listByMerchant(Long merchantId, Long lastId, int size);

    /** 商家删除自己的动态 */
    void delete(Long postId);

    /** 点赞 / 取消点赞 */
    void toggleLike(Long postId);

    /** 全站动态流（游标分页） */
    CursorPageVO<PostVO> listAll(Long lastId, int size, Long currentUserId);

    /** 已关注商家动态流（游标分页） */
    CursorPageVO<PostVO> followedFeed(Long lastId, int size, Long currentUserId);
}
