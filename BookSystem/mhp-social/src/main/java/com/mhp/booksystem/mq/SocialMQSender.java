package com.mhp.booksystem.mq;

import cn.hutool.core.util.IdUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.stereotype.Component;

/**
 * mhp-social 侧 MQ 发布器。
 *
 * 评价提交后，social 发一条 SCORE_UPDATE 消息给自己消费，异步更新商家评分。
 * routing 设计：topic=mhp-notify-topic，tag=SCORE_UPDATE，
 * NotifyConsumer 的 selectorExpression="*" 全量消费，在消费逻辑内按 type 分支处理。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SocialMQSender {

    private static final String TOPIC = "mhp-notify-topic";

    private final RocketMQTemplate rocketMQTemplate;

    public void sendScoreUpdate(Long merchantId) {
        NotifyMessage msg = new NotifyMessage();
        msg.setMsgId(IdUtil.fastSimpleUUID());
        msg.setType("SCORE_UPDATE");
        msg.setMerchantId(merchantId);
        rocketMQTemplate.syncSend(TOPIC + ":SCORE_UPDATE", msg);
        log.info("[MQ] 评分更新消息已发送 merchantId={} msgId={}", merchantId, msg.getMsgId());
    }
}
