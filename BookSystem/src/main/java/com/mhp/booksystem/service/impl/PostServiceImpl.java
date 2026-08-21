package com.mhp.booksystem.service.impl;

import cn.dev33.satoken.stp.StpUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.mhp.booksystem.common.ResultCode;
import com.mhp.booksystem.common.exception.BusinessException;
import com.mhp.booksystem.dto.PostCreateDTO;
import com.mhp.booksystem.entity.Merchant;
import com.mhp.booksystem.entity.Post;
import com.mhp.booksystem.entity.User;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.mhp.booksystem.entity.Follow;
import com.mhp.booksystem.mapper.FollowMapper;
import com.mhp.booksystem.mapper.MerchantMapper;
import com.mhp.booksystem.mapper.PostMapper;
import com.mhp.booksystem.mapper.UserMapper;
import com.mhp.booksystem.service.PostService;
import com.mhp.booksystem.vo.CursorPageVO;
import com.mhp.booksystem.vo.PostVO;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class PostServiceImpl extends ServiceImpl<PostMapper, Post> implements PostService {

    private final MerchantMapper merchantMapper;
    private final UserMapper userMapper;
    private final FollowMapper followMapper;
    private final StringRedisTemplate stringRedisTemplate;

    @Override
    public void create(PostCreateDTO dto) {
        Long userId = StpUtil.getLoginIdAsLong();
        Merchant merchant = merchantMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Merchant>()
                        .eq(Merchant::getUserId, userId));
        if (merchant == null) {
            throw new BusinessException(ResultCode.USER_NOT_MERCHANT);
        }

        Post post = new Post();
        post.setMerchantId(merchant.getId());
        post.setContent(dto.getContent());
        post.setLikeCount(0);
        if (dto.getImages() != null && !dto.getImages().isEmpty()) {
            post.setImages(JSONUtil.toJsonStr(dto.getImages()));
        }
        save(post);
    }

    @Override
    public CursorPageVO<PostVO> listByMerchant(Long merchantId, Long lastId, int size) {
        List<Post> posts = lambdaQuery()
                .eq(Post::getMerchantId, merchantId)
                .lt(lastId != null && lastId > 0, Post::getId, lastId)
                .orderByDesc(Post::getId)
                .last("LIMIT " + (size + 1))
                .list();

        boolean hasMore = posts.size() > size;
        if (hasMore) posts = posts.subList(0, size);
        if (posts.isEmpty()) return CursorPageVO.of(List.of(), false, null);

        // 查商家信息（同一个商家，只查一次）
        Merchant merchant = merchantMapper.selectById(merchantId);
        User merchantUser = merchant != null ? userMapper.selectById(merchant.getUserId()) : null;
        String nickname = merchantUser != null ? merchantUser.getNickname() : "";
        String avatar = merchantUser != null ? merchantUser.getAvatar() : "";

        List<PostVO> voList = posts.stream()
                .map(p -> toVO(p, nickname, avatar))
                .collect(Collectors.toList());

        Long nextCursor = posts.get(posts.size() - 1).getId();
        return CursorPageVO.of(voList, hasMore, nextCursor);
    }

    @Override
    public void delete(Long postId) {
        Long userId = StpUtil.getLoginIdAsLong();
        Post post = getById(postId);
        if (post == null) {
            throw new BusinessException(ResultCode.POST_NOT_FOUND);
        }
        Merchant merchant = merchantMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Merchant>()
                        .eq(Merchant::getUserId, userId));
        if (merchant == null || !post.getMerchantId().equals(merchant.getId())) {
            throw new BusinessException(ResultCode.FORBIDDEN);
        }
        removeById(postId);
    }

    @Override
    public void toggleLike(Long postId) {
        Long userId = StpUtil.getLoginIdAsLong();
        String key = "post:likes:" + postId + ":" + userId;
        // setIfAbsent = SET NX：返回 true 表示首次设值（首次点赞）
        Boolean firstLike = stringRedisTemplate.opsForValue()
                .setIfAbsent(key, "1", 24 * 365, TimeUnit.HOURS);
        if (Boolean.TRUE.equals(firstLike)) {
            // 首次点赞：like_count + 1
            lambdaUpdate()
                    .eq(Post::getId, postId)
                    .setSql("like_count = like_count + 1")
                    .update();
        } else {
            // 已点赞：取消点赞，删除 key，like_count - 1（不低于 0）
            stringRedisTemplate.delete(key);
            lambdaUpdate()
                    .eq(Post::getId, postId)
                    .setSql("like_count = GREATEST(like_count - 1, 0)")
                    .update();
        }
    }

    @Override
    public CursorPageVO<PostVO> listAll(Long lastId, int size, Long currentUserId) {
        List<Post> posts = lambdaQuery()
                .lt(lastId != null && lastId > 0, Post::getId, lastId)
                .orderByDesc(Post::getId)
                .last("LIMIT " + (size + 1))
                .list();

        boolean hasMore = posts.size() > size;
        if (hasMore) posts = posts.subList(0, size);
        if (posts.isEmpty()) return CursorPageVO.of(List.of(), false, null);

        // 收集所有 merchantId，批量查商家与用户
        List<Long> merchantIds = posts.stream()
                .map(Post::getMerchantId)
                .distinct()
                .collect(Collectors.toList());
        Map<Long, Merchant> merchantMap = merchantMapper.selectBatchIds(merchantIds).stream()
                .collect(Collectors.toMap(Merchant::getId, m -> m));
        List<Long> userIds = merchantMap.values().stream()
                .map(Merchant::getUserId)
                .distinct()
                .collect(Collectors.toList());
        Map<Long, User> userMap = userIds.isEmpty()
                ? Collections.emptyMap()
                : userMapper.selectBatchIds(userIds).stream()
                        .collect(Collectors.toMap(User::getId, u -> u));

        List<PostVO> voList = new ArrayList<>();
        for (Post p : posts) {
            Merchant merchant = merchantMap.get(p.getMerchantId());
            User merchantUser = merchant != null ? userMap.get(merchant.getUserId()) : null;
            String nickname = merchantUser != null ? merchantUser.getNickname() : "";
            String avatar = merchantUser != null ? merchantUser.getAvatar() : "";
            PostVO vo = toVO(p, nickname, avatar);
            // 如果当前用户已登录，检查 Redis 中是否存在点赞 key
            if (currentUserId != null) {
                String key = "post:likes:" + p.getId() + ":" + currentUserId;
                vo.setLiked(Boolean.TRUE.equals(stringRedisTemplate.hasKey(key)));
            }
            voList.add(vo);
        }

        Long nextCursor = posts.get(posts.size() - 1).getId();
        return CursorPageVO.of(voList, hasMore, nextCursor);
    }

    @Override
    public CursorPageVO<PostVO> followedFeed(Long lastId, int size, Long currentUserId) {
        if (currentUserId == null) return CursorPageVO.of(List.of(), false, null);

        List<Long> merchantIds = followMapper
                .selectList(new LambdaQueryWrapper<Follow>().eq(Follow::getUserId, currentUserId))
                .stream().map(Follow::getMerchantId).collect(Collectors.toList());

        if (merchantIds.isEmpty()) return CursorPageVO.of(List.of(), false, null);

        List<Post> posts = lambdaQuery()
                .in(Post::getMerchantId, merchantIds)
                .lt(lastId != null && lastId > 0, Post::getId, lastId)
                .orderByDesc(Post::getId)
                .last("LIMIT " + (size + 1))
                .list();

        boolean hasMore = posts.size() > size;
        if (hasMore) posts = posts.subList(0, size);
        if (posts.isEmpty()) return CursorPageVO.of(List.of(), false, null);

        Map<Long, Merchant> merchantMap = merchantMapper.selectBatchIds(merchantIds).stream()
                .collect(Collectors.toMap(Merchant::getId, m -> m));
        List<Long> userIds = merchantMap.values().stream()
                .map(Merchant::getUserId).distinct().collect(Collectors.toList());
        Map<Long, User> userMap = userMapper.selectBatchIds(userIds).stream()
                .collect(Collectors.toMap(User::getId, u -> u));

        List<PostVO> voList = posts.stream().map(p -> {
            Merchant merchant = merchantMap.get(p.getMerchantId());
            User merchantUser = merchant != null ? userMap.get(merchant.getUserId()) : null;
            PostVO vo = toVO(p,
                    merchantUser != null ? merchantUser.getNickname() : "",
                    merchantUser != null ? merchantUser.getAvatar() : "");
            String key = "post:likes:" + p.getId() + ":" + currentUserId;
            vo.setLiked(Boolean.TRUE.equals(stringRedisTemplate.hasKey(key)));
            return vo;
        }).collect(Collectors.toList());

        return CursorPageVO.of(voList, hasMore, posts.get(posts.size() - 1).getId());
    }

    private PostVO toVO(Post p, String nickname, String avatar) {
        PostVO vo = new PostVO();
        vo.setId(p.getId());
        vo.setMerchantId(p.getMerchantId());
        vo.setMerchantNickname(nickname);
        vo.setMerchantAvatar(avatar);
        vo.setContent(p.getContent());
        vo.setLikeCount(p.getLikeCount());
        vo.setCreateTime(p.getCreateTime());
        if (StringUtils.hasText(p.getImages())) {
            vo.setImages(JSONUtil.toList(JSONUtil.parseArray(p.getImages()), String.class));
        } else {
            vo.setImages(Collections.emptyList());
        }
        return vo;
    }
}
