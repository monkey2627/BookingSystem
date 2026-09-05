package com.mhp.booksystem.service;

import com.mhp.booksystem.dto.feign.MerchantScoreUpdateDTO;
import com.mhp.booksystem.mapper.ReviewMapper;
import com.mhp.booksystem.rpc.RpcMerchantService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.dubbo.config.annotation.DubboReference;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;

/**
 * 商家评分聚合更新服务。
 *
 * 独立于 ReviewServiceImpl 的原因：
 *   NotifyConsumer 消费 SCORE_UPDATE 消息时需要调用此逻辑，
 *   若放在 ReviewServiceImpl（private 方法）则无法从 Consumer 访问。
 *   提取为独立 @Service 后，Consumer 直接注入即可，也避免了循环依赖。
 *
 * 为什么用 SQL 聚合而不是 Java 全量 load？
 *   SELECT AVG / COUNT 在 MySQL 侧执行，是单条原子 SQL，
 *   并发多条评价时两个 Consumer 拿到的都是当前 DB 实际值，
 *   最终写入结果幂等（最后一次更新覆盖，值相同）。
 *   Java 全量 load 方案需要 load 全部 Review 对象到内存再计算，
 *   既浪费 IO，也存在并发 load 后互相覆盖的数据竞争。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReviewScoreService {

    private final ReviewMapper reviewMapper;

    @DubboReference(version = "1.0.0")
    private RpcMerchantService rpcMerchantService;

    public void updateMerchantScore(Long merchantId) {
        Map<String, Object> stats = reviewMapper.selectScoreStats(merchantId);
        if (stats == null) return;

        Object countObj = stats.get("review_count");
        Object avgObj   = stats.get("avg_score");
        if (countObj == null || avgObj == null) return;

        int reviewCount = ((Number) countObj).intValue();
        if (reviewCount == 0) return;

        BigDecimal avgScore = new BigDecimal(avgObj.toString()).setScale(1, RoundingMode.HALF_UP);

        MerchantScoreUpdateDTO dto = new MerchantScoreUpdateDTO();
        dto.setAvgScore(avgScore);
        dto.setReviewCount(reviewCount);
        rpcMerchantService.updateMerchantScore(merchantId, dto);

        log.info("[Score] 商家评分已更新 merchantId={} avg={} count={}", merchantId, avgScore, reviewCount);
    }
}
