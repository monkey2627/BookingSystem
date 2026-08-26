package com.mhp.booksystem.canal;

import cn.hutool.json.JSONUtil;
import com.alibaba.otter.canal.client.CanalConnector;
import com.alibaba.otter.canal.client.CanalConnectors;
import com.alibaba.otter.canal.protocol.CanalEntry;
import com.alibaba.otter.canal.protocol.Message;
import com.mhp.booksystem.document.MerchantDoc;
import com.mhp.booksystem.entity.Merchant;
import com.mhp.booksystem.entity.User;
import com.mhp.booksystem.mapper.MerchantMapper;
import com.mhp.booksystem.mapper.UserMapper;
import com.mhp.booksystem.repository.MerchantEsRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.net.InetSocketAddress;
import java.util.Collections;
import java.util.List;

/**
 * Canal CDC 同步服务：监听 MySQL binlog → 实时同步商家数据到 Elasticsearch。
 *
 * ── Canal 工作原理 ──────────────────────────────────────────────────────────
 * Canal server 伪装成 MySQL 从库（Slave），通过 binlog 协议接收主库的数据变更事件。
 * 本类作为 Canal 客户端，从 Canal server 拉取这些事件并处理：
 *
 *   MySQL 数据变更（INSERT/UPDATE/DELETE）
 *       ↓  ROW 格式 binlog（记录行的完整前镜像+后镜像）
 *   Canal server（解析 binlog → 结构化 Entry）
 *       ↓  TCP 协议（canal.client 库）
 *   CanalSyncService（本类）
 *       ↓  解析 Entry → 同步到 ES
 *   Elasticsearch merchant 索引
 *
 * ── 为什么监听 merchant + user 两张表 ──────────────────────────────────────
 * ES 的 MerchantDoc 中存有 nickname 和 avatar，这两个字段来自 user 表。
 * 如果用户改了昵称，只监听 merchant 表会导致 ES 中的昵称过期。
 * 所以订阅 user 表，user 更新时找到其对应 merchant 并更新 ES doc。
 *
 * ── ApplicationRunner 的作用 ───────────────────────────────────────────────
 * ApplicationRunner.run() 在 Spring 容器完全启动后被回调（所有 Bean 都就绪之后）。
 * 这里在 run() 里启动一个后台守护线程持续拉取 Canal 事件，不阻塞主线程。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CanalSyncService implements ApplicationRunner {

    private final MerchantEsRepository merchantEsRepository;
    private final MerchantMapper merchantMapper;
    private final UserMapper userMapper;

    @Value("${canal.server}")
    private String canalServer;        // 如 "localhost:11111"

    @Value("${canal.destination}")
    private String destination;        // Canal server 中配置的 destination 名，如 "example"

    @Override
    public void run(ApplicationArguments args) {
        // 解析 host:port
        String[] parts = canalServer.split(":");
        String host = parts[0];
        int port = Integer.parseInt(parts[1]);

        // 创建连接器（不传用户名/密码，Canal server 默认无认证）
        CanalConnector connector = CanalConnectors.newSingleConnector(
                new InetSocketAddress(host, port), destination, "", "");

        // 后台守护线程：应用关闭时自动销毁，不影响 JVM 退出
        Thread thread = new Thread(() -> syncLoop(connector), "canal-sync-thread");
        thread.setDaemon(true);
        thread.start();
        log.info("Canal sync started → {}:{} destination={}", host, port, destination);
    }

    /**
     * 主循环：持续从 Canal server 拉取 binlog 事件并处理。
     *
     * getWithoutAck(100)：每次最多拉取 100 条，返回 Message 对象。
     *   - message.getId() == -1 且 entries 为空：表示当前没有新事件，稍等后继续。
     *   - 否则：遍历 entries 处理每条变更。
     * ack(batchId)：告知 Canal server 本批已消费完毕，可以移动游标。
     *   如果不 ack，Canal 会一直保留这批数据（类似消息队列的 ack 机制）。
     */
    private void syncLoop(CanalConnector connector) {
        try {
            connector.connect();
            // 订阅过滤规则，与 docker-compose canal.instance.filter.regex 一致
            // "mhp\\.merchant,mhp\\.user" 意思：mhp 库的 merchant 表和 user 表
            connector.subscribe("mhp\\.merchant,mhp\\.user");
            connector.rollback(); // 回滚上次未 ack 的 batch，防止重复消费

            log.info("Canal sync connected, subscribing mhp.merchant + mhp.user");

            while (!Thread.currentThread().isInterrupted()) {
                Message message = connector.getWithoutAck(100);
                long batchId = message.getId();

                if (batchId == -1 || message.getEntries().isEmpty()) {
                    // 没有新数据，等待 500ms 再轮询，避免空转消耗 CPU
                    sleep(500);
                    connector.ack(batchId);
                    continue;
                }

                try {
                    for (CanalEntry.Entry entry : message.getEntries()) {
                        // 只处理行数据事件（DDL、事务开始/结束等类型直接跳过）
                        if (entry.getEntryType() != CanalEntry.EntryType.ROWDATA) continue;
                        processEntry(entry);
                    }
                    connector.ack(batchId); // 本批全部处理成功，提交确认
                } catch (Exception e) {
                    log.error("Canal sync error, rollback batch {}", batchId, e);
                    connector.rollback(batchId); // 出错则回滚，下次重新消费
                }
            }
        } catch (Exception e) {
            log.error("Canal sync thread fatal error", e);
        } finally {
            connector.disconnect();
        }
    }

    /**
     * 处理单条 Entry（一个表的一次行变更事件）。
     *
     * RowChange 包含：eventType（INSERT/UPDATE/DELETE）+ 若干 RowData（每行一个）。
     * 每个 RowData 包含：beforeColumns（操作前的行数据）+ afterColumns（操作后的行数据）。
     * DELETE 操作只有 beforeColumns；INSERT 只有 afterColumns；UPDATE 两者都有。
     */
    private void processEntry(CanalEntry.Entry entry) throws Exception {
        String tableName = entry.getHeader().getTableName();
        CanalEntry.RowChange rowChange = CanalEntry.RowChange.parseFrom(entry.getStoreValue());
        CanalEntry.EventType eventType = rowChange.getEventType();

        for (CanalEntry.RowData rowData : rowChange.getRowDatasList()) {
            if ("merchant".equals(tableName)) {
                handleMerchantChange(eventType, rowData);
            } else if ("user".equals(tableName)) {
                handleUserChange(eventType, rowData);
            }
        }
    }

    /** 商家表变更：INSERT/UPDATE → 重新从 DB 查完整数据后写 ES；DELETE → 从 ES 删除 */
    private void handleMerchantChange(CanalEntry.EventType eventType, CanalEntry.RowData rowData) {
        if (eventType == CanalEntry.EventType.DELETE) {
            Long id = extractLong(rowData.getBeforeColumnsList(), "id");
            if (id != null) {
                merchantEsRepository.deleteById(id);
                log.debug("Canal: merchant deleted from ES, id={}", id);
            }
            return;
        }

        // INSERT 或 UPDATE：从 DB 查最新数据（binlog 的 afterColumns 不含所有字段，直接查更可靠）
        Long id = extractLong(rowData.getAfterColumnsList(), "id");
        if (id == null) return;

        Merchant merchant = merchantMapper.selectById(id);
        if (merchant == null) return; // 可能是逻辑删除

        User user = userMapper.selectById(merchant.getUserId());
        merchantEsRepository.save(toDoc(merchant, user));
        log.debug("Canal: merchant synced to ES, id={}", id);
    }

    /**
     * 用户表变更：用户改昵称/头像时，找到其对应的商家 ES 文档并更新。
     * 只处理 UPDATE（注册不会立刻有 merchant 记录，DELETE 极少发生）。
     */
    private void handleUserChange(CanalEntry.EventType eventType, CanalEntry.RowData rowData) {
        if (eventType != CanalEntry.EventType.UPDATE) return;

        Long userId = extractLong(rowData.getAfterColumnsList(), "id");
        if (userId == null) return;

        // 查此用户对应的商家记录
        Merchant merchant = merchantMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Merchant>()
                        .eq(Merchant::getUserId, userId));
        if (merchant == null) return; // 该用户还没有商家资料，不需要同步

        User user = userMapper.selectById(userId);
        if (user == null) return;

        // 仅更新 ES doc 中的 nickname 和 avatar（其余字段不变）
        merchantEsRepository.findById(merchant.getId()).ifPresent(doc -> {
            doc.setNickname(user.getNickname());
            doc.setAvatar(user.getAvatar());
            merchantEsRepository.save(doc);
            log.debug("Canal: merchant ES doc updated for user nickname change, userId={}", userId);
        });
    }

    /** 从 Canal columns 列表中按字段名提取 Long 值 */
    private Long extractLong(List<CanalEntry.Column> columns, String fieldName) {
        return columns.stream()
                .filter(col -> fieldName.equals(col.getName()))
                .map(col -> col.getValue().isEmpty() ? null : Long.parseLong(col.getValue()))
                .findFirst()
                .orElse(null);
    }

    /** Merchant + User 实体 → ES 文档（与 MerchantServiceImpl.toVO 逻辑对称） */
    public MerchantDoc toDoc(Merchant merchant, User user) {
        MerchantDoc doc = new MerchantDoc();
        doc.setId(merchant.getId());
        doc.setUserId(merchant.getUserId());
        if (user != null) {
            doc.setNickname(user.getNickname());
            doc.setAvatar(user.getAvatar());
        }
        if (StringUtils.hasText(merchant.getServiceTypes())) {
            doc.setServiceTypes(JSONUtil.toList(JSONUtil.parseArray(merchant.getServiceTypes()), Integer.class));
        } else {
            doc.setServiceTypes(Collections.emptyList());
        }
        doc.setCity(merchant.getCity());
        doc.setIntro(merchant.getIntro());
        doc.setAvgScore(merchant.getAvgScore() != null ? merchant.getAvgScore().doubleValue() : null);
        doc.setReviewCount(merchant.getReviewCount());
        doc.setPriceMin(merchant.getPriceMin() != null ? merchant.getPriceMin().doubleValue() : null);
        doc.setPriceMax(merchant.getPriceMax() != null ? merchant.getPriceMax().doubleValue() : null);
        return doc;
    }

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
