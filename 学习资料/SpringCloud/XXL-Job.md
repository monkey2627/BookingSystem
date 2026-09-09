# XXL-Job 从使用到原理

---

## 一、XXL-Job 是什么，解决什么问题

### 1.1 单机定时任务的缺陷

Spring 自带的 `@Scheduled` 在单机时够用，但到了分布式环境有致命问题：

```
部署两个 mhp-booking 实例：

实例A  @Scheduled 每5分钟    ──▶ 执行 cancelTimeoutBookingJob
实例B  @Scheduled 每5分钟    ──▶ 执行 cancelTimeoutBookingJob（重复！）

同一批超时预约被取消两次，档期被释放两次，数据混乱。
```

根本原因：`@Scheduled` 跑在应用进程里，没有协调者，每个实例各自为政。

### 1.2 XXL-Job 的解法

引入一个独立的**调度中心**，所有实例向它注册，由它统一派发任务：

```
XXL-Job Admin（调度中心）
    │
    │  到点了，派发 cancelTimeoutBookingJob
    ▼
执行器注册表：
  实例A :9998  ──▶ 由调度中心选一个实例执行（路由策略）
  实例B :9998

→ 只有被选中的那个实例执行，另一个等待下次调度
```

---

## 二、核心概念

### 2.1 三个角色

| 角色 | 是什么 | 本项目对应 |
|------|--------|-----------|
| **调度中心（Admin）** | 独立部署的 Web 应用，管理任务配置、触发调度、记录日志 | Docker 容器 mhp-xxl-job，端口 8088 |
| **执行器（Executor）** | 嵌入业务服务的组件，负责接收调度命令并执行具体逻辑 | mhp-booking 内，端口 9998 |
| **任务（Job）** | 具体要执行的业务逻辑，用 `@XxlJob` 注解标注的方法 | `cancelTimeoutBookingJob`、`reminderJob` |

### 2.2 调度中心 vs 执行器的关系

```
调度中心（Admin）               执行器（Executor，嵌在 mhp-booking）
      │                                    │
      │  ①心跳注册（执行器启动后上报）       │
      │◀──────────────────────────────────│
      │                                    │
      │  ②到达 Cron 时间，触发调度          │
      │  HTTP 调用执行器的 /run 接口        │
      │──────────────────────────────────▶│
      │                                    │  ③执行 @XxlJob 方法
      │  ④回调执行结果（成功/失败）          │
      │◀──────────────────────────────────│
```

---

## 三、快速上手：在项目中使用 XXL-Job

### 3.1 依赖引入

```xml
<!-- pom.xml -->
<dependency>
    <groupId>com.xuxueli</groupId>
    <artifactId>xxl-job-core</artifactId>
    <version>2.4.0</version>
</dependency>
```

### 3.2 配置执行器

```yaml
# application.yaml（mhp-booking）
xxl:
  job:
    admin:
      addresses: http://localhost:8088/xxl-job-admin  # 调度中心地址
      accessToken: default_token                       # 通信令牌，Admin 和执行器必须一致
    executor:
      appname: booking-executor   # 执行器名称，在 Admin 控制台创建执行器时填这个
      ip:                         # 留空：自动获取本机 IP
      port: 9998                  # 执行器监听端口，Admin 通过这个端口发起调度
      logpath: ./logs/xxl-job     # 任务日志目录
      logretentiondays: 30        # 日志保留天数
```

### 3.3 注册执行器 Bean

```java
@Configuration
public class XxlJobConfig {

    @Value("${xxl.job.admin.addresses}")
    private String adminAddresses;

    @Value("${xxl.job.admin.accessToken}")
    private String accessToken;

    @Value("${xxl.job.executor.appname}")
    private String appname;

    @Value("${xxl.job.executor.port}")
    private int port;

    @Value("${xxl.job.executor.logpath}")
    private String logpath;

    @Value("${xxl.job.executor.logretentiondays}")
    private int logretentiondays;

    @Bean
    public XxlJobSpringExecutor xxlJobExecutor() {
        XxlJobSpringExecutor executor = new XxlJobSpringExecutor();
        executor.setAdminAddresses(adminAddresses);
        executor.setAccessToken(accessToken);
        executor.setAppname(appname);
        executor.setPort(port);
        executor.setLogPath(logpath);
        executor.setLogRetentionDays(logretentiondays);
        return executor;
    }
}
```

### 3.4 编写任务处理器

