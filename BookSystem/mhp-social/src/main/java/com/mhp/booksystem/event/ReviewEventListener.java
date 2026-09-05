package com.mhp.booksystem.event;

import com.mhp.booksystem.mq.SocialMQSender;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 评价创建事件监听器。
 *
 * 为什么要单独建这个类而不写在 ReviewServiceImpl 里？
 *   @Async 依赖 Spring AOP 代理生效。若监听方法写在 ReviewServiceImpl 内，
 *   publishEvent() 触发的是 Spring 事件机制对该 Bean 的代理调用，
 *   单独的 @Component 能保证代理链路清晰，避免 self-invocation 绕过代理。
 *
 * 为什么用 @TransactionalEventListener(AFTER_COMMIT) 而不是普通 @EventListener？
 *   create() 内 publishEvent() 时事务尚未提交，DB 里还没有这条 review。
 *   若立即发 MQ，Consumer 查 DB 时拿不到新 review，算出来的均分是错的。
 *   AFTER_COMMIT 保证事务提交后才触发，Consumer 查到的数据一定是完整的。
 *   若事务回滚，事件不触发，不会发出无效的 MQ 消息。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReviewEventListener {

    private final SocialMQSender socialMQSender;

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onReviewCreated(ReviewCreatedEvent event) {
        log.info("[Event] 评价事务已提交，触发评分异步更新 merchantId={}", event.getMerchantId());
        socialMQSender.sendScoreUpdate(event.getMerchantId());
    }
}
