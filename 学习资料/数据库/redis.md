# Redis 系统笔记

---

## 一、定义与特点

Redis 是**基于内存**的 key-value 结构数据库，读写性能极高（单线程 10 万+ QPS）。

### 为什么这么快？

1. **纯内存操作**：数据全部在内存，无磁盘随机 IO
2. **单线程模型**：命令执行串行，无锁竞争（网络 IO 多路复用）
3. **高效数据结构**：跳表、压缩列表、哈希表等针对场景优化
4. **非阻塞 IO 多路复用**：epoll/kqueue 同时监听大量连接，IO 不阻塞主线程

> 单线程指**命令处理线程**是单线程；网络 IO、持久化是多线程（Redis 6.0 后网络 IO 也多线程化）。

---

## 二、数据类型

> key 都是 `String` 类型；value 有 5 种基本类型。

### 1. String（字符串）

最基础的类型，可存文本、数字、二进制数据。最大 512MB。

**底层编码**：int（整数）/ embstr（≤44字节字符串）/ raw（>44字节字符串）

```bash
SET key value                    # 设置
GET key                          # 获取
DEL key                          # 删除
MSET k1 v1 k2 v2                 # 批量设置
MGET k1 k2                       # 批量获取
INCR key                         # 原子自增1（key 不存在则从0开始）
INCRBY key 10                    # 原子增加指定值
DECR key                         # 原子自减1
SETNX key value                  # key 不存在时才设置（SET if Not eXists，分布式锁基础）
SETEX key seconds value          # 设置值同时设过期时间（原子操作）
SET key value EX 60 NX           # EX=过期秒数，NX=不存在才设置（推荐写法）
APPEND key value                 # 追加内容
STRLEN key                       # 获取字符串长度
```

**典型应用**：缓存、计数器（INCR）、分布式锁（SETNX）、Session 存储

---

### 2. Hash（哈希/散列）

类似 Java `HashMap`，一个 key 下存多个 field-value 对，适合存对象。

**底层编码**：listpack（小数据）/ hashtable（大数据）

```bash
HSET user:1 name 张三 age 18 city 北京   # 设置多个字段
HGET user:1 name                          # 获取单个字段
HMGET user:1 name age                     # 获取多个字段
HGETALL user:1                            # 获取所有 field-value
HDEL user:1 city                          # 删除字段
HEXISTS user:1 name                       # 字段是否存在
HKEYS user:1                              # 所有字段名
HVALS user:1                              # 所有字段值
HLEN user:1                               # 字段数量
HINCRBY user:1 age 1                      # 字段原子自增
```

**典型应用**：存用户对象（比 JSON 字符串方便单字段更新）

---

### 3. List（列表）

有序、可重复，底层是双向链表，支持从两端快速插入/删除。

**底层编码**：listpack（小数据）/ quicklist（大数据）

```bash
LPUSH list a b c     # 从左插入（结果：c b a）
RPUSH list x y       # 从右插入（结果：c b a x y）
LPOP list            # 从左弹出并返回
RPOP list            # 从右弹出并返回
LLEN list            # 列表长度
LRANGE list 0 -1     # 获取全部元素（-1 表示最后一个）
LRANGE list 0 2      # 获取前3个
LINDEX list 0        # 获取指定下标的元素
LREM list 2 a        # 删除2个值为 a 的元素
LTRIM list 0 9       # 只保留下标 0~9 的元素（裁剪）
BLPOP list 10        # 阻塞式左弹出，等待10秒（消息队列基础）
```

**典型应用**：消息队列（LPUSH + BRPOP）、最新 N 条记录（LPUSH + LTRIM）

---

### 4. Set（集合）

无序、不重复，支持集合运算。

**底层编码**：listpack（小数据）/ hashtable（大数据）

```bash
SADD tags java python go     # 添加成员
SMEMBERS tags                # 返回所有成员
SCARD tags                   # 成员数量
SISMEMBER tags java          # 是否包含某成员
SREM tags go                 # 删除成员
SRANDMEMBER tags 2           # 随机返回2个成员
SPOP tags                    # 随机弹出一个成员

# 集合运算
SINTER set1 set2             # 交集（共同关注）
SUNION set1 set2             # 并集
SDIFF set1 set2              # 差集（set1 有但 set2 没有）
SINTERSTORE dest set1 set2   # 交集结果存入 dest
```