```java
@Slf4j
@Component
@RequiredArgsConstructor
public class BookingJobHandler {

    private final BookingMapper bookingMapper;
    private final ScheduleMapper scheduleMapper;

    // @XxlJob 的 value 就是在 Admin 控制台填写的 JobHandler 名称
    @XxlJob("cancelTimeoutBookingJob")
    public void cancelTimeoutBookings() {
        LocalDateTime deadline = LocalDateTime.now().minusMinutes(30);

        List<Booking> timeoutBookings = bookingMapper.selectList(
                new LambdaQueryWrapper<Booking>()
                        .eq(Booking::getStatus, 1)
                        .lt(Booking::getCreateTime, deadline)
                        .last("LIMIT 100")   // 每次最多处理 100 条，防数据库压力峰值
        );

        if (timeoutBookings.isEmpty()) return;

        for (Booking booking : timeoutBookings) {
            Booking cancel = new Booking();
            cancel.setId(booking.getId());
            cancel.setStatus(4);
            bookingMapper.updateById(cancel);

            Schedule release = new Schedule();
            release.setId(booking.getScheduleId());
            release.setStatus(0);
            scheduleMapper.updateById(release);
        }

        log.info("[JOB] 取消超时预约 {} 条", timeoutBookings.size());

        // 通知调度中心本次执行结果（可选，不写默认成功）
        XxlJobHelper.log("取消超时预约 {} 条", timeoutBookings.size());
    }
}
```

### 3.5 在 Admin 控制台配置任务

1. 访问 `http://localhost:8088/xxl-job-admin`（admin/123456）
2. **执行器管理** → 新增：AppName 填 `booking-executor`，注册方式选「自动注册」
3. **任务管理** → 新增：

```
执行器：booking-executor
任务描述：超时取消预约
Cron：0 */5 * * * ?          每5分钟
运行模式：BEAN
JobHandler：cancelTimeoutBookingJob
路由策略：第一个（多实例时只给第一个执行）
超时时间：60（秒）
失败重试次数：0
```

4. 点「启动」激活任务

---

## 四、Cron 表达式

XXL-Job 使用 **6 位 Cron**（比 Linux 的 5 位多一个秒字段）：

```
秒   分   时   日   月   周
0    */5  *    *    *    ?
```

| 字段 | 范围 | 特殊字符 |
|------|------|---------|
| 秒 | 0-59 | `, - * /` |
| 分 | 0-59 | `, - * /` |
| 时 | 0-23 | `, - * /` |
| 日 | 1-31 | `, - * / ? L W` |
| 月 | 1-12 | `, - * /` |
| 周 | 1-7（1=周日） | `, - * / ? L #` |

常用示例：

```
0 */5 * * * ?        每5分钟执行一次
0 0 9 * * ?          每天早上9点
0 0 0 * * ?          每天凌晨0点
0 0 9 ? * MON-FRI    工作日早上9点
0 0/30 9-18 * * ?    每天9点到18点，每30分钟
0 0 10,14,16 * * ?   每天10点、14点、16点
```

---

## 五、路由策略（多实例部署时的任务分配）

调度中心在派发任务时，如果执行器有多个实例，需要决定发给哪个：

| 策略 | 说明 | 适用场景 |
|------|------|---------|
| **第一个** | 固定发给注册列表的第一个 | 简单任务，不要求负载均衡 |
| **最后一个** | 固定发给最后一个 | 同上 |
| **轮询** | 依次发给每个实例 | 无状态任务，负载均衡 |
| **随机** | 随机选一个 | 同上 |
| **一致性 Hash** | 按任务参数 Hash 分配，同参数总发给同一实例 | 有状态任务（如按商家分片） |
| **最不经常使用** | 发给历史执行次数最少的 | 负载均衡 |
| **最近最久未使用** | 发给最久没执行任务的 | 负载均衡 |
| **故障转移** | 按顺序找第一个心跳正常的 | 高可用，主备模式 |
| **忙碌转移** | 找第一个不忙碌的实例 | 任务执行较慢时避免积压 |
| **分片广播** | 广播给所有实例，每个实例拿到分片参数 | 大数据量分片处理 |

**本项目使用「第一个」**，因为超时取消任务是全局扫描，不适合多实例同时跑（会重复处理同一批数据）。

---

## 六、分片广播（处理大数据量的利器）

当数据量很大（比如 100 万条记录），单机处理太慢，可以用分片广播让多个实例并行处理：

