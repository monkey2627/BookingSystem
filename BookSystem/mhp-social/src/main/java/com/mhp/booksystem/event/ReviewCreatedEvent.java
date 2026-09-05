package com.mhp.booksystem.event;

/**
 * 评价提交事件，由 ReviewServiceImpl.create() 在事务内发布。
 * ReviewEventListener 注册了 AFTER_COMMIT 监听，事务提交后才会触发，
 * 确保 Consumer 查 DB 时能看到刚写入的 review 记录。
 */
public class ReviewCreatedEvent {

    private final Long merchantId;

    public ReviewCreatedEvent(Long merchantId) {
        this.merchantId = merchantId;
    }

    public Long getMerchantId() {
        return merchantId;
    }
}
