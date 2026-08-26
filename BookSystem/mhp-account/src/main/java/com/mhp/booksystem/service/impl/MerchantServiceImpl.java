package com.mhp.booksystem.service.impl;

import cn.dev33.satoken.stp.StpUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.mhp.booksystem.canal.CanalSyncService;
import com.mhp.booksystem.common.ResultCode;
import com.mhp.booksystem.common.exception.BusinessException;
import com.mhp.booksystem.document.MerchantDoc;
import com.mhp.booksystem.dto.MerchantUpdateDTO;
import com.mhp.booksystem.entity.Merchant;
import com.mhp.booksystem.entity.User;
import com.mhp.booksystem.mapper.MerchantMapper;
import com.mhp.booksystem.mapper.UserMapper;
import com.mhp.booksystem.repository.MerchantEsRepository;
import com.mhp.booksystem.service.MerchantService;
import com.mhp.booksystem.vo.MerchantVO;
import lombok.RequiredArgsConstructor;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.elasticsearch.client.elc.NativeQuery;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.SearchHits;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import co.elastic.clients.elasticsearch._types.query_dsl.FieldValueFactorModifier;
import co.elastic.clients.elasticsearch._types.query_dsl.FunctionBoostMode;
import co.elastic.clients.elasticsearch._types.query_dsl.FunctionScore;
import co.elastic.clients.elasticsearch._types.query_dsl.FunctionScoreMode;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * MerchantServiceImpl 类声明解析：
 *
 * 1. extends ServiceImpl<MerchantMapper, Merchant>
 *    ServiceImpl 是 MyBatis-Plus 提供的通用 Service 实现类，内置了 getById / save /
 *    saveOrUpdate / updateById / removeById / lambdaQuery 等几十个常用方法，继承它就白得这些能力。
 *    泛型参数含义：
 *      MerchantMapper — 告诉父类"帮我注入的 Mapper 是 MerchantMapper"，
 *                        父类里的 protected M baseMapper 字段就会被 Spring 注入为 MerchantMapper 实例，
 *                        本类中直接用 baseMapper.xxx() 即可调用 MerchantMapper 的自定义方法。
 *      Merchant       — 告诉父类"操作的实体类是 Merchant"，getById() 等方法的返回值因此是 Merchant 而非 Object。
 *
 * 2. implements MerchantService
 *    面向接口编程：Controller 注入的类型是接口（MerchantService），不依赖具体实现类。
 *    Spring 运行时从 IoC 容器取出本类的 Bean 注入进去。好处：将来换实现，Controller 一行不用改。
 *
 * 3. @RequiredArgsConstructor（Lombok）
 *    编译时自动生成一个包含所有 final 字段的构造方法，Spring 检测到只有一个构造方法时
 *    自动用构造注入（Spring 4.3+ 无需再写 @Autowired）。
 *    相比字段注入 @Autowired 的优势：
 *      - 依赖声明为 final，注入后不可变，更安全
 *      - 单测时可以直接 new 传参，不依赖 Spring 容器
 *      - 循环依赖在启动时立即报错，不会藏到运行时
 *
 * 注意：MerchantMapper 不需要在本类声明，父类 ServiceImpl 已经注入，用 baseMapper 访问。
 */
@Service
@RequiredArgsConstructor
public class MerchantServiceImpl extends ServiceImpl<MerchantMapper, Merchant> implements MerchantService {

    // 以下字段由 @RequiredArgsConstructor 生成的构造方法注入，无需 @Autowired
    private final UserMapper userMapper;                    // 查 User 表（商家的昵称/头像存在 user 表，不在 merchant 表）
    private final StringRedisTemplate stringRedisTemplate;  // Redis 字符串操作（存/取序列化后的 JSON）
    private final RedissonClient redissonClient;            // 分布式锁客户端，用于防缓存击穿
    private final ElasticsearchOperations elasticsearchOperations; // ES 高级查询（NativeQuery + bool query）
    private final MerchantEsRepository merchantEsRepository;       // ES 基础 CRUD（save/delete/saveAll）
    private final CanalSyncService canalSyncService;               // 复用 toDoc() 转换逻辑（全量导入时使用）