```java
@XxlJob("shardingJobHandler")
public void shardingJob() {
    // 获取分片参数
    int shardIndex = XxlJobHelper.getShardIndex();   // 当前实例是第几片（从0开始）
    int shardTotal = XxlJobHelper.getShardTotal();   // 总共几片（几个实例）

    // 按分片查数据：每个实例只处理属于自己分片的数据
    // 例如3个实例，shardIndex=0处理 id%3=0 的数据
    List<Booking> list = bookingMapper.selectList(
        new LambdaQueryWrapper<Booking>()
            .eq(Booking::getStatus, 1)
            .apply("id % {0} = {1}", shardTotal, shardIndex)
    );

    // 处理本分片数据...
    log.info("分片 {}/{} 处理 {} 条", shardIndex, shardTotal, list.size());
}
```

```
调度中心广播给3个实例：

实例A（shardIndex=0）→ 处理 id % 3 = 0 的数据
实例B（shardIndex=1）→ 处理 id % 3 = 1 的数据
实例C（shardIndex=2）→ 处理 id % 3 = 2 的数据

三个实例并行，速度提升3倍
```

---

## 七、实现原理深挖

XXL-Job 底层由四层机制共同驱动：

```
第一层：MySQL           → 持久化所有状态 + Admin 集群分布式锁
第二层：调度线程 + 时间轮 → 精准触发任务（解决 MySQL 轮询粒度不够精确的问题）
第三层：Netty HTTP      → Admin 与执行器之间的全部通信
第四层：JobThread       → 执行器内每个任务独立线程，串行安全执行
```

---

### 7.1 第一层：MySQL —— 持久化 + 分布式锁

#### 五张核心表

**① xxl_job_info（任务配置表，最核心）**

```sql
CREATE TABLE xxl_job_info (
  id                  int          -- 任务ID
  job_group           int          -- 所属执行器ID（关联 xxl_job_group）
  job_desc            varchar(255) -- 任务描述
  schedule_type       varchar(50)  -- 调度类型：CRON / FIX_RATE / FIX_DELAY
  schedule_conf       varchar(128) -- Cron 表达式，如 "0 */5 * * * ?"
  executor_handler    varchar(255) -- Handler 名称："cancelTimeoutBookingJob"
  executor_route_strategy varchar(50) -- 路由策略："FIRST / ROUND / RANDOM..."
  executor_block_strategy varchar(50) -- 阻塞策略："SERIAL_EXECUTION / ..."
  executor_timeout    int          -- 超时时间（秒），0=不超时
  executor_fail_retry_count int    -- 失败重试次数
  trigger_status      tinyint      -- 0=停止 1=运行
  trigger_last_time   bigint       -- 上次触发时间（毫秒时间戳）
  trigger_next_time   bigint       -- 下次触发时间（毫秒时间戳）← 调度核心字段
)
```

`trigger_next_time` 是调度的核心：Admin 每次触发后立即根据 Cron 计算下次时间写回数据库，调度线程通过扫这个字段知道哪些任务快到点了。

**② xxl_job_group（执行器注册表）**

```sql
CREATE TABLE xxl_job_group (
  id           int
  app_name     varchar(64)  -- "booking-executor"
  title        varchar(12)  -- 显示名称
  address_type tinyint      -- 0=自动注册 1=手动录入
  address_list text         -- 执行器地址列表，如 "192.168.1.10:9998,192.168.1.11:9998"
)
-- address_list 是逗号拼接的字符串，Admin 触发时按路由策略从中选一个
```

**③ xxl_job_registry（心跳记录表）**

```sql
CREATE TABLE xxl_job_registry (
  registry_group  varchar(50)  -- "EXECUTOR"
  registry_key    varchar(255) -- "booking-executor"
  registry_value  varchar(255) -- "192.168.1.10:9998"
  update_time     datetime     -- 最后一次心跳时间
)
-- Admin 有后台线程每 30 秒扫一次，把 update_time 超过 90 秒的记录删掉
-- 删掉后同步更新 xxl_job_group.address_list，移除下线实例
```

**④ xxl_job_log（执行日志表）**

```sql
CREATE TABLE xxl_job_log (
  id               bigint
  job_group        int
  job_id           int
  trigger_time     datetime   -- 触发时间
  trigger_code     int        -- 触发结果：200=成功 500=失败
  trigger_msg      text       -- 触发过程描述（选了哪个执行器、发了什么请求）
  handle_time      datetime   -- 执行完成时间
  handle_code      int        -- 执行结果：200=成功 500=失败
  handle_msg       text       -- 执行器回调的消息
  alarm_status     tinyint    -- 告警状态：0=未告警 1=已告警
)
-- Admin 控制台「调度日志」页面就是查这张表
-- trigger 和 handle 分开记录：触发成功不代表执行成功
```