**典型应用**：共同关注、去重（防刷）、随机抽奖（SRANDMEMBER）

---

### 5. Sorted Set / ZSet（有序集合）

成员唯一，每个成员关联一个 `score` 分数，按 score 自动排序。

**底层编码**：listpack（小数据）/ skiplist + hashtable（大数据）

跳表（skiplist）：多层链表结构，查找 O(log n)，支持范围查询，比红黑树实现更简单。

```bash
ZADD rank 95 赵雷 87 孙风 92 钱电   # 添加（score member）
ZSCORE rank 赵雷                     # 获取分数 → 95
ZRANK rank 孙风                      # 升序排名（从0开始）
ZREVRANK rank 赵雷                   # 降序排名
ZRANGE rank 0 -1                     # 升序返回所有成员
ZRANGE rank 0 -1 WITHSCORES         # 带分数返回
ZREVRANGE rank 0 2                   # 降序返回前3名
ZRANGEBYSCORE rank 80 100           # 返回分数在 [80,100] 的成员
ZCOUNT rank 80 100                   # 分数在 [80,100] 的成员数
ZINCRBY rank 5 孙风                  # 分数加5
ZREM rank 孙风                       # 删除成员
ZCARD rank                           # 成员总数
```

**典型应用**：排行榜（ZREVRANGE）、带权重优先级队列、延时队列（score 存执行时间戳）

---

## 三、通用命令

```bash
KEYS pattern          # 查找匹配的 key（生产环境慎用！阻塞）
SCAN cursor MATCH pattern COUNT 100   # 非阻塞渐进式遍历（推荐）
EXISTS key            # key 是否存在（返回1/0）
TYPE key              # 返回 key 的类型
DEL key [key ...]     # 删除（同步）
UNLINK key [key ...]  # 删除（异步，不阻塞，大 key 用这个）
EXPIRE key seconds    # 设置过期时间（秒）
PEXPIRE key ms        # 设置过期时间（毫秒）
TTL key               # 查看剩余过期时间（-1=永久，-2=已过期/不存在）
PERSIST key           # 移除过期时间，变为永久
RENAME key newkey     # 重命名
OBJECT ENCODING key   # 查看底层编码
```

---

## 四、过期策略

Redis 删除过期 key 的两种机制，**同时使用**：

| 策略 | 触发时机 | 特点 |
|------|---------|------|
| **惰性删除** | 访问 key 时才检查是否过期 | 节省 CPU，但内存不及时释放 |
| **定期删除** | 每隔一段时间（默认100ms）随机抽查一批 key | 平衡 CPU 和内存 |

---

## 五、内存淘汰策略（Eviction Policy）

当内存达到 `maxmemory` 上限时，触发淘汰：

```bash
# 查看/设置淘汰策略
CONFIG GET maxmemory-policy
CONFIG SET maxmemory-policy allkeys-lru
```

| 策略 | 说明 |
|------|------|
| `noeviction` | 不淘汰，写满后报错（默认） |
| `allkeys-lru` | 所有 key 中淘汰最近最少使用的（**推荐，通用缓存**） |
| `allkeys-lfu` | 所有 key 中淘汰使用频率最低的 |
| `allkeys-random` | 随机淘汰 |
| `volatile-lru` | 只淘汰设了过期时间的 key（LRU） |
| `volatile-lfu` | 只淘汰设了过期时间的 key（LFU） |
| `volatile-ttl` | 优先淘汰快过期的 key |
| `volatile-random` | 随机淘汰设了过期时间的 key |

---

## 六、持久化（Persistence）

### RDB（快照）

定期把内存数据以二进制快照形式写入磁盘（`dump.rdb`）。

```bash
# redis.conf 配置（满足任一条件触发）
save 3600 1     # 3600秒内有1次修改
save 300 100    # 300秒内有100次修改
save 60 10000   # 60秒内有10000次修改

# 手动触发（阻塞主线程，生产慎用）
SAVE
# 非阻塞快照（fork 子进程，推荐）
BGSAVE
```

