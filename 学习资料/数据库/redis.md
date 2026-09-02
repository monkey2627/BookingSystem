# Redis 系统笔记

---

## 一、定义与特点

Redis 是**基于内存**的 key-value 结构数据库，读写性能极高（单线程 10 万+ QPS）。

### 为什么这么快？

1. **纯内存操作**：数据全部在内存，无磁盘随机 IO
2. **单线程模型**：命令执行串行，无锁竞争（网络 IO 多路复用）
3. **高效数据结构**：跳表、压缩列表、哈希表等针对场景优化
4. **非阻塞 IO 多路复用**：epoll/kqueue 同时监听大量连接，IO 不阻塞主线程

> 单线程指**命令处理线程**是单线程；网络 IO、持久化是多线程。
### Redis说的「内存」到底是什么
这里的**内存 = 计算机的 RAM，主存，内存条**，不是硬盘上的文件夹、不是磁盘文件。

> 通俗区分：
- **内存(RAM)：断电数据直接消失；程序运行时才拥有，打开电脑文件夹看不见。**
- **磁盘(硬盘 SSD/HDD)：就是你电脑文件管理器看到的文件夹、文件；断电数据保留。**
#### 问题1：持久化之前，本地文件夹看不到数据文件？
**对。**
Redis刚启动，你还没开RDB、AOF持久化的时候：
- 所有 `set key val` 写进去的数据，**只呆在内存条里**；
- 你去Redis安装目录、任意文件夹，找不到任何保存你的key‑value的文件；
- 此时：**关闭Redis进程 / 服务器断电，全部数据直接丢失。**

持久化（RDB/AOF）做的事情，就是：把内存里的全部数据，dump一份写到**磁盘上的文件**（`dump.rdb` / `appendonly.aof`）。
只有做了持久化，硬盘上才会出现数据文件。

> 注意：就算开启持久化，**正常读写命令依然只操作内存**。写RDB/AOF是后台异步动作，不是你执行set的时候立刻写磁盘。

#### 问题2：再把「Redis内存」讲透
操作系统给Redis进程分配一块内存空间，就是这块RAM。
Redis的所有 dict、跳表、quicklist、各种key‑value全部存在这块进程内存里。

```
物理硬件：内存条(RAM)
        ↓
操作系统虚拟内存
        ↓
Redis进程的地址空间（这就是Redis口中的内存）
    ├─ redisObject对象、dict哈希表、skiplist跳表……所有业务数据
    ├─ 客户端缓冲区、复制缓冲区
    └─ 等等
```

#### 举个完整生命周期例子
1. 启动redis，**没开持久化**。此时硬盘无任何数据文件。
2. `set name duan`：数据写到**内存条（进程内存）**，文件夹看不到任何新增文件。
3. 此时 kill redis进程：内存条断电释放，数据彻底没了。
4. 开启RDB，触发快照。Redis fork子进程，把内存全部key‑value序列化，生成 `dump.rdb`，存到磁盘文件夹。**现在文件夹能看到这个文件。**
5. 就算有dump.rdb，你继续执行 `set age 24`，新数据依然先写内存，不会立刻修改rdb。rdb只是某个时间点的快照。
6. Redis重启：读取硬盘的 `dump.rdb`，把数据重新加载进内存条，rdb读完就放一边，后续读写继续操作内存。

#### 回答你最初那个Redis为什么快第一条再复盘
> 纯内存操作：数据全部在内存，无磁盘随机IO

意思：**每一次get/set，CPU直接访问内存条，不去硬盘找文件。**
磁盘文件（rdb/aof）只用于备份、重启恢复，业务读写路径完全不走磁盘。

---

### 单线程模型：命令执行串行，无锁竞争
这里的单线程特指**执行Redis命令的 主线程**
1. 所有不同客户端发来的增删改查命令，全部丢到同一个任务队列，主线程逐个串行执行；
2. 同一个时刻只会执行一条命令，**不存在多个线程同时修改同一个Redis数据结构**，天然不需要加互斥锁。

#### 好处
- 省去大量锁竞争、加锁解锁、线程上下文切换的CPU开销；
- 不用处理并发修改带来的各种线程安全问题。

#### 短板
- 如果某一条命令执行时间极长（比如 `keys *`、超大hash全量遍历），会阻塞整个命令主线程，后面所有客户端命令全部排队等待。
> 所以Redis禁止使用慢命令，要避免主线程阻塞。

