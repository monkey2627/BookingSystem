package com.mhp.booksystem.service.impl;

import cn.dev33.satoken.stp.StpUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.mhp.booksystem.common.ResultCode;
import com.mhp.booksystem.common.exception.BusinessException;
import com.mhp.booksystem.dto.PostCreateDTO;
import com.mhp.booksystem.dto.feign.MerchantDTO;
import com.mhp.booksystem.dto.feign.UserDTO;
import com.mhp.booksystem.entity.Follow;
import com.mhp.booksystem.entity.Post;
import com.mhp.booksystem.feign.AccountFeignClient;
import com.mhp.booksystem.mapper.FollowMapper;
import com.mhp.booksystem.mapper.PostMapper;
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

    private final AccountFeignClient accountFeignClient;
    private final FollowMapper followMapper;
    private final StringRedisTemplate stringRedisTemplate;

    @Override
    public void create(PostCreateDTO dto) {
        Long userId = StpUtil.getLoginIdAsLong();
        MerchantDTO merchant = accountFeignClient.getMerchantByUserId(userId).getData();
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

        MerchantDTO merchant = accountFeignClient.getMerchant(merchantId).getData();
        String nickname = "";
        String avatar = "";
        if (merchant != null) {
            UserDTO merchantUser = accountFeignClient.getUser(merchant.getUserId()).getData();
            if (merchantUser != null) {
                nickname = merchantUser.getNickname();
                avatar = merchantUser.getAvatar();
            }
        }

        final String finalNickname = nickname;
        final String finalAvatar = avatar;
        List<PostVO> voList = posts.stream()
                .map(p -> toVO(p, finalNickname, finalAvatar))
                .collect(Collectors.toList());

        return CursorPageVO.of(voList, hasMore, posts.get(posts.size() - 1).getId());
    }

    @Override
    public void delete(Long postId) {
        Long userId = StpUtil.getLoginIdAsLong();
        Post post = getById(postId);
        if (post == null) {
            throw new BusinessException(ResultCode.POST_NOT_FOUND);
        }
        MerchantDTO merchant = accountFeignClient.getMerchantByUserId(userId).getData();
        if (merchant == null || !post.getMerchantId().equals(merchant.getId())) {
            throw new BusinessException(ResultCode.FORBIDDEN);
        }
        removeById(postId);
    }

    @Override
    public void toggleLike(Long postId) {
        Long userId = StpUtil.getLoginIdAsLong();
        String key = "post:likes:" + postId + ":" + userId;
        Boolean firstLike = stringRedisTemplate.opsForValue()
                .setIfAbsent(key, "1", 24 * 365, TimeUnit.HOURS);
        if (Boolean.TRUE.equals(firstLike)) {
            lambdaUpdate()
                    .eq(Post::getId, postId)
                    .setSql("like_count = like_count + 1")
                    .update();
        } else {
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

        List<Long> merchantIds = posts.stream().map(Post::getMerchantId).distinct().collect(Collectors.toList());
        List<MerchantDTO> merchants = accountFeignClient.batchGetMerchants(merchantIds).getData();
        Map<Long, MerchantDTO> merchantMap = merchants == null ? Collections.emptyMap()
                : merchants.stream().collect(Collectors.toMap(MerchantDTO::getId, m -> m));

        List<Long> userIds = merchantMap.values().stream().map(MerchantDTO::getUserId).distinct().collect(Collectors.toList());
        List<UserDTO> users = userIds.isEmpty() ? Collections.emptyList()
                : accountFeignClient.batchGetUsers(userIds).getData();
        Map<Long, UserDTO> userMap = users == null ? Collections.emptyMap()
                : users.stream().collect(Collectors.toMap(UserDTO::getId, u -> u));

        List<PostVO> voList = new ArrayList<>();
        for (Post p : posts) {
            MerchantDTO merchant = merchantMap.get(p.getMerchantId());
            UserDTO merchantUser = merchant != null ? userMap.get(merchant.getUserId()) : null;
            String nickname = merchantUser != null ? merchantUser.getNickname() : "";
            String avatar = merchantUser != null ? merchantUser.getAvatar() : "";
            PostVO vo = toVO(p, nickname, avatar);
            if (currentUserId != null) {
                String key = "post:likes:" + p.getId() + ":" + currentUserId;
                vo.setLiked(Boolean.TRUE.equals(stringRedisTemplate.hasKey(key)));
            }
            voList.add(vo);
        }

        return CursorPageVO.of(voList, hasMore, posts.get(posts.size() - 1).getId());
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

        List<MerchantDTO> merchants = accountFeignClient.batchGetMerchants(merchantIds).getData();
        Map<Long, MerchantDTO> merchantMap = merchants == null ? Collections.emptyMap()
                : merchants.stream().collect(Collectors.toMap(MerchantDTO::getId, m -> m));

        List<Long> userIds = merchantMap.values().stream().map(MerchantDTO::getUserId).distinct().collect(Collectors.toList());
        List<UserDTO> users = userIds.isEmpty() ? Collections.emptyList()
                : accountFeignClient.batchGetUsers(userIds).getData();
        Map<Long, UserDTO> userMap = users == null ? Collections.emptyMap()
                : users.stream().collect(Collectors.toMap(UserDTO::getId, u -> u));

        List<PostVO> voList = posts.stream().map(p -> {
            MerchantDTO merchant = merchantMap.get(p.getMerchantId());
            UserDTO merchantUser = merchant != null ? userMap.get(merchant.getUserId()) : null;
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