**优点**：文件紧凑，恢复速度快；**缺点**：可能丢失最后一次快照之后的数据。

### AOF（追加日志）

把每条写命令追加到日志文件（`appendonly.aof`），重启时重放恢复。

```bash
# redis.conf
appendonly yes
appendfsync everysec   # always（每条）/ everysec（每秒）/ no（OS决定）
```

**优点**：数据更完整（最多丢1秒数据）；**缺点**：文件大，恢复慢。

### AOF 重写

AOF 文件会越来越大，Redis 定期将命令合并精简（如把100次 INCR 合并成一条 SET）：

```bash
BGREWRITEAOF   # 手动触发重写
```

### RDB + AOF 混合模式（推荐）

```bash
aof-use-rdb-preamble yes   # RDB 快照 + 增量 AOF，兼顾速度和数据完整性
```

---

## 七、缓存常见问题

### 缓存穿透

**现象**：查询**不存在的数据**，每次都穿透到数据库（恶意攻击或 bug）。

**解决**：
1. **空值缓存**：DB 没查到，也缓存 `""` 或 null，设短 TTL（2~5分钟）
2. **布隆过滤器**（Bloom Filter）：请求先过布隆过滤器，不存在的直接拒绝，节省内存

### 缓存击穿

**现象**：**热点 key 突然过期**，瞬间大量请求打到 DB。

**解决**：
1. **逻辑过期**：key 不设真实过期时间，数据里存过期字段，查到"过期"时异步重建缓存
2. **互斥锁**：缓存失效时只允许一个线程去查 DB 并重建缓存，其余等待

```java
// Redis 分布式锁（双重检查模式）
String cache = redis.get(key);
if (cache == null) {
    String lockKey = "lock:" + key;
    boolean locked = redis.setnx(lockKey, "1", 30); // 获取锁
    if (locked) {
        try {
            cache = db.query(id);         // 查 DB
            redis.set(key, cache, 30*60); // 写缓存
        } finally {
            redis.del(lockKey);
        }
    } else {
        Thread.sleep(50);
        cache = redis.get(key);           // 等锁释放后重试
    }
}
```

### 缓存雪崩

**现象**：大量 key **同时过期**或 Redis 宕机，请求全部打到 DB，DB 崩溃。

**解决**：
1. **随机 TTL**：TTL 基础值 + 随机偏移（如 30min ± 5min），避免集体过期
2. **Redis 高可用**：主从 + 哨兵 / 集群，防单点故障
3. **服务降级熔断**：DB 压力过大时直接返回降级响应，保护系统

---

## 八、分布式锁

### 基于 SETNX 的简单实现

```bash
# 获取锁（NX=不存在才设置，EX=过期时间，原子操作）
SET lock:key unique_value NX EX 30

# 释放锁（Lua 脚本保证原子性：判断是自己的锁才删）
if redis.call("GET", KEYS[1]) == ARGV[1] then
    return redis.call("DEL", KEYS[1])
else
    return 0
end
```

### 注意事项

- **value 必须是唯一值**（UUID），防止误删其他线程的锁
- **过期时间要合理**，防止业务执行时间超过锁超时导致锁提前释放
- **看门狗机制**：Redisson 自动续期（后台线程每 1/3 超时时间续期一次）

### Redisson（推荐生产使用）

```java
RLock lock = redissonClient.getLock("booking:create:" + id);
boolean locked = lock.tryLock(3, 30, TimeUnit.SECONDS); // 等锁3秒，持有30秒
if (locked) {
    try {
        // 业务逻辑
    } finally {
        if (lock.isHeldByCurrentThread()) lock.unlock();
    }
}
```

---

## 九、主从复制

**作用**：数据冗余备份，实现读写分离（主写从读）。

```bash
# 从节点配置
replicaof 主节点IP 6379
```

**全量同步**（首次连接）：主节点 `BGSAVE` 生成 RDB → 发给从节点 → 从节点载入 RDB → 同步期间的命令通过 replication buffer 补发。

**增量同步**（断线重连）：通过 `replid` + `offset` 判断，只同步断线期间缺少的命令。