> 网络接收、发送不是靠这个单线程死等，靠下面的多路复用。持久化、异步删除是额外后台线程。
#### 同样需解决并发问题
两个客户端同时改同一个key，执行顺序怎么定？
核心结论：
> Redis 命令主线程**串行排队执行**，顺序**不是网络发包发出的时间，是事件到达 Redis 服务端、被 epoll 就绪读到的顺序**。
并不是“客户端A先调用set，就一定先执行”。

两个客户端 ClientA、ClientB，几乎同时发：
```
ClientA: set x 100
ClientB: set x 200
```

1. 两个客户端各自在本机调用socket send，把命令丢出去。网络有延迟，TCP报文在网络上跑。
2. 报文先后到达 Redis 服务器操作系统内核 socket缓冲区。
3. **epoll 检测哪些socket有数据可读**。哪一个socket先就绪，主线程就先读取这个客户端的完整命令。
4. 读到一条完整命令，丢给执行引擎执行；执行完毕，再取下一个就绪socket的命令。

5. 时序对比
- ClientA 代码先执行 send()，但是网络延迟大，数据包晚到服务端；
- ClientB 代码后执行 send()，网络链路快，数据包先抵达Redis机器内核缓冲区。
  👉 Redis 会**先执行 ClientB 的 set x 200，再执行 ClientA set x 100**。
  最终 x = 100。
  虽然A客户端先发，但是服务端后收到，就后执行。

##### TCP层面的补充：同一个客户端多条命令的顺序保证
TCP是字节流，**同一个客户端连接内，命令一定按发送顺序执行**。
比如同一个客户端连续发送：
```
set x 1
set x 2
```
Redis读到之后一定按1→2执行，不会乱序。

##### **不同客户端之间没有任何时序保证**，只看服务端epoll读到命令的先后。
这里要区分两件事：
1. **单条Redis命令内部是原子的**
   `set key value`、`incr key`，一条命令完整执行完，才会执行下一条。不会出现命令执行一半切到另一个客户端。✅没有内部竞争。

2. **多条命令组合不是原子！这是坑**
   比如客户端做逻辑：
```java
// 客户端1
int val = get x;
val = val + 1;
set x val;

// 客户端2几乎同时执行一模一样三段代码
```
虽然Redis内部每条命令串行，但是**两个客户端的多条命令会交叉穿插**。

时序示例：
1. Client1 get x → 返回0
2. Client2 get x → 返回0
3. Client1 set x 1
4. Client2 set x 1

最终结果是1，而不是预期2。
> Redis服务端单线程只能保证**单条命令不被打断**，管不到客户端把多条命令拆开的业务逻辑。

解决手段：
- Lua脚本：把多段逻辑打包成一个命令，服务端整体串行执行，中间不会插入别的客户端命令。
- Transaction（MULTI/EXEC）事务，也是把一批命令排队，一次性连续执行。
- Redis 提供的原子指令：`incr`、`hincrby`等。

### 高效数据结构：跳表、压缩列表、哈希表等针对场景优化
Redis 没有直接复用C语言标准库简陋的数据结构，自己实现了一套高度优化的底层结构：
1. **哈希表 dict**：实现 String、Hash，渐进式rehash，扩容不瞬间阻塞主线程；
2. **跳表 skiplist**：用于Sorted‑Set，支持O(logn)有序范围查询，比平衡树实现简单，CPU开销更低；
3. **压缩列表 ziplist**：小List、小Hash，连续内存存储，减少内存碎片，节省内存；数据量大会自动升级；
4. **快速列表 quicklist**：List底层，ziplist+双向链表结合，兼顾内存节省和查询性能；
5. **整数集合 intset**：存纯数字集合，节省内存。

每一种结构都是针对Redis业务场景做裁剪，时间复杂度可控，内存占用也做优化。

> 注意：对外暴露的是上层对象（String/List/Hash/ZSet），底层会根据元素多少自动切换底层存储结构。

### 非阻塞IO多路复用：epoll同时监听大量连接，IO不阻塞主线程
> Linux下是epoll；macOS是kqueue；Windows是select。

#### 什么问题要解决？
Redis要同时维护成千上万客户端TCP连接。
如果每一个连接开一个线程，线程数量爆炸，上下文切换开销巨大。
如果用阻塞socket，读取一个客户端的时候会卡住，其他客户端完全得不到处理。

多路复用模型工作流程：
1. 主线程只做两件事：**监听socket事件 + 执行命令**
2. epoll把成千上万个TCP连接全部交给内核监听；
3. 内核会告诉你：哪些socket有数据到达，可以读；哪些socket缓冲区可写；
4. 主线程只去处理已经就绪的连接，没有就绪的连接完全不占用CPU；
5. **不会为每一个客户端分配线程**，少量线程就可以管理上万TCP连接。