    // 把 Redis key 的前缀集中定义为常量，散落在代码各处的魔法字符串难以维护
    private static final String CACHE_KEY_PREFIX = "merchant:info:";  // 缓存 key：merchant:info:{merchantId}
    private static final String LOCK_KEY_PREFIX = "lock:merchant:";   // 锁 key：lock:merchant:{merchantId}

    @Override
    public void updateInfo(MerchantUpdateDTO dto) {
        // Sa-Token 从当前请求线程绑定的 token 里解析用户 id，不从请求参数取，
        // 防止用户伪造别人的 userId 修改他人资料
        Long userId = StpUtil.getLoginIdAsLong();

        // lambdaQuery() 来自父类 ServiceImpl，等价于：SELECT * FROM merchant WHERE user_id = ? LIMIT 1
        // 用 Lambda 引用而非字符串字段名（如 "user_id"），IDE 有类型检查，重构时字段名跟着改
        Merchant merchant = lambdaQuery()
                .eq(Merchant::getUserId, userId)
                .one();
        if (merchant == null) {
            // 首次填资料时 DB 里还没有记录，新建对象，后面统一走 saveOrUpdate，不用分 insert/update 两条路
            merchant = new Merchant();
            merchant.setUserId(userId);
        }

        // 只更新 DTO 中非 null 的字段，支持"部分修改"（如只改简介不动价格区间）
        // serviceTypes 在 DB 存 JSON 字符串（如 "[1,2,3]"），需先序列化
        if (dto.getServiceTypes() != null) {
            merchant.setServiceTypes(JSONUtil.toJsonStr(dto.getServiceTypes()));
        }
        if (dto.getCity() != null) merchant.setCity(dto.getCity());
        if (dto.getIntro() != null) merchant.setIntro(dto.getIntro());
        if (dto.getAlipayLink() != null) merchant.setAlipayLink(dto.getAlipayLink());
        if (dto.getXianyuLink() != null) merchant.setXianyuLink(dto.getXianyuLink());
        if (dto.getXiaohongshuLink() != null) merchant.setXiaohongshuLink(dto.getXiaohongshuLink());
        if (dto.getWeiboLink() != null) merchant.setWeiboLink(dto.getWeiboLink());
        if (dto.getPriceMin() != null) merchant.setPriceMin(dto.getPriceMin());
        if (dto.getPriceMax() != null) merchant.setPriceMax(dto.getPriceMax());
        if (dto.getBookingNotice() != null) merchant.setBookingNotice(dto.getBookingNotice());

        // saveOrUpdate() 来自父类 ServiceImpl：merchant.id 不为 null → UPDATE，否则 → INSERT
        saveOrUpdate(merchant);

        // Cache Aside 写策略：改 DB 后删缓存，而不是直接更新缓存。
        // 直接更新缓存在并发下有风险：
        //   线程 A 写 DB → 线程 B 写 DB → 线程 B 更新缓存 → 线程 A 更新缓存（旧值覆盖新值）
        // 删缓存后，下次读请求触发重建，始终以 DB 为准
        stringRedisTemplate.delete(CACHE_KEY_PREFIX + merchant.getId());
    }