---

## 十、哨兵模式（Sentinel）

监控主从节点，主节点宕机时**自动故障转移**（选一个从节点升为主节点）。

```bash
# sentinel.conf
sentinel monitor mymaster 127.0.0.1 6379 2  # 至少2个哨兵同意才判定主节点下线
sentinel down-after-milliseconds mymaster 30000  # 30秒无响应判定下线
```

**工作流程**：
1. 哨兵定期发 PING，超时未响应 → **主观下线（SDOWN）**
2. 超过 `quorum` 个哨兵都认为下线 → **客观下线（ODOWN）**
3. 哨兵集群选举 Leader，由 Leader 执行故障转移
4. 从节点中选新主节点（依据：offset 最大的优先）

---

## 十一、集群模式（Cluster）

**作用**：水平分片，突破单机内存限制，支持更高并发。

- 16384 个哈希槽（slot）平均分配给多个主节点
- key 通过 `CRC16(key) % 16384` 决定存到哪个 slot
- 每个主节点可配从节点做高可用

```bash
# 查看 key 属于哪个 slot
CLUSTER KEYSLOT username

# 集群信息
CLUSTER INFO
CLUSTER NODES
```

**注意**：集群模式下，多 key 操作（MGET/MSET）要求 key 在同一 slot，可用 `{tag}` 强制同槽：
```bash
SET {user}:name 张三
SET {user}:age 18    # 两个 key 的 slot 由 {user} 决定，保证同槽
```

---

## 十二、事务与 Lua 脚本

### Redis 事务（MULTI/EXEC）

```bash
MULTI           # 开启事务，后续命令入队
SET k1 v1
SET k2 v2
EXEC            # 执行所有入队命令
DISCARD         # 放弃事务
WATCH key       # 乐观锁（key 被修改则 EXEC 返回 nil，事务失败）
```

**局限**：Redis 事务不支持回滚（执行失败继续执行其他命令），与 MySQL 事务不同。

### Lua 脚本（推荐，原子性更强）

Lua 脚本在 Redis 中**原子执行**，中间不会插入其他命令，适合复杂原子操作。

```bash
# 命令行执行
EVAL "return redis.call('GET', KEYS[1])" 1 mykey

# Spring 中使用（RedisTemplate）
DefaultRedisScript<Long> script = new DefaultRedisScript<>(luaScript, Long.class);
redisTemplate.execute(script, Arrays.asList(key), args);
```

**典型应用**：
- 分布式锁释放（判断 + 删除原子化）
- 抢购库存扣减（判空 + 扣减原子化）
- 限流计数器

---

## 十三、Pipeline（管道）

将多条命令**批量打包**发送，减少网络往返次数，提升吞吐量。

```java
// Jedis Pipeline
Pipeline pipe = jedis.pipelined();
for (int i = 0; i < 1000; i++) {
    pipe.set("key" + i, "val" + i);
}
pipe.sync();  // 一次性发送
```

注意：Pipeline 不是原子的，中途可能有命令失败；Lua 脚本才是原子的。

---

## 十四、面试高频总结

| 问题 | 核心答案 |
|------|---------|
| Redis 为什么快 | 内存操作 + 单线程无锁 + 高效数据结构 + IO 多路复用 |
| Redis vs Memcached | Redis 支持更多数据类型、持久化、集群；Memcached 仅支持字符串 |
| 如何保证缓存一致性 | 先更新 DB，再删除缓存（Cache Aside）；或用 Canal 监听 binlog 同步 |
| BigKey 问题 | 单个 key 对应的 value 过大（List/Hash/Set 元素过多），用 UNLINK 异步删除，拆分大 key |
| HotKey 问题 | 热点 key 请求集中，方案：本地缓存 + Redis + 备份 key 分散（多个副本） |
| 删除策略 | 惰性删除 + 定期删除配合使用 |
| 持久化选型 | 追求性能用 RDB；追求数据完整性用 AOF；推荐 RDB + AOF 混合模式 |
| 缓存三大问题 | 穿透（空值缓存/布隆过滤器）、击穿（互斥锁/逻辑过期）、雪崩（随机TTL/高可用） |