> 关键点：网络IO是**非阻塞**，等待网络数据这件事交给操作系统内核，Redis主线程不会卡在等待网络上。
> 主线程流程：等待socket就绪 → 读客户端命令 → 执行内存操作 → 写返回结果给客户端，循环往复。

---
### 整体完整运行链路总结
```
大量客户端TCP连接
        ↓
epoll(内核)监听全部socket，只返回就绪连接
        ↓
主线程读取就绪客户端命令 → 【串行执行命令：纯内存操作，无锁】
        ↓
写响应给客户端socket
        ↓
持久化、大key内存释放交给后台子线程/子进程，不阻塞主线程
循环
```

## 二、数据类型-逻辑
> redis是基于键值对(key-value)的非关系型数据库。

> key 都是 `String` 类型；value 有 5 种基本类型。

>5 种（String / List / Hash / Set / ZSet）是**对外逻辑类型（API 层）**，写命令时你感知到的类型；
底层是**encoding 编码（真实内存存储结构）**，
> 同一个上层类型，可以有多种底层实现；Redis 会根据数据多少、内容特征**自动切换底层编码** 

### 1. 逻辑类型-String

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

#### 1.1底层编码方式 embstr vs raw
robj（redisObject） + SDS 的内存布局，分界点：**44字节**

> 注意：44字节指的是**字符串有效内容长度**，不包含SDS自身的`\0`。
> Redis 3.2之后使用 `sdshdr8` 作为小字符串的SDS头，这是44字节分界的来源。
```c
// redisObject 对象头，占 16 字节（64位）
typedef struct redisObject {
    unsigned type:4;
    unsigned encoding:4;
    unsigned lru:24;
    int refcount;
    void *ptr;
} robj;

// sdshdr8 小字符串SDS头
struct sdshdr8 {
    uint8_t len;         // 已用
    uint8_t alloc;       // 分配总大小
    unsigned char flags;
    char buf[];          // 柔性数组，存数据 + '\0'
};
```
- sdshdr8 头部：**3字节** + buf末尾额外1字节 `\0`。

---

##### 1.1.1 embstr 编码：一块连续内存
`encoding = OBJ_ENCODING_EMBSTR`
> **robj 对象头 和 sdshdr8 + buf 在同一块连续内存，一次malloc分配出来。**

内存排布（连续一块）：
```
[ robj(16B) ][ sdshdr8(3B) ][ buf 字符串内容 ][ '\0' ]
↑                                   
ptr 指向这里（sdshdr8.buf的地址）
```

- 只调用**一次malloc**，一次free释放；内存碎片少。
- 限制：Redis分配总块大小不能超过 **64字节**（redis.h宏定义 `OBJ_ENCODING_EMBSTR_SIZE_LIMIT = 64`）
```
robj(16) + sdshdr8(3) + 结尾\0(1) = 20字节
64 − 20 = 44  → 有效字符串最大44字节
```
✅ 字符串长度 ≤44字节：使用 embstr。

> ⚠️ embstr **只读**！
embstr 不支持修改字符串；一旦执行 append、setrange 等修改命令，会**强制转成 raw 编码**。
因为它是一块连续内存，修改SDS要扩容，会破坏整块内存布局。

---

##### 1.1.2. raw 编码：两块独立内存
`encoding = OBJ_ENCODING_RAW`
> robj 对象头 和 SDS 是**两次 malloc，两块不连续内存**。

```
堆内存A：[ robj(16B) ]
           |
           ptr指针
           ↓
堆内存B：[ sdshdr ][ buf ][ '\0' ]
```
- robj 一次分配；SDS单独另外分配。
- 支持修改、扩容；修改SDS只操作SDS这块内存，不碰robj。
- 触发条件：
    1. 字符串有效长度 >44字节；
    2. embstr字符串执行修改命令（append等），强制转为raw。
    3. Redis设计策略：**不做原地修改，直接转raw**。 哪怕字符串只有10字节，只要做append，直接变成raw编码。

---
### 2. 逻辑类型-Hash（哈希/散列）

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

### 3. 逻辑类型-List（列表）

有序、可重复，底层是双向链表，支持从两端快速插入/删除。

**底层编码**：quicklist

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

### 4. 逻辑类型-Set（集合）

无序、不重复，支持集合运算。

**底层编码**：intset（小数据，int）/ hashtable（大数据）

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

### 5. 逻辑类型-ZSet（有序集合）

成员唯一，每个成员关联一个 `score` 分数，按 score 自动排序。

**底层编码**：ziplist（小数据）/ skiplist + hashtable（大数据）

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