**⑤ xxl_job_lock（分布式锁表）**

```sql
CREATE TABLE xxl_job_lock (
  lock_name varchar(50) PRIMARY KEY  -- 只有一行：'schedule_lock'
)
```

只有一行数据，存在的意义就是作为 `SELECT FOR UPDATE` 的锁对象。

#### Admin 集群分布式锁原理

多个 Admin 实例部署时，每个实例都有自己的调度线程，必须保证同一时刻只有一个在调度：

```sql
-- Admin 调度线程每次执行前（在同一个事务内）：
BEGIN;
SELECT * FROM xxl_job_lock WHERE lock_name = 'schedule_lock' FOR UPDATE;
-- 抢到行锁 → 执行调度逻辑（扫任务、触发、更新 trigger_next_time）
-- 没抢到   → 阻塞等待，直到持锁方提交事务释放锁

-- 调度完成：
COMMIT;  -- 事务提交，行锁自动释放，其他 Admin 实例才能抢到锁
```

**为什么不用 Redis 分布式锁？**
Admin 本身强依赖 MySQL（存任务配置），不想再引入 Redis 依赖。
MySQL 行锁简单可靠，调度间隔是秒级，锁竞争不频繁，性能完全够用。

---

### 7.2 第二层：调度线程 + 时间轮 —— 精准触发

#### 为什么要预读 5 秒？

如果每秒只读"当前这一秒"要触发的任务，有两个问题：
1. MySQL 查询本身有延迟（几毫秒到几十毫秒），可能导致任务实际触发时间比 Cron 晚
2. 调度线程一旦卡顿（GC、IO），当前秒的任务就漏掉了

提前 5 秒预读，把即将触发的任务先装进内存时间轮，由时间轮线程在精确时刻投递，即使调度线程偶尔卡顿也不会漏任务。

#### 调度线程完整逻辑

```
AdminApplication 启动
        │
        ▼
JobScheduleHelper.start() 启动两个线程：
        │
        ├── 【调度线程 scheduleThread】每隔 1 秒执行一次：
        │
        │   ① BEGIN 事务，SELECT FOR UPDATE 抢 schedule_lock 行锁
        │
        │   ② SELECT * FROM xxl_job_info
        │        WHERE trigger_status = 1               -- 只处理启用状态的任务
        │          AND trigger_next_time <= now()+5s    -- 预读未来5秒
        │        LIMIT 6000                             -- 单次最多处理6000个任务
        │
        │   ③ 对每个任务判断时间差：
        │        now - trigger_next_time > 5s （已经过期超过5秒）
        │            → 根据「调度过期策略」处理：
        │              · 忽略（MISFIRE_IGNORE）：不补跑，直接算下一次
        │              · 立即执行一次（MISFIRE_DEFAULT）：立即投入触发线程池
        │            → 更新 trigger_next_time 为下一次 Cron 时间
        │
        │        now <= trigger_next_time <= now+5s （5秒内要触发）
        │            → 放入时间轮：ringData[trigger_next_time的秒数] 追加该任务
        │            → 更新 trigger_next_time 为下一次 Cron 时间
        │
        │        trigger_next_time == now（就是当前这秒）
        │            → 直接放入触发线程池立即执行
        │            → 如果下次触发也在5秒内，顺手也放进时间轮
        │
        │   ④ COMMIT 事务（释放 schedule_lock 行锁）
        │
        └── 【时间轮线程 ringThread】每隔 1 秒执行一次：

            nowSecond = LocalDateTime.now().getSecond()  // 当前秒（0~59）

            // 取当前秒和上一秒的槽位（防止调度线程处理慢导致跨秒遗漏）
            List<Integer> nowSeconds = [nowSecond, (nowSecond+59)%60]

            for second in nowSeconds:
                jobs = ringData.remove(second)           // 取出并清空该槽位
                if jobs != null:
                    for job in jobs:
                        触发线程池.execute(triggerJob)    // 投递给触发线程池执行
```

#### 时间轮结构

```java
// 源码中的数据结构（简化）
private volatile static Map<Integer, List<Integer>> ringData = new ConcurrentHashMap<>();
//                          ↑秒数(0~59)   ↑该秒要触发的 jobId 列表

// 举例：当前时间 09:05:23
// 有三个任务：
//   job1 在 09:05:25 触发 → ringData[25] = [job1]
//   job2 在 09:05:27 触发 → ringData[27] = [job2]
//   job3 在 09:05:25 触发 → ringData[25] = [job1, job3]  （同一秒多个任务追加）

// 时间轮线程在第25秒时取出 [job1, job3]，投入触发线程池
```

