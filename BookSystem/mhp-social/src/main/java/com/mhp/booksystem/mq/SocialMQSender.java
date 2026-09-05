package com.mhp.booksystem.mq;

import cn.hutool.core.util.IdUtil;
import com.mhp.booksystem.config.RabbitConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

/**
 * mhp-social 侧 MQ 发布器。
 *
 * 原来 mhp-social 只消费消息（NotifyConsumer），不发布。
 * 评分异步更新改造后，social 需要在评价提交后发一条 SCORE_UPDATE 消息给自己消费。
 *
 * routing key "notify.score_update" 匹配现有绑定规则 "notify.#"，
 * 消息进入 notify.queue，由 NotifyConsumer.onNotify() 处理，
 * 无需新建 Exchange 或 Queue。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SocialMQSender {

    private final RabbitTemplate rabbitTemplate;

    public void sendScoreUpdate(Long merchantId) {
        NotifyMessage msg = new NotifyMessage();
        msg.setMsgId(IdUtil.fastSimpleUUID());
        msg.setType("SCORE_UPDATE");
        msg.setMerchantId(merchantId);
        rabbitTemplate.convertAndSend(RabbitConfig.EXCHANGE, "notify.score_update", msg);
        log.info("[MQ] 评分更新消息已发送 merchantId={} msgId={}", merchantId, msg.getMsgId());
    }
}