## 三、数据类型-底层数据结构
> 底层基础结构：`SDS、双向链表、ziplist压缩列表、哈希表、skiplist跳表、整数集合`。
### 1. SDS 简单动态字符串（Simple Dynamic String）
Redis String 的底层，不是 C 原生 `char*`。
```c
struct sdshdr {
    int len;        // 已使用字节数
    int free;       // 剩余空闲字节
    char buf[];     // 字节数组，末尾'\0'兼容C函数
};
```
**优点**
1. O(1) 获取字符串长度，C字符串需要遍历；
2. 杜绝缓冲区溢出；
3. 减少字符串修改时内存重分配次数；
4. 二进制安全，可以存图片、序列化字节，不以`\0`做结束标记。

> Redis所有字符串相关（key、value字符串）几乎全部使用SDS。

---

### 2. 双向链表 linkedlist
```c
typedef struct listNode {
    struct listNode *prev;
    struct listNode *next;
    void *value;
} listNode;

typedef struct list {
    listNode *head;
    listNode *tail;
    unsigned long len;
    void *(*dup)(void *ptr);
    void (*free)(void *ptr);
    int (*match)(void *ptr, void *key);
} list;
```
- 双向，头尾指针，获取头尾节点 O(1)；
- 带长度计数器；
- 可以存不同类型数据；
- **缺点：内存碎片化，每个节点额外 prev/next 指针开销大**。
> 旧版 Redis List 的底层实现；**新版本 List 不再使用 linkedlist**，改用 `quicklist`。

---

### 3. 压缩列表 ziplist
**连续内存数组，专门为小数据设计，节省内存**，是 Hash、ZSet 在数据量小时的底层编码。


整体内存布局（一块连续内存，从低地址到高地址）：

`zlbytes` | `zltail` | `zllen` | `entry1` | `entry2` | … | `entryN` | `zlend`

> ziplist 是**一整块连续字节数组**，没有指针，全部靠偏移量访问，节约内存。

---

#### 1. zlbytes 【uint32_t，4字节】
**整个 ziplist 的总字节数**，包含自身4字节、zltail、zllen、所有entry、zlend。

- 作用：知道整个压缩列表占多大内存，**内存重分配时直接获取整体大小**；
- 通过 zlbytes，就可以定位到 ziplist 的末尾 `zlend` 的地址：
  `ziplist起始地址 + zlbytes −1` 就是 zlend 的位置。

> 示例：zlbytes=100，表示整个ziplist一共100字节。

---

#### 2. zltail 【uint32_t，4字节】
**偏移量：最后一个 entry 的起始位置距离 ziplist 头部的偏移**。

- 作用：**O(1) 获取尾节点**，不需要遍历全部条目，支持从尾部 pop；
- 使用：`ziplist起始地址 + zltail` → 直接拿到最后一个 entry 的首地址。

> 不是存指针，存的是偏移量，因为是连续内存。

---

#### 3. zllen 【uint16_t，2字节】
**ziplist 里面 entry 的元素个数**。

- 如果 `zllen < 65535`：直接存真实元素数量；
- 如果 `zllen == 65535`：代表**元素超过65535**，此时不能直接读zllen得到数量，**必须完整遍历全部entry统计个数**。

> 只有2字节，最大只能表示65534，65535作为标记值。

---

#### 4. entry1、entry2 … entryN 实际数据条目
每一个 entry 代表列表里的一个元素，每个 entry 三部分：
`prevlen` | `encoding` | `content`

##### ① prevlen：前一个entry的字节长度
记录**上一个 entry 占多少字节**。
- 如果前一个entry长度 < 254：占用**1字节**；
- 如果 ≥254：占用**5字节**；第1字节固定0xFE，后面4字节存真实长度。

> 用途：支持**反向遍历**：当前entry地址 − prevlen = 上一个entry起始地址。
> ⚠️ **连锁更新根源**：当某个entry由1字节prevlen扩张为5字节，自身变大，会导致后一个entry的prevlen也要修改，连锁向后传播。

##### ② encoding：编码，标记content存什么
区分存的是整数，还是字符串，同时标记长度。
- 高2位判断类型：
    - `00`/`01`：短字符串；
    - `10`：长字符串；
    - `11`：存整数。

##### ③ content：真正存储的数据
整数或者字节字符串。

> 第一个 entry 的 prevlen：因为前面没有元素，prevlen=0。

---

#### 5. zlend 【uint8_t，固定1字节，值=0xFF】
压缩列表结束标记，固定字节 `0xff`。