**时间轮为什么用秒数（0~59）作为下标，而不是绝对时间戳？**
因为时间轮是复用的（Ring Buffer = 环形缓冲）。
第1分钟的第25秒和第2分钟的第25秒都放在 `ringData[25]`，
但任务已经在放入时就被触发（取出后清空），不存在混淆。
环形设计让内存消耗恒定（只需60个槽位），不随任务数增长。

#### 触发线程池

调度线程和时间轮线程都只负责"决定触发哪个任务"，真正发 HTTP 请求给执行器是交给**触发线程池**（默认 32 个线程）异步处理的：

```
触发线程池.execute(() -> {
    // 1. 从 xxl_job_group 的 address_list 按路由策略选一个执行器地址
    String address = routeStrategy.route(jobId, addressList);

    // 2. 在 xxl_job_log 插入一条触发记录，拿到 logId
    long logId = xxlJobLogDao.save(triggerLog);

    // 3. HTTP POST 执行器 /run 接口
    ReturnT result = executorBiz.run(triggerParam);

    // 4. 更新 xxl_job_log 的 trigger_code（200=触发成功 / 500=执行器不可达）
    xxlJobLogDao.updateTriggerInfo(logId, result.code, result.msg);
})
```

注意：trigger_code=200 只代表 HTTP 请求发出去了，不代表任务执行成功。
执行结果由执行器异步回调，写入 handle_code 字段。

---

### 7.3 第三层：Netty HTTP Server —— 通信细节

#### 为什么选 Netty

执行器作为嵌入 mhp-booking 的组件，不能依赖宿主 Web 容器（万一业务服务用的不是 Tomcat 怎么办）。Netty 是纯 Java NIO 框架，自带 HTTP 编解码器，轻量、无外部依赖，启动一个独立端口（9998）与业务端口（8082）完全隔离。

#### 执行器暴露的三个接口

```
POST /run    触发任务
  请求体：{
    jobId: 1,
    executorHandler: "cancelTimeoutBookingJob",
    executorParams: "",          // 控制台配置的任务参数
    executorBlockStrategy: "SERIAL_EXECUTION",
    executorTimeout: 60,         // 超时秒数
    logId: 12345,                // 本次执行的日志ID，用于回调
    logDateTime: 1724900700000
  }
  响应：{ code: 200, msg: "success" }  // 只代表接收成功，不代表执行完成

POST /kill   终止任务
  请求体：{ jobId: 1 }
  效果：向对应 JobThread 发中断信号，JobThread 捕获后停止执行

POST /log    查询执行日志
  请求体：{ logDateTim: ..., logId: ..., fromLineNum: 1 }
  效果：读取本地日志文件（logpath 目录下按日期和logId命名的文件）
  响应：{ fromLineNum, toLineNum, logContent, isEnd }
  // Admin 控制台点「执行日志」按钮，实际是分页调这个接口拉日志内容
```

#### accessToken 安全机制

Admin 和执行器通信时都会在请求头加 `XXL-JOB-ACCESS-TOKEN`：

```
// Admin 调执行器时：
Header: XXL-JOB-ACCESS-TOKEN: default_token

// 执行器回调 Admin 时：
Header: XXL-JOB-ACCESS-TOKEN: default_token
```

执行器收到请求后校验 token 是否和配置一致，不一致直接拒绝。
防止未授权的请求伪造触发指令。**生产环境必须改成复杂 token，不能用 `default_token`**。

#### 心跳与故障检测

```
执行器侧（每 30 秒）：
    HTTP POST http://admin/api/registry
    Body: { registryGroup: "EXECUTOR", registryKey: "booking-executor",
            registryValue: "192.168.1.10:9998" }
    → Admin 更新 xxl_job_registry.update_time

Admin 侧（每 30 秒扫一次 xxl_job_registry）：
    DELETE FROM xxl_job_registry
      WHERE update_time < now() - 90s   -- 90秒无心跳视为下线

    重新聚合 address_list：
    SELECT registry_value FROM xxl_job_registry
      WHERE registry_key = 'booking-executor'
    → 用逗号拼接，更新 xxl_job_group.address_list

结论：执行器下线后，最多 90 秒内 Admin 感知并从路由列表移除
```