    /**
     * 商家主页详情，实现 Cache Aside + 防三缓完整方案。
     *
     * ┌─────────────────────────────────────────────────────────┐
     * │ 问题          │ 场景                    │ 解法           │
     * ├─────────────────────────────────────────────────────────┤
     * │ 缓存穿透      │ 恶意请求大量不存在的 id │ 空值哨兵 2min  │
     * │ 缓存击穿      │ 热点缓存过期，并发打 DB │ 分布式锁 + DCL │
     * │ 缓存雪崩      │ 大量缓存同时过期        │ 随机 TTL 25~35min │
     * └─────────────────────────────────────────────────────────┘
     */
    @Override
    public MerchantVO getDetail(Long merchantId) {
        String cacheKey = CACHE_KEY_PREFIX + merchantId;  // 如 "merchant:info:42"

        // ── 第一次读缓存（无锁，快路径，绝大多数请求在这里直接返回） ──────────────
        String cached = stringRedisTemplate.opsForValue().get(cacheKey);
        if (cached != null) {
            // cached 非 null 说明 key 存在于 Redis，但值可能是空字符串（穿透哨兵）
            if (cached.isEmpty()) {
                // 命中穿透哨兵：这个 id 之前查过，DB 里没有，不用再查直接报错
                throw new BusinessException(ResultCode.MERCHANT_NOT_FOUND);
            }
            // 命中正常缓存：反序列化 JSON 字符串 → MerchantVO 对象返回
            return JSONUtil.toBean(cached, MerchantVO.class);
        }

        // ── 缓存未命中，加分布式锁防击穿 ─────────────────────────────────────────
        // getLock() 只是创建锁对象（本地操作），不会阻塞，也不会去抢锁
        RLock lock = redissonClient.getLock(LOCK_KEY_PREFIX + merchantId);
        try {
            // lock.lock() 才是真正抢锁，会阻塞直到拿到锁
            // 同一 merchantId 的并发请求在这里排队，只有一个能继续往下查 DB
            lock.lock();

            // ── 双重检查（DCL, Double-Checked Locking） ──────────────────────────
            // 场景：10 个请求同时过了第一次 get（都未命中），然后都来抢锁。
            // 第 1 个拿到锁，查 DB，写缓存，释放锁。
            // 第 2~10 个依次拿到锁时，缓存已经有了，进锁后再读一次，直接返回，不再查 DB。
            // 没有这次二次检查，每个拿到锁的线程都会重复查 DB。
            cached = stringRedisTemplate.opsForValue().get(cacheKey);
            if (cached != null) {
                if (cached.isEmpty()) throw new BusinessException(ResultCode.MERCHANT_NOT_FOUND);
                return JSONUtil.toBean(cached, MerchantVO.class);
            }

            // 真正查 DB（getById() 来自父类 ServiceImpl，等价于 SELECT * FROM merchant WHERE id = ?）
            Merchant merchant = getById(merchantId);
            if (merchant == null) {
                // ── 防缓存穿透：写空字符串哨兵 ──────────────────────────────────
                // 短 TTL（2 分钟）：如果商家之后真的注册了，最多 2 分钟后缓存自动过期可见
                stringRedisTemplate.opsForValue().set(cacheKey, "", 2, TimeUnit.MINUTES);
                throw new BusinessException(ResultCode.MERCHANT_NOT_FOUND);
            }

            // 商家的昵称/头像存在 user 表，需要额外查一次（两表关联不用 JOIN，保持查询简单）
            User user = userMapper.selectById(merchant.getUserId());
            MerchantVO vo = toVO(merchant, user);  // 合并两个实体的字段到 VO

            // ── 防缓存雪崩：随机 TTL ─────────────────────────────────────────────
            // nextInt(11) 返回 0~10，加上基础 25 分钟 → TTL 范围 25~35 分钟
            // 如果所有商家都用固定 TTL，会在同一时刻集体过期，瞬间大量请求打 DB
            int ttl = 25 + new Random().nextInt(11);
            stringRedisTemplate.opsForValue().set(cacheKey, JSONUtil.toJsonStr(vo), ttl, TimeUnit.MINUTES);
            return vo;

        } finally {
            // finally 保证不管正常/异常，锁一定被释放
            // isHeldByCurrentThread() 防御性判断：极少数情况下执行时间过长，
            // Redisson 的 Watchdog 可能已经判定锁超时并释放，此时直接 unlock() 会报错
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    /**
     * 商家搜索 — 全走 ES，查询结构：function_score 包裹 bool query。
     *
     * ── 整体评分公式 ──────────────────────────────────────────────────────
     *
     *   最终得分 = bool_score × (avgScore × 1.2 + log(1 + reviewCount) × 0.5)
     *              ↑ 相关度分                 ↑ 业务因子（两个 function 相加后 × 相关度）
     *
     *   boost_mode=multiply：把 bool query 的相关度 _score 与业务因子相乘，
     *   保证关键词越相关 × 评分越高 ＝ 最终排越前，而不是单纯比业务指标。
     *
     * ── 字段搜索范围 ──────────────────────────────────────────────────────
     *
     *   nickname^2        — 昵称，IK 中文分词，权重 2（匹配昵称比匹配简介得分高）
     *   intro             — 简介，IK 中文分词，权重 1
     *   nickname.pinyin   — 昵称拼音子字段（@InnerField suffix=pinyin），支持：
     *                         "zhuangniang" → 分割 → ["zhuang","niang"] → 命中"妆娘"
     *                         "lty"（首字母缩写）→ 命中"洛天依"
     *   intro.pinyin      — 简介拼音子字段（同上）
     *
     * ── 为什么不再用 sort(avgScore) ───────────────────────────────────────
     *   之前无关键词时用 sort 按 avgScore 排序，有关键词时用 _score 排序，
     *   两套策略切换不平滑。改用 function_score 后：
     *   无关键词（match_all，_score=1.0）→ 最终得分 = 1 × 业务因子，等价于按业务排序
     *   有关键词（_score 有实际值）      → 最终得分 = 相关度 × 业务因子，两者共同决定排名
     *   逻辑统一，不再需要分支切换。
     *
     * ── 为什么 ES 搜索比 MySQL LIKE 更好 ─────────────────────────────────
     *   MySQL LIKE '%妆娘%' → 全表扫描，不走索引；只搜单字段；不支持拼音
     *   ES 倒排索引 → O(log N)；多字段；IK 分词；拼音支持；相关度评分
     */
    @Override
    public Page<MerchantVO> search(String city, Integer serviceType, String keyword, int page, int size) {

        // ── Step 1：构建 bool 内层查询（决定哪些文档匹配） ─────────────────
        Query boolQuery;
        if (StringUtils.hasText(keyword)) {
            // multi_match：同时搜索中文字段 + 拼音子字段
            // "nickname.pinyin" 是 @InnerField(suffix="pinyin") 自动生成的子字段名
            // 搜"zhuangniang"会命中"妆娘"；搜"妆娘"也能通过 nickname 主字段命中
            boolQuery = Query.of(q -> q.bool(b -> b
                    .must(m -> m.multiMatch(mm -> mm
                            .fields("nickname^2", "intro",
                                    "nickname.pinyin", "intro.pinyin")
                            .query(keyword)
                    ))
                    .filter(buildFilters(city, serviceType))
            ));
        } else {
            // match_all：所有文档都参与，_score 统一为 1.0
            // 排名完全由 function_score 的业务因子决定（下面 Step 2）
            boolQuery = Query.of(q -> q.bool(b -> b
                    .must(m -> m.matchAll(ma -> ma))
                    .filter(buildFilters(city, serviceType))
            ));
        }

        // ── Step 2：function_score 叠加业务评分 ──────────────────────────
        //
        // field_value_factor 参数说明：
        //   field    — 取文档中哪个字段的值
        //   factor   — 乘以该值的系数（控制这个因子的影响权重）
        //   modifier — 对字段值做变换：
        //              none   → 直接用字段值（avgScore 直接用，线性影响）
        //              log1p  → log(1 + 值)（reviewCount 用对数，防止百万接单量独大）
        //   missing  — 字段为 null 时的默认值：
        //              avgScore 无评价 → 默认 3.0（中等），避免新商家被过度压制
        //              reviewCount 无记录 → 0，完全不加分
        //
        // 示例得分（无关键词，match_all._score=1.0）：
        //   商家A avgScore=4.8 reviewCount=50  → 1.0 × (5.76 + log(51)×0.5) ≈ 7.73
        //   商家B avgScore=4.0 reviewCount=200 → 1.0 × (4.80 + log(201)×0.5) ≈ 7.45
        //   商家C avgScore=5.0 reviewCount=0   → 1.0 × (6.00 + 0)           = 6.00
        //   商家D 新商家无评价                  → 1.0 × (3.60 + 0)           = 3.60
        Query finalQuery = Query.of(q -> q.functionScore(fs -> fs
                .query(boolQuery)
                .functions(
                        // 因子 1：avgScore 线性加权
                        FunctionScore.of(fn -> fn.fieldValueFactor(fvf -> fvf
                                .field("avgScore")
                                .factor(1.2d)
                                .modifier(FieldValueFactorModifier.None)
                                .missing(3.0d)
                        )),
                        // 因子 2：reviewCount 对数加权（接单量越多但边际递减）
                        FunctionScore.of(fn -> fn.fieldValueFactor(fvf -> fvf
                                .field("reviewCount")
                                .factor(0.5d)
                                .modifier(FieldValueFactorModifier.Log1p)
                                .missing(0.0d)
                        ))
                )
                .scoreMode(FunctionScoreMode.Sum)       // 两个因子分数相加
                .boostMode(FunctionBoostMode.Multiply)  // 因子总分 × bool query 相关度
        ));

        // ── Step 3：执行查询并分页 ────────────────────────────────────────
        // 不需要 withSort：function_score 的评分即是排名信号
        SearchHits<MerchantDoc> hits = elasticsearchOperations.search(
                NativeQuery.builder()
                        .withQuery(finalQuery)
                        .withPageable(PageRequest.of(page - 1, size))
                        .build(),
                MerchantDoc.class);

        List<MerchantVO> voList = hits.stream()
                .map(hit -> docToVO(hit.getContent()))
                .collect(Collectors.toList());

        Page<MerchantVO> result = new Page<>(page, size, hits.getTotalHits());
        result.setRecords(voList);
        return result;
    }

    /** 构建城市 + 服务类型 filter 列表（为空则返回空列表，bool query 的 filter 接受空列表） */
    private List<Query> buildFilters(String city, Integer serviceType) {
        List<Query> filters = new ArrayList<>();
        if (StringUtils.hasText(city)) {
            // term query：精确匹配 keyword 字段，不分词
            filters.add(Query.of(q -> q.term(t -> t.field("city").value(city))));
        }
        if (serviceType != null) {
            // serviceTypes 是 integer 数组，term query 匹配"数组中包含该值"
            filters.add(Query.of(q -> q.term(t -> t.field("serviceTypes").value(serviceType))));
        }
        return filters;
    }

    /**
     * 全量初始化：将 MySQL 中所有商家数据批量写入 ES。
     * 只需在首次部署 ES 或 ES 数据丢失时调用一次，
     * 之后由 Canal 负责增量同步，无需再调用此方法。
     */
    public void initEsData() {
        List<Merchant> merchants = list();
        if (merchants.isEmpty()) return;

        List<Long> userIds = merchants.stream().map(Merchant::getUserId).collect(Collectors.toList());
        Map<Long, User> userMap = userMapper.selectBatchIds(userIds).stream()
                .collect(Collectors.toMap(User::getId, u -> u));

        List<MerchantDoc> docs = merchants.stream()
                .map(m -> canalSyncService.toDoc(m, userMap.get(m.getUserId())))
                .collect(Collectors.toList());

        // saveAll 底层走 ES bulk API，一次请求写入所有文档，比逐条 save 快很多
        merchantEsRepository.saveAll(docs);
    }

    /** ES MerchantDoc → MerchantVO（nickname/avatar 直接来自 ES，无需查 User 表） */
    private MerchantVO docToVO(MerchantDoc doc) {
        MerchantVO vo = new MerchantVO();
        vo.setId(doc.getId());
        vo.setUserId(doc.getUserId());
        vo.setNickname(doc.getNickname());
        vo.setAvatar(doc.getAvatar());
        vo.setServiceTypes(doc.getServiceTypes() != null ? doc.getServiceTypes() : Collections.emptyList());
        vo.setCity(doc.getCity());
        vo.setIntro(doc.getIntro());
        vo.setAvgScore(doc.getAvgScore() != null ? java.math.BigDecimal.valueOf(doc.getAvgScore()) : null);
        vo.setReviewCount(doc.getReviewCount());
        vo.setPriceMin(doc.getPriceMin() != null ? java.math.BigDecimal.valueOf(doc.getPriceMin()) : null);
        vo.setPriceMax(doc.getPriceMax() != null ? java.math.BigDecimal.valueOf(doc.getPriceMax()) : null);
        return vo;
    }

    @Override
    public MerchantVO getMyInfo() {
        Long userId = StpUtil.getLoginIdAsLong();
        // 通过 userId 找到自己的商家记录，取到 merchantId 再走带缓存的 getDetail
        Merchant merchant = lambdaQuery().eq(Merchant::getUserId, userId).one();
        if (merchant == null) {
            throw new BusinessException(ResultCode.MERCHANT_NOT_FOUND);
        }
        // 复用 getDetail 而不是直接查 DB，商家自己看资料也走缓存，响应更快
        return getDetail(merchant.getId());
    }

    /**
     * 由 AccountInternalController 暴露给 mhp-social 的内部 Feign 接口调用。
     * 触发时机：用户提交评价后，ReviewServiceImpl 通过 Feign 调用此方法更新商家评分。
     * 不经过 Gateway，直接走服务间内网调用。
     */
    public void updateScore(Long merchantId, java.math.BigDecimal avgScore, Integer reviewCount) {
        Merchant merchant = getById(merchantId);
        if (merchant == null) {
            throw new BusinessException(ResultCode.MERCHANT_NOT_FOUND);
        }
        merchant.setAvgScore(avgScore);
        merchant.setReviewCount(reviewCount);
        // updateById() 来自父类 ServiceImpl，等价于 UPDATE merchant SET ... WHERE id = ?
        updateById(merchant);
        // 同 updateInfo：改 DB 后删缓存，下次展示时重建（Cache Aside 写策略）
        stringRedisTemplate.delete(CACHE_KEY_PREFIX + merchantId);
    }

    /**
     * 将 Merchant 实体 + User 实体合并转换为前端展示用的 MerchantVO。
     * 私有方法，仅在本类内部 getDetail / search / getMyInfo 复用，不对外暴露。
     */
    private MerchantVO toVO(Merchant merchant, User user) {
        MerchantVO vo = new MerchantVO();
        vo.setId(merchant.getId());
        vo.setUserId(merchant.getUserId());
        if (user != null) {
            // 昵称和头像来自 user 表，不在 merchant 表里
            vo.setNickname(user.getNickname());
            vo.setAvatar(user.getAvatar());
        }
        // serviceTypes 在 DB 存 JSON 字符串（如 "[1,2,3]"），返回给前端前反序列化为 List<Integer>
        // hasText() 同时处理 null 和空字符串，等价于 != null && !isEmpty() && !isBlank()
        if (StringUtils.hasText(merchant.getServiceTypes())) {
            vo.setServiceTypes(JSONUtil.toList(JSONUtil.parseArray(merchant.getServiceTypes()), Integer.class));
        } else {
            vo.setServiceTypes(Collections.emptyList());  // 避免前端收到 null 需要额外判空
        }
        vo.setCity(merchant.getCity());
        vo.setIntro(merchant.getIntro());
        vo.setAlipayLink(merchant.getAlipayLink());
        vo.setXianyuLink(merchant.getXianyuLink());
        vo.setXiaohongshuLink(merchant.getXiaohongshuLink());
        vo.setWeiboLink(merchant.getWeiboLink());
        vo.setAvgScore(merchant.getAvgScore());
        vo.setReviewCount(merchant.getReviewCount());
        vo.setPriceMin(merchant.getPriceMin());
        vo.setPriceMax(merchant.getPriceMax());
        vo.setBookingNotice(merchant.getBookingNotice());
        return vo;
    }
}