- 不存储任何数据；
- 遍历的时候读到 `0xff` 代表已经走完所有 entry；
- zlbytes 已经包含这1字节。
> 补充：ziplist 现在新版本 Redis 已经很少直接裸用；被封装在 quicklist 的每个节点内部。
---

#### ziplist 用作 Hash 的存储
Hash 使用 ziplist 编码时（`OBJ_ENCODING_ZIPLIST`）：
> **key 占一个 entry，紧接着下一个 entry 就是对应的 value，成对存放。**

内存排布：
```
zlbytes | zltail | zllen | entry(key1) | entry(val1) | entry(key2) | entry(val2) | … | zlend
```
- `zllen` 记录的是 **entry 的总个数**，所以元素个数 = zllen / 2。
- 每一对：`[key entry][value entry]`，严格相邻。

---

##### Redis 读取 Hash‑ziplist 的逻辑伪代码
```c
// 获取 hset field 的值：
1. 从头遍历 ziplist entry，步长=2
2. 取当前 entry 作为 key，对比是否等于目标 field
3. 匹配成功，**下一个 entry 就是 value**
4. 遍历结束没找到，返回不存在
```
> 查询时间复杂度 O(n)，顺着 entry 一个个往后扫。

##### 写入 HSET
1. 遍历 ziplist，查找是否已经存在该 field(key)
2. 如果找到：把**下一个 entry**替换成新 value；（会触发 ziplist 内存重分配，有可能连锁更新）
3. 如果没找到：在 ziplist **尾部追加两个 entry**：先 key，后 value。

> 所以 ziplist 编码的 Hash，field‑value 对是按插入顺序保存在 ziplist 里，**有序**；升级成 dict(hashtable) 之后就无序。

---

##### 同理：ziplist 用作 ZSet 的时候
zset ziplist编码也是成对 entry：
`entry(member1) | entry(score1) | entry(member2) | entry(score2) …`
- member 和 score 两两相邻；
- score 是数字存成 entry 的整数编码；
- **并且 ziplist 内部会按照 score 从小到大维护顺序**，插入的时候要找位置插入，不是简单追加尾部。

> ⚠️ Hash 的 ziplist **不排序**，只保持插入顺序；ZSet 的 ziplist 是按 score 排序存储。

---

##### 触发从 ziplist → hashtable(dict) 的两个阈值（Redis6）
```
hash-max-ziplist-entries 512   // ziplist中entry总数不能超过512（key+value算2个entry）
hash-max-ziplist-value 64      // 单个entry字符串不能超过64字节
```
只要满足任意一个条件，Hash 就会做编码转换：
把所有 `key‑value` entry 对迁移到 `dict`，robj 的 encoding 改为 `OBJ_ENCODING_HT`。

> 举例：200个hash字段，每个字段对应一对entry，总entry=400 <512，还可以用ziplist；加到260个字段，entry=520>512，升级为dict。

---
### 4. 哈希表 dict（字典）
Redis Hash 对象底层，Set 底层也复用 dict。
```c
typedef struct dict {
    dictType *type;
    void *privdata;
    dictht ht[2];       // ht[0]主哈希表；ht[1]用于rehash
    long rehashidx;     // rehash进度，-1表示不在rehash
} dict;

typedef struct dictht {
    dictEntry **table;  // 哈希桶数组
    unsigned long size;
    unsigned long sizemask;
    unsigned long used; // 已有元素数量
} dictht;

typedef struct dictEntry {
    void *key;
    union {
        void *val;
        uint64_t u64;
        int64_t s64;
        double d;
    } v;
    struct dictEntry *next; // 链地址法解决哈希冲突，链表
} dictEntry;
```
#### rehash 扩容缩容
1. 正常使用 `ht[0]`；触发扩容/缩容，分配 `ht[1]`；
2. **渐进式 rehash**：不一次性迁移全部数据；每次增删查改，把 rehashidx 指向的桶迁移到 ht[1]；
3. 全部迁移完成后，`ht[1]`切换为 `ht[0]`，ht[1]清空备用。
4. rehash期间，查询会同时查 ht[0]、ht[1]。

> 负载因子 `used/size > 1` 扩容；负载因子 `<0.1` 缩容。
> 冲突解决：**链地址法**，同一个哈希桶内形成单向链表。

---

### 5. 整数集合 intset
**Set 的底层编码之一，集合全部是整数时使用，连续内存**。
```c
typedef struct intset {
    uint32_t encoding; // INTSET_ENC_INT16 / INT32 / INT64
    uint32_t length;
    int8_t contents[]; // 有序升序存储，二分查找
} intset;
```
- 元素有序，无重复；查找 O(logn)；
- 支持升级：插入一个更大范围数字，整个集合升级编码；**不支持降级**；
- 一旦插入非整数数据，自动转 hashtable(也就是dict)。