#### 执行器回调 Admin

任务执行完成后，执行器主动回调 Admin（不是 Admin 轮询）：

```
HTTP POST http://admin/api/callback
Body: [{
    logId: 12345,
    logDateTim: 1724900700000,
    handleCode: 200,        // 200=成功 500=失败
    handleMsg: "取消超时预约 3 条"
}]

// 批量回调：执行器内有回调队列，每次最多把积压的回调一起发送，减少 HTTP 请求次数
```

Admin 收到回调后：
1. 更新 `xxl_job_log` 的 `handle_code`、`handle_msg`、`handle_time`
2. 如果 handle_code=500 且配置了重试次数 → 重新触发（重新走调度流程）
3. 如果配置了报警邮件且失败 → 发邮件告警

---

### 7.4 第四层：JobThread —— 任务线程生命周期

#### 线程创建时机

执行器收到 `/run` 请求时，查找是否已有该 jobId 的 JobThread：
- 有且存活 → 直接把触发请求压入其 Queue
- 没有（首次触发）→ 创建新的 JobThread，启动，压入 Queue

```java
// 源码简化（XxlJobExecutor.registJobThread）
JobThread jobThread = jobThreadRepository.get(jobId);
if (jobThread == null) {
    jobThread = new JobThread(jobId, handler);
    jobThread.start();                          // 线程启动，进入循环等待
    jobThreadRepository.put(jobId, jobThread);  // 注册到 Map
}
jobThread.pushTriggerQueue(triggerParam);       // 压入触发参数
```

#### JobThread 内部循环

```java
// 源码简化
public void run() {
    while (!toStop) {
        // 从 Queue 取触发参数，等待最多 3 秒
        TriggerParam triggerParam = triggerQueue.poll(3L, TimeUnit.SECONDS);

        if (triggerParam == null) {
            // 3秒没有新任务：检查空闲时间
            if (idleTimes > 30) {  // 连续空闲超过 30*3=90 秒
                // 自我销毁：从 jobThreadRepository 移除，线程退出
                XxlJobExecutor.removeJobThread(jobId, "idle timeout");
                return;
            }
            idleTimes++;
            continue;
        }

        idleTimes = 0;

        // 执行任务
        try {
            handler.execute();  // 反射调用 @XxlJob 方法

            // 回调成功
            callbackQueue.push(new HandleCallbackParam(logId, 200, "success"));

        } catch (Exception e) {
            // 回调失败
            callbackQueue.push(new HandleCallbackParam(logId, 500, e.getMessage()));
        }
    }
}
```

**空闲自销毁**：JobThread 连续 90 秒没有新任务进来，自动结束线程并从 Map 移除。
下次有任务时重新创建。这样不会为很久才触发一次的任务（如每天凌晨一次）常驻线程浪费资源。

#### 任务超时检测

如果 Admin 控制台配置了「超时时间」（如 60 秒），执行器用 `FutureTask` 实现超时检测：

```java
// 有超时配置时，用 FutureTask 包装执行
FutureTask<Boolean> futureTask = new FutureTask<>(() -> {
    handler.execute();
    return true;
});
Thread futureThread = new Thread(futureTask);
futureThread.start();

try {
    futureTask.get(timeout, TimeUnit.SECONDS);  // 等待最多 timeout 秒
} catch (TimeoutException e) {
    futureTask.cancel(true);       // 超时：中断执行线程
    futureThread.interrupt();
    throw new RuntimeException("任务执行超时，已中断");
}
// 超时后回调 Admin：handleCode=500，handleMsg="任务执行超时"
```

#### /kill 接口如何终止任务

```java
// 收到 /kill 请求后：
jobThread.toStop = true;          // 设置停止标志
jobThread.interrupt();            // 发送线程中断信号
// 如果任务正在执行且 @XxlJob 方法内有阻塞操作（sleep/IO），会抛 InterruptedException
// 任务代码需要捕获 InterruptedException 并提前退出，否则中断不一定立即生效
```

#### 阻塞处理策略的底层实现

```
SERIAL_EXECUTION（串行，默认）：
    triggerQueue 是 LinkedBlockingQueue，无限接收，先进先出
    上一次执行完才取下一条，天然串行

DISCARD_LATER（丢弃后续）：
    收到 /run 请求时，检查 triggerQueue.size() > 0
    → 有等待中的任务 → 直接返回 "阻塞中，丢弃" → 不压入 Queue

COVER_EARLY（覆盖之前）：
    收到 /run 请求时，找到当前正在执行的 JobThread
    → 先发 /kill 给旧 JobThread 停掉
    → 创建新 JobThread 执行新触发
```