---

### 6. 跳表 skiplist
**有序集合 ZSet 的底层实现之一**。
平衡树可以做到 O(logn)，但是树实现复杂，跳表更适合范围遍历。
```c
typedef struct zskiplistNode {
    sds ele;
    double score;
    struct zskiplistNode *backward; // 后退指针，用于倒序遍历
    struct zskiplistLevel {
        struct zskiplistNode *forward;
        unsigned long span; // 到下一个节点跨过多少节点
    } level[]; // 多层索引，每个节点层高随机(1‑32)
} zskiplistNode;

typedef struct zskiplist {
    struct zskiplistNode *header, *tail;
    unsigned long length;
    int level;
} zskiplist;
```
- 每层是有序链表；高层是稀疏索引，底层level[0]包含全部真实数据；
- 层高随机生成；
- 支持按 score 范围查询、排序、范围遍历；时间复杂度 O(logN)；
- ZSet 对象同时保存 `dict*`，存 member‑score 映射，O(1) 根据成员查分数。

> zset 底层同时持有：**跳表 + 哈希字典**。跳表负责范围有序遍历；字典负责根据成员快速拿score。

---

### 7. quicklist（快速列表）—— Redis List 真正底层

> quicklist 是一个双向链表，链表的每个节点是一个 ziplist。
```c
typedef struct quicklist {
    quicklistNode *head;
    quicklistNode *tail;
    unsigned long count; // 总元素数量
    unsigned int len;    // ziplist节点个数
    int fill : 16;       // 每个ziplist大小阈值
    unsigned int compress :16; // LZF压缩深度
} quicklist;

typedef struct quicklistNode {
    struct quicklistNode *prev;
    struct quicklistNode *next;
    unsigned char *zl; // 指向ziplist，或者压缩后的ziplist
    unsigned int sz;   // ziplist字节大小
    unsigned int count :16; // 当前ziplist里面元素个数
    unsigned int encoding :2; // RAW 或者 LZF压缩
    // ...
} quicklistNode;
```
- 把大列表拆成多个小压缩列表 ziplist；ziplist之间用双向链表串联；
- 兼顾内存紧凑和头尾操作性能；还支持 LZF 压缩部分节点，进一步省内存。

---

### 对外API ↔ 底层编码映射
`redisObject` 对象头：
```c
typedef struct redisObject {
    unsigned type:4;        // 对象类型 string/list/hash/set/zset
    unsigned encoding:4;    // 底层实际编码
    unsigned lru:LRU_BITS;
    int refcount;           // 引用计数
    void *ptr;              // 指向底层数据结构
} robj;
```

|对外对象|编码encoding|底层结构|触发条件|
|---|---|---|---|
|String|embstr|SDS（embstr模式，对象头+SDS一块连续内存）|字符串 ≤44字节|
|String|raw|SDS（分开两块内存）|字符串 >44字节|
|List|quicklist|quicklist|**Redis3.2+ 唯一编码**|
|Hash|ziplist|压缩列表|元素少、值小；超过阈值转hashtable|
|Hash|hashtable|dict哈希表|数据量大自动转换|
|Set|intset|整数集合|全部为整数，数量小|
|Set|hashtable|dict哈希表|出现非整数 / 元素变多|
|ZSet|ziplist|压缩列表|元素少、值小|
|ZSet|skiplist|跳表+dict字典|元素多自动升级|

> 转换规则：编码只能从小内存结构升级到复杂结构；**不会自动降级**。

### 四、高频面试点
1. SDS 对比C字符串的四大优势；
2. ziplist 的连锁更新；
3. dict 的渐进式 rehash 原理，为什么不一次性rehash；
4. quicklist为什么取代linkedlist；
5. zset为什么同时需要跳表+字典两套结构；只用跳表行不行；
6. intset升级不降级；
7. 对象编码升级不可逆。

## 四、通用命令

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

## 五、过期策略和内存淘汰

### 过期
Redis 删除过期 key 的两种机制，**同时使用**：
前提：key 设置了过期时间 `expire key seconds`

| 策略 | 触发时机 | 特点 |
|------|---------|------|
| **惰性删除** | 访问 key 时才检查是否过期 | 节省 CPU，但内存不及时释放 |
| **定期删除** | 每隔一段时间（默认100ms）随机抽查一批 key | 平衡 CPU 和内存 |

>定期删除也只是采样，**不能保证所有过期 key 立刻被清理**。
>所以有很多已经过期、又不被访问的 key，依然驻留在内存里占用空间。这是固有问题，可以结合内存淘汰策略解决
---
### 内存淘汰
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

## 七、缓存常见三大问题

### 缓存穿透(透明透过去了，即查没有的)

**现象**：查询**不存在的数据**，每次都穿透到数据库（恶意攻击或 bug）。

**解决**：
1. **空值缓存**：DB 没查到，也缓存 `""` 或 null，设短 TTL（2~5分钟）
2. **布隆过滤器**（Bloom Filter）：请求先过布隆过滤器，不存在的直接拒绝，节省内存，有概率把不存在的判断为存在(hash 冲突)，但不会把存在的判断为不存在
所以能够保证正确性

### 缓存击穿

**现象**：**热点 key 突然过期**，瞬间大量请求打到 DB。

**解决**：
1. **逻辑过期**：key 不设真实过期时间，数据里存过期字段，查到"过期"时异步重建缓存
2. **利用redis实现分布式锁**：缓存失效时只允许一个线程去查 DB 并重建缓存，其余等待
#### 方案1：互斥锁（分布式锁）
> 思想：缓存失效，**只放行一个线程去查库重建缓存，其他线程等待重试**。
```java
String cache = redis.get(key);
if (cache == null) {
    String lockKey = "lock:" + key;
    // SET lock 1 NX EX 30，原子加锁
    String lockValue = UUID.randomUUID().toString(); // 唯一标识本线程锁
    boolean locked = "OK".equals(redis.set(lockKey, lockValue, "NX", "EX", 30));
    if (locked) {
        try {
            cache = db.query(id);
            redis.set(key, cache, 30 * 60);
        } finally {
            // Lua脚本：只有value匹配才删除，防止释放别人的锁
            String script = "if redis.call('get',KEYS[1]) == ARGV[1] then return redis.call('del',KEYS[1]) else return 0 end";
            redis.eval(script, Collections.singletonList(lockKey), lockValue);
        }
    } else {
        // 自旋重试，不要sleep死等；设置最大重试次数防止死循环
        int retry = 0;
        while(cache == null && retry < 10){
            Thread.sleep(50);
            cache = redis.get(key);
            retry++;
        }
    }
}
return cache;
```
> 缺点：大量线程自旋等待，CPU/连接消耗；适合并发不是极端爆炸场景。

#### 方案2：逻辑过期（不设置Redis真实TTL）
> 核心：redis key**永不过期**，value内部存一个逻辑过期时间戳。
> 读到发现逻辑时间过期：**只有一个线程异步更新缓存，旧数据继续返回给用户，不阻塞**。
> ⚠️会短暂返回脏数据，对一致性要求不高场景使用。

存储结构示例：
```json
{
  "data":"业务数据",
  "expireTime":1789000000000
}
```

伪代码：
```java
RedisValue value = redis.get(key);
if(value == null){
    // 缓存不存在直接查库（一般项目启动预加载热点key，尽量不走这里）
    value = db.query(id);
    value.setExpireTime(System.currentTimeMillis() + 30*60*1000L);
    redis.set(key, value);
}
// 判断逻辑过期
if(value.getExpireTime() < System.currentTimeMillis()){
    // 获取互斥锁，成功则开启线程异步刷新；拿不到锁直接返回旧数据
    String lockKey = "lock:"+key;
    boolean ok = redis.set(lockKey, "1", "NX", "EX", 10);
    if(ok){
        // 异步线程重建缓存，主线程不阻塞，直接返回旧数据
        threadPool.submit(() -> {
            try {
                Object dbData = db.query(id);
                RedisValue newVal = new RedisValue(dbData, System.currentTimeMillis()+30*60*1000L);
                redis.set(key, newVal);
            }finally {
                redis.del(lockKey);
            }
        });
    }
}
// 无论是否过期，直接返回当前缓存数据（可能是过期旧数据）
return value.getData();
```
**特点**
1. 不会打大量DB，不会阻塞用户请求；
2. **会返回短暂脏数据**；
3. key不会自动消亡，热点key提前预热加载；冷key不适合。

#### 额外兜底方案：永不过期 + 后台定时刷新
热点key干脆不给TTL，后台定时任务定时刷新缓存；从根源避免key过期。

#### 面试对比：互斥锁 vs 逻辑过期
|方案|互斥锁|逻辑过期|
|---|---|---|
|数据一致性|强，返回最新数据|短暂脏数据|
|用户是否阻塞|会阻塞等待锁|不阻塞，直接返回旧数据|
|DB压力|同一时刻只有1线程查库|同一时刻只有1线程异步查库|
|适用场景|一致性要求高|允许短暂脏数据，超高并发热点key|
|缺点|线程自旋，性能损耗|缓存不会自动淘汰，脏数据窗口|