---

### 7.5 执行器完整启动流程

```
Spring 容器启动完成
        │
        ▼
XxlJobSpringExecutor.afterSingletonsInstantiated()
（实现了 SmartInitializingSingleton，所有 Bean 初始化完才回调，
  保证 @XxlJob 所在的 Bean 已经就绪）
        │
        ├── ① 扫描 @XxlJob
        │      遍历所有 Spring Bean，找有 @XxlJob 注解的方法
        │      注册到 jobHandlerRepository（ConcurrentHashMap）：
        │        key = "cancelTimeoutBookingJob"
        │        value = new MethodJobHandler(bean, method)
        │                 // 持有 bean 引用 + Method 对象，执行时用反射调用
        │
        ├── ② 启动 Netty HTTP Server
        │      EmbedServer.start(port=9998)
        │      内部：new ServerBootstrap()
        │              .channel(NioServerSocketChannel.class)
        │              .childHandler(new EmbedHttpServerHandler())
        │              .bind(9999)
        │      EmbedHttpServerHandler 解析请求路径，分发到 run/kill/log 处理器
        │
        └── ③ 启动注册线程（registryThread）
               立即向 Admin 发一次注册请求（不等30秒）
               之后每 30 秒发一次心跳：
               POST http://admin/api/registry
               Body: { registryGroup:"EXECUTOR",
                       registryKey:"booking-executor",
                       registryValue:"192.168.1.10:9998" }
```

---

### 7.6 一次完整调度的全链路追踪

以 `cancelTimeoutBookingJob` 每 5 分钟触发为例：

```
[Admin 调度线程] 09:05:00
    SELECT FOR UPDATE 抢行锁
    扫 xxl_job_info：trigger_next_time = 09:05:00，距现在0秒
    → 直接放入触发线程池
    → 计算下次触发时间 09:10:00，UPDATE xxl_job_info SET trigger_next_time=...
    COMMIT 释放锁

[Admin 触发线程池] 09:05:00
    从 xxl_job_group 取 address_list = "192.168.1.10:9998"
    路由策略「第一个」→ 选 192.168.1.10:9998
    INSERT xxl_job_log（trigger_time=09:05:00）→ 拿到 logId=999
    HTTP POST http://192.168.1.10:9998/run
        { jobId:1, executorHandler:"cancelTimeoutBookingJob", logId:999, timeout:60 }
    收到响应 200 → UPDATE xxl_job_log SET trigger_code=200

[执行器 Netty Server] 09:05:00
    收到 /run 请求
    验证 accessToken 通过
    找到 jobId=1 的 JobThread（或新建）
    把 triggerParam 压入 triggerQueue

[JobThread] 09:05:00
    从 Queue 取出 triggerParam
    反射调用 cancelTimeoutBookings()
        查 booking 表：status=1 且 createTime < 08:35:00
        找到 3 条，批量更新 status=4，释放档期
        XxlJobHelper.log("取消超时预约 3 条")  // 写本地日志文件
    执行完成（耗时约 50ms）

[执行器回调线程] 09:05:00
    HTTP POST http://admin/api/callback
        [{ logId:999, handleCode:200, handleMsg:"取消超时预约 3 条" }]

[Admin] 09:05:00
    收到回调
    UPDATE xxl_job_log SET handle_code=200, handle_time=09:05:00, handle_msg=...
    alarm_status=0（无需告警）

[Admin 控制台] 调度日志页面
    查 xxl_job_log：trigger_code=200，handle_code=200，耗时50ms，日志可查
```

---

### 7.7 四层机制总览

```
┌─────────────────────────────────────────────────────────┐
│                    MySQL（持久层）                        │
│  xxl_job_info      → 任务配置 + trigger_next_time        │
│  xxl_job_group     → 执行器 address_list                 │
│  xxl_job_registry  → 心跳记录，90s清理                   │
│  xxl_job_log       → 每次触发 + 执行结果                  │
│  xxl_job_lock      → SELECT FOR UPDATE Admin集群锁       │
└───────────────────────────┬─────────────────────────────┘
                            │ 读写
┌───────────────────────────▼─────────────────────────────┐
│              调度线程 + 时间轮（Admin内存）                │
│  调度线程：每秒 SELECT 预读5秒内任务 → 时间轮 or 触发池     │
│  时间轮：  60槽环形数组，每秒投递到期任务 → 触发线程池       │
│  触发线程池（32线程）：HTTP POST 执行器 /run               │
└───────────────────────────┬─────────────────────────────┘
                            │ HTTP（accessToken鉴权）
┌───────────────────────────▼─────────────────────────────┐
│           Netty HTTP Server（执行器内嵌，port=9998）       │
│  /run  → 收触发请求，压入 JobThread Queue                  │
│  /kill → 设 toStop=true，interrupt JobThread             │
│  /log  → 读本地日志文件，分页返回给 Admin 控制台            │
│  心跳  → 每30秒 POST Admin /api/registry                  │
│  回调  → 执行完成后 POST Admin /api/callback               │
└───────────────────────────┬─────────────────────────────┘
                            │
┌───────────────────────────▼─────────────────────────────┐
│              JobThread（每个@XxlJob一个线程）              │
│  LinkedBlockingQueue 接收触发请求（串行安全）               │
│  FutureTask 实现超时检测                                   │
│  空闲90秒自动销毁，下次触发时重建                           │
│  执行完成 → 压入回调队列 → 回调线程批量发给 Admin            │
└─────────────────────────────────────────────────────────┘
```

---

## 八、失败重试与告警

### 8.1 失败重试

在 Admin 控制台配置任务时，可以设置「失败重试次数」：

```
失败重试次数：3

第一次失败 → 等待 → 重试第1次 → 失败 → 重试第2次 → 失败 → 重试第3次
                                                                │
                                                         仍失败 → 记录失败，触发告警
```

**任务幂等的重要性**：重试意味着同一个任务可能执行多次，
必须保证多次执行结果一致（幂等）。
本项目的取消任务是幂等的——已经是 status=4 的预约再次被 UPDATE 为 4，没有副作用。

### 8.2 告警配置

在任务配置里填「报警邮件」，失败时自动发邮件通知。
需要在 Admin 的配置文件里配置 SMTP 邮件服务器。

---

## 九、与其他方案对比

| 方案 | 优点 | 缺点 | 适用场景 |
|------|------|------|---------|
| `@Scheduled` | 无需额外组件，简单 | 多实例重复执行，无监控 | 单机，简单任务 |
| **XXL-Job** | 可视化控制台，国内生态好，易上手 | 需要独立部署 Admin + 数据库 | 中小型分布式项目 |
| ElasticJob | 轻量，无需独立 Admin，ZK 协调 | 需要 ZooKeeper，控制台较简陋 | 不想维护额外 Web 服务 |
| PowerJob | 支持工作流、MapReduce，功能最强 | 相对复杂，社区比 XXL-Job 小 | 复杂任务编排，大数据量分片 |
| 云厂商托管 | 免运维，高可用 | 付费，与云厂商绑定 | 生产环境，不想维护中间件 |

---

## 十、面试高频问题

**Q：XXL-Job 怎么保证任务不重复执行？**

调度中心是单点调度（集群时用数据库行锁互斥），触发请求只发给执行器的一个实例（路由策略决定），执行器内部每个 Job 用独立 JobThread 串行处理。三层保障。

**Q：执行器挂了任务怎么办？**

Admin 心跳检测到执行器下线，从注册列表移除。路由策略为「故障转移」时自动切到其他存活实例。任务下次到 Cron 时间时正常触发存活的实例。中间漏掉的那次触发可以通过「调度过期策略」配置：忽略/立即执行一次。

**Q：调度中心挂了任务怎么办？**

任务不会触发，执行器静默等待。Admin 重启后，根据 `xxl_job_info` 表重新计算 `trigger_next_time` 恢复调度。所以 Admin 重启后可能有一次「补偿触发」（调度过期策略=立即执行）。

**Q：任务执行时间超过调度间隔怎么办？**

由「阻塞处理策略」决定：默认串行（新触发排队等待），也可以配置丢弃或覆盖。本项目 cancelTimeoutBookingJob 最多处理 100 条，执行时间远小于 5 分钟，不会出现积压。

**Q：@XxlJob 方法里能拿到任务参数吗？**

```java
@XxlJob("myJob")
public void myJob() {
    // 在 Admin 控制台「任务参数」字段填入的字符串
    String param = XxlJobHelper.getJobParam();

    // 分片参数（分片广播时）
    int shardIndex = XxlJobHelper.getShardIndex();
    int shardTotal = XxlJobHelper.getShardTotal();

    // 向 Admin 写执行日志（在 Admin 控制台「执行日志」里查看）
    XxlJobHelper.log("处理参数：{}", param);
}
```