> 小坑：
> - 逻辑过期**不能处理缓存null的情况**，key必须存在；一般热点key提前预热。
> - 互斥锁不要用简单`del`释放锁，要用Lua比对value再删锁。
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
* 内部已经封装好了拿锁等逻辑
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

### Lua 脚本

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
#### 为什么 Lua 脚本可以保证 Redis 原子性
核心一句话：
> **Redis 执行一段 Lua 脚本的时候，是一个单一执行单元，整个脚本内所有命令，要么全部执行完，要么完全不执行；执行期间不会插入其他客户端的命令。**

Redis 是**单线程模型**（命令执行线程单线程）。
> ⚠️注意：网络IO、持久化是子线程；**处理命令请求是主线程串行执行**。

##### 1. 普通多条命令为什么不原子
比如释放锁错误写法：先 get，判断，再 del，两条独立命令：
```
GET lock:xxx
# 这里！！其他客户端命令可以插队执行
DEL lock:xxx
```
1. 客户端发第一条 `GET`；Redis执行完，把结果返回。
2. Redis 主线程回去处理别的客户端请求，**别的命令可以插在 GET 和 DEL 中间执行**。
3. 再收到客户端的 `DEL`，执行删除。

> GET 和 DEL 中间存在时间窗口，别的请求可以介入，所以不是原子。
> 这就是手写释放锁会误删别人锁的根源。

##### 2. Lua 脚本的执行规则
当你发送 `EVAL` 执行一段 Lua 脚本：
```lua
if redis.call('get',KEYS[1]) == ARGV[1] then
    return redis.call('del',KEYS[1])
else
    return 0
end
```
Redis 的行为：
1. 把**整个脚本当成一个完整任务丢给命令执行主线程**。
2. **脚本运行期间，不会切换处理其他任何客户端命令**。其他客户端命令只能排队等待脚本跑完。
3. 脚本内部所有 `redis.call()` 按顺序全部跑完，才会去处理下一条外部客户端命令。

✅ `GET`、判断逻辑、`DEL` 三者中间**没有别的命令可以插队**，实现原子。

> 不是 Lua 语言本身有事务能力；是 Redis 的执行模型把整个脚本封装成不可打断的执行片。

##### 3. Lua脚本 ≠ Redis事务（MULTI/EXEC）
两者都原子，但有区别，面试高频：

###### Redis事务 MULTI / EXEC
- MULTI：把多条命令放入队列，**不会立刻执行**。
- EXEC：把队列里全部命令一次性串行执行，执行期间不能被打断。
- ❗**事务不支持脚本内逻辑判断！** MULTI队列里面只能存Redis命令，不能做if‑else判断。
> MULTI 只能做批量命令，不能根据上一条命令返回值决定要不要执行下一条。

##### Lua脚本
- 可以写 `if‑else`、变量、循环；**可以根据前面命令的返回值，动态决定后面执行什么命令**。
- 同时整个脚本执行不可被打断。

> 释放锁场景必须要：get拿到值 → 判断相等 → 才del。
> **MULTI做不到这个逻辑，只能用Lua脚本。**

> 注意：**Lua脚本也不支持回滚！**
脚本中间某一条 redis.call 报错，已经执行成功的命令不会撤销。只是不会被其他命令插队，不等于数据库事务ACID的回滚。

Lua脚本两个重要坑（面试常问）
① 脚本不要写耗时操作
脚本执行时阻塞整个Redis，别的全部请求排队。
> 如果Lua里面写死循环、大量keys遍历，Redis整体卡死。
② 脚本超时
配置 `lua-time-limit` 默认5秒。
脚本超过5秒还没跑完：Redis不再接受普通命令，但**不会终止正在跑的Lua脚本**，只能执行 `SCRIPT KILL` / `SHUTDOWN`。
所以业务Lua脚本一定要短小精悍，只做简单判断+少量命令。

> Redis命令执行是单线程串行。Lua脚本提交后，**整个脚本作为一个整体执行，脚本运行期间不会处理其他客户端命令，外部命令无法插队，因此实现原子性**。
> 普通多条命令会在命令间隙被其他请求插入，无法保证原子。
> Redis事务MULTI/EXEC虽然也原子，但不能根据命令返回值做if条件判断；Lua脚本可以写业务逻辑判断，适合分布式锁释放这种场景。
> 注意：Lua脚本原子不代表支持回滚，脚本中途出错已经执行的命令不会撤销；脚本不能写耗时逻辑，否则阻塞整个Redis服务。

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
