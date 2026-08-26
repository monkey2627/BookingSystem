# Elasticsearch 学习笔记

> 本笔记结合 MHP 项目（商家搜索）整理，所有 Java 示例均基于 Spring Boot 3.2 + Spring Data ES 5.x + Elasticsearch 8.x。

---

## 一、为什么需要 ES？正向索引 vs 倒排索引

### 正向索引（MySQL 的存储方式）

MySQL 的 B+ Tree 索引是"正向的"：先找到行，再从行里读字段值。

```
id → 文档内容
1  → "专业妆娘接单，服务北京地区..."
2  → "摄影师，擅长古风汉服..."
```

执行 `SELECT * FROM merchant WHERE intro LIKE '%妆娘%'`：
- **全表扫描**：逐行读取每一条数据，判断 intro 字段是否包含"妆娘"
- **不走索引**：B+ Tree 支持前缀匹配（`LIKE '妆%'`），但通配符开头的 LIKE 无法使用索引
- **大数据量下极慢**：100 万条商家数据，每次搜索都扫描 100 万行

### 倒排索引（ES 的核心）

倒排索引翻转了存储方向：先建立"词→文档列表"的映射。

**第一步：建索引时对文档分词**

```
文档 1："专业妆娘接单"  →  IK 分词 → ["专业", "妆娘", "接单", "专业妆娘"]
文档 2："资深妆娘，擅长动漫角色" → ["资深", "妆娘", "动漫", "角色"]
文档 3："摄影师接外拍"  →  ["摄影师", "外拍"]
```

**第二步：建倒排索引**

```
词条（term）  →  文档 ID 列表（Postings List）
"妆娘"       →  [文档1(词频=1), 文档2(词频=1)]
"接单"       →  [文档1(词频=1)]
"专业"       →  [文档1(词频=1)]
"摄影师"     →  [文档3(词频=1)]
```

**第三步：搜索时**

搜索"妆娘"：
1. 对"妆娘"分词 → 得到词条"妆娘"
2. 在倒排索引（B+ Tree 词典）中查找"妆娘" → O(log N)
3. 取出对应的文档 ID 列表 → [文档1, 文档2]
4. 按照评分排序后返回

**倒排索引的三个组成部分：**

| 组件 | 说明 | 数据结构 |
|------|------|----------|
| **Term（词条）** | 文档分词后的最小单元，如"妆娘" | - |
| **Term Dictionary（词典）** | 所有词条的有序集合 | B+ Tree（查找 O(log N)）|
| **Postings List（倒排表）** | 每个词条对应的文档 ID + 词频 + 位置信息 | 压缩整数列表 |

**搜索的本质**：词典查找（O(log N)) + 文档 ID 取交/并（bool query）

---

## 二、Segment（段）— ES 写入原理的核心

### 为什么需要 Segment？

倒排索引一旦写入磁盘就**永远不可修改**（immutable）。这是刻意的设计：
- 不可变 → 读操作无需加锁，并发性能极高
- 不可变 → 可以安全地被多个线程同时读取

但"不可变"带来了问题：怎么处理文档的更新和删除？

答案是：**按段（Segment）写入，用删除标记处理更新/删除**。

### 写入流程（Buffer → Segment → 磁盘）

```
客户端写入文档
       ↓
[内存 Buffer（索引缓冲区）]   ← 写入速度最快，但断电丢失
       ↓ refresh（默认 1 秒）
[内存 Segment（可搜索）]      ← 此时文档可被搜索到（近实时）
       ↓ flush（触发条件满足时）
[磁盘 Segment 文件]           ← 持久化，但此时才真正安全
       ↓ merge（后台自动）
[合并后的大 Segment]          ← 减少小 Segment 数量，提升查询性能
```

**各阶段详解：**

#### Refresh（内存 → 内存 Segment）
- 时间间隔：默认 1 秒（`index.refresh_interval`）
- 将 Buffer 中的数据写入一个新的 Segment，但 Segment 此时**在内存中**
- 写入 Segment 后，文档立刻可以被搜索（这就是 ES 的"近实时"）
- 为什么是近实时而不是实时？Buffer → Segment 的这 1 秒延迟
- 高写入场景可以关闭 refresh（设为 -1）提升吞吐，批量导入完再恢复

#### Translog（事务日志，类似 MySQL 的 redo log）
```
写入顺序：内存 Buffer → Translog（追加写，极快）→ 磁盘 Segment（异步）
```
- Translog 是一个追加写的日志文件，记录每次写入操作
- **目的**：防止 Buffer 中的数据因服务崩溃丢失
- 恢复时：重放 Translog 中未 flush 的操作，恢复内存状态
- 类比：MySQL 的 redo log 在事务提交时先写，保证崩溃恢复

#### Flush（Segment → 磁盘）
- 触发条件：Translog 达到一定大小（默认 512MB），或距上次 flush 超过 30 分钟
- 将内存中的 Segment 写入磁盘，清空 Translog
- Flush 之后，数据真正持久化，不再需要 Translog 来恢复

#### Merge（段合并）
- 问题：每次 refresh 产生一个新 Segment，长期运行会有很多小 Segment
- 小 Segment 查询效率低（需要查所有 Segment 然后合并结果）
- 后台线程自动把多个小 Segment 合并成一个大 Segment
- 合并时：顺带删除被标记为删除的文档（真正释放空间）

#### 删除和更新是怎么做的？
- **删除**：只是在 `.del` 文件中标记该文档已删除，不修改 Segment
- **更新**：先在 `.del` 标记旧版本已删除，再新增一条记录到 Buffer
- 查询时：命中被删除的文档会被过滤掉
- 真正释放空间：等 merge 时才将标记为删除的文档物理删除

---
# `index.refresh_interval: 1s`
👉 **不是做一次refresh要花1秒钟；是每间隔1秒就执行一次refresh**。

- 含义：**每隔1秒，就把Index Buffer里积攒的数据，执行一轮refresh**。
- refresh本身耗时很短，通常几毫秒就完成。

## 举时间线例子
```
0.0s  写入一批文档，进入Index Buffer
0.005s refresh执行完毕（耗时5ms）→ 生成Segment‑1，buffer清空
1.0s  又到时间点，执行refresh
1.003s refresh执行完毕 → 生成Segment‑2
2.0s  再次到时间点，执行refresh
……
```
> 周期是**调度间隔1s**，不是操作耗时1s。

## 关键细节
1. 如果这1秒内**一条数据都没有写入**：到点依然会跑refresh，但buffer是空的，不会产生新segment，什么都不做。
2. 如果上一次refresh还没执行完，到了1s时间点，**不会并发再启一个refresh**，会跳过本次，等下一个周期。不会出现refresh任务堆积。

## 近实时的来源
> 新写入的数据，最多最多等待接近1秒，就可以被搜索到。
- 刚写完立刻查：还在buffer，看不见。
- 最多等不到1s，定时refresh跑完，生成内存segment，就能搜到。
  这就是ES“近实时搜索(NRT)”的来源，**不是实时，有最多1秒窗口**。

## 三个容易混淆
1. `refresh_interval:1s`：**调度周期，每1秒触发一次refresh任务**。
2. refresh操作本身：毫秒级，把buffer转内存segment。
3. flush：和这个1秒无关，flush由translog大小(512MB)或者30分钟时间控制，负责刷磁盘持久化。

## 实操配置
```yaml
# 每2秒refresh一次，段生成变少，写入吞吐更高，搜索延迟最大2s
index.refresh_interval: 2s

# -1 关闭定时refresh，完全不自动执行，只有手动/_refresh才会生成segment，大批量导入用
index.refresh_interval: -1
```
# 是不是每次新增就创建一个Segment？
**不是每一条文档新建一个段；是每一次 `refresh` 才生成一个新Segment，一次refresh可以包含N条文档。**

> 记住核心公式：
> **一次 refresh → 产出 1 个新 Segment；一次 refresh 可以攒几十/几千条文档。**

## 完整流程回顾
1. 文档写入，先放到 **Index Buffer（内存缓冲区）**，此时还没有segment。
2. 不断往buffer堆多条文档。
3. 触发refresh（默认1秒）：
  - 把buffer里**当前所有积攒的全部文档**，一次性构建成**1个全新Segment**（内存段）
  - buffer清空，继续接收新写入
    👉 不管这次buffer里面是1条、10条、1000条，**一次refresh只生成1个segment**。

> ❌误区：写1条文档 → 1个segment（错误）
> ✅事实：写N条，攒到refresh触发，统一打包成1个segment。

### 什么时候会触发refresh？
1. **定时自动refresh**：`index.refresh_interval: 1s`，每1秒执行一次，把buffer全部数据生成一个segment。
2. **手动调用 refresh API**：`POST /merchant/_refresh`，立刻把buffer全部数据生成segment。
3. 批量API可以手动指定`refresh=true`，请求结束立刻refresh，立刻可见。

```http
POST /merchant/_bulk?refresh=true
{"index":{}}
{"nickname":"妆娘A"}
{"index":{}}
{"nickname":"妆娘B"}
```
> 这一次bulk里面2条数据，会生成**1个segment**，不是2个。

---

## 那什么情况下会疯狂产生大量小segment？
场景1：**高频手动refresh**
每插入1条就带上`refresh=true`。
> 写1条 → refresh → 生成1个segment；再写1条 → refresh → 又一个segment。
> 这样就会出现大量只有1条文档的极小segment，查询性能爆炸变差。

> 生产环境批量导入大量数据做法：
> 1. 设置 `index.refresh_interval: -1` 关闭自动refresh
> 2. 大量bulk写入，不手动refresh
> 3. 全部写完，再恢复refresh_interval，手动refresh一次。
     > 大批量数据只会产生少量segment。

场景2：写入压力巨大，每秒大量数据，每1秒都有新refresh，每秒新增1个segment，日积月累段数量变多，后台merge线程就会启动，把很多小segment合并成大segment。

## 区分 refresh / flush / merge
1. **refresh**：buffer → 内存Segment，**产生新段**，文档可搜索，不落地磁盘。
2. **flush**：内存Segment写到磁盘文件，translog清空，**不会新增segment，只是把已有的内存segment持久化**。
3. **merge**：读取多个旧segment，合并计算，生成**1个全新大segment**，删除旧的一堆小segment。

> merge才会销毁旧segment，生成新segment。refresh只会新增segment，不会删除旧segment。

## 举个例子帮助理解
假设默认1s refresh间隔：
- 0s：写入文档A、B、C，进入buffer
- 1s：触发refresh → buffer里A/B/C生成**Segment‑1**，buffer清空
- 1.5s：写入D、E，进入buffer
- 2s：触发refresh → D/E生成**Segment‑2**，buffer清空

此时shard下有两个segment：Segment‑1（3条），Segment‑2（2条）。
查询的时候ES同时查Segment‑1、Segment‑2，合并两者结果。

后台merge线程过一段时间，把Segment‑1 + Segment‑2合并，生成Segment‑3，之后删掉1、2。shard只剩Segment‑3。

## 回答你原问题总结
1. **新增一条数据，不会立刻创建段**，只是放到内存buffer。
2. 只有refresh发生的时候，buffer里积攒的**全部数据统一生成1个segment**，跟本次有多少条无关。
3. 如果你每写一条就强制refresh，就会出现大量只有1条文档的segment，属于错误用法。
4. update更新操作本质也是新增文档到buffer，等待refresh之后生成到新segment，旧文档打删除标记。

### 补充小问题：Bulk批量写入
bulk请求本身**不会自动触发refresh**，写完数据还在buffer，等待下一次1秒定时refresh才生成segment。
除非你url参数带上`?refresh=true`。

需要我顺带讲下为什么深度分页from/size性能差和segment的关系吗？
### 补充：手动 `?refresh=true`
bulk请求加`refresh=true`：**本次请求结束立刻执行refresh，不等待那1秒定时器**。
适合单元测试；生产高频使用会疯狂产生大量小segment，性能灾难。

> 一句话记忆：**1s是定时器间隔，不是干活耗时。**

# ES Segment（段）结构详解
> 前置记住核心：**Segment 是磁盘上一组不可变的倒排索引文件集合，一次 refresh 生成一个新 Segment；一个索引可以有多个 Segment，查询时会遍历该索引全部 Segment，再合并结果**。
> 一个 Segment 内部包含多组文件，分别存词典、倒排表、文档存储、删除标记等。

## 1. 整体层级关系
```
索引(index: merchant)
├─ 000001_0      # Segment 0（段编号，数字递增）
├─ 000002_0      # Segment 1
└─ _segments     # 段元数据文件，记录该索引下所有Segment列表、状态
```
- `_segments`：索引的元文件，记录当前有多少个段、每个段的版本、是否被合并、文档数量。
- 每个 Segment 是一整套独立完整倒排索引，互相之间完全隔离。
> 查询流程：搜索 `merchant`索引 → 读取`_segments`拿到全部segment列表 → **并行查询每一个segment** → 把多个segment返回的文档ID、评分做归并排序，返回最终结果。
> 这就是小segment过多查询变慢的根源：segment越多，需要查询的子索引就越多。

## 2. 一个Segment内部的文件结构
每个Segment对应一批磁盘文件，后缀代表不同用途，以段名`_0`举例：

| 文件后缀 | 文件作用 | 存储内容 |
|---|---|---|
| `.fdt` | 文档存储文件 | 原始文档数据（各个字段原始值，`_source`来源） |
| `.fdx` | 文档索引 | 记录每个docId在`.fdt`中的偏移位置，快速定位文档 |
| `.tim` | Term词典 | term字典（B+树结构，词条有序，用于快速查找term） |
| `.tip` | Term词典索引 | tim的索引，加速term查找 |
| `.doc` | Postings倒排表 | term对应的文档id、词频、位置、偏移（核心搜索数据） |
| `.pos` | 词条位置 | 词在文档中出现的位置，用于`match_phrase`短语搜索 |
| `.pay` | 偏移量 | 高亮需要的字符偏移信息 |
| `.dvd` / `.dvm` | 文档值 | doc_values，排序、聚合用的列存数据 |
| `.del` | 删除标记文件 | 记录哪些docId已经被删除/更新，**不会修改原segment数据** |

> 关键点：
> 1. segment一旦生成，上面这些文件**只读，绝不修改**。更新删除不会改动旧segment的任何文件。
> 2. `.del`不属于segment本体，是附加标记；查询命中文档后，会检查`.del`，如果标记删除就过滤掉这条结果。
> 3. 只有**段合并merge的时候**，才会真正物理丢弃被删除的文档，生成全新segment。

## 3. 写入时Segment生命周期完整拆解
```
写入文档 → IndexBuffer（内存缓冲区）
    ↓ refresh(1s)
1. 把buffer的数据构建成**内存Segment**（全部在内存，磁盘还没有文件）
    - 此时已经构建完整倒排索引，可以被搜索（近实时）
    - 旧buffer清空，接收新写入
    ↓ flush触发
2. 将内存Segment全部序列化写入磁盘，生成上面一整套`.tim/.doc/.fdt`等磁盘文件
    - translog日志被清空
    ↓ 后台merge线程
3. 挑选多个小segment，读取全部有效文档（跳过.del标记删除的），重新构建一份全新大segment，写入磁盘
4. 新segment写入成功后，旧的多个小segment文件被物理删除；更新`_segments`元数据，切换到新segment。
```

### 更新文档是如何借助Segment实现
1. 原有文档存在于老SegmentA，**SegmentA完全不动**；
2. 在新的内存SegmentB写入新版本文档；
3. 在`.del`标记SegmentA里面的旧docId已经删除；
4. 查询时，A命中旧文档，发现被标记删除，直接丢弃；B命中新版本文档返回；
5. 等到merge阶段，A中被标记删除的文档直接丢弃，只保留有效数据，生成新segment。

> 所以ES更新不是原地修改，是**写新的，标记旧的作废**。

## 4. 为什么segment多会查询变慢？
假设现在索引有 100 个小segment：
1. 查询需要打开100套独立倒排索引，分别执行term查找，拿到100组文档id列表；
2. 对100组结果做归并排序，合并打分，过滤删除标记；
   segment数量越多，IO、内存、归并排序开销越大。
> merge后台任务就是用来把一堆小segment合并成大segment，减少段数量，优化查询性能。
> 但merge非常消耗CPU和IO，ES会控制merge的并发，避免打满机器资源。

## 5. 容易混淆的概念澄清
1. **Segment vs Shard分片**
  - Shard：逻辑分片，一个索引分为多个shard，shard分布在不同节点；
  - 每个shard内部，由若干个segment组成；
   > `索引 → shard分片 → 每个shard内部有多个segment`
2. refresh只是生成内存segment，**不刷磁盘**；flush才把segment持久化磁盘。
3. translog是独立日志，不属于segment，作用是内存数据断电保护。

## 6. Kibana查看segment信息
```http
GET /merchant/_segments
```
返回结果可以看到每个shard下面所有segment：段名称、文档数、删除文档数、内存大小、是否正在merge。
# 什么叫 Segment 是一套「完整的子索引」
一句话：
> **Segment 本身就相当于一个微型、独立的小 ES 索引。它自己内部：有文档、有词典、有倒排表、有排序聚合用的doc_values。只属于当前这一批文档，可以独立完成搜索。**

> 不是一个大倒排索引被切分成很多块；而是**每个段都单独做了一份完整倒排索引**。

举个现实例子：
我们往 ES 写入4条商家文档，refresh_interval=1s。

- t=0s：写入文档1、文档2，进入buffer
- t=1s：触发refresh → 生成 **Segment‑A**，包含文档1、2

Segment‑A 内部自己生成属于【文档1、2】的整套倒排：
```
# Segment‑A 自己的词典+倒排表（只看1、2）
"妆娘"  → [doc1, doc2]
"接单"  → [doc1]
"古风"  → [doc2]
```
同时 Segment‑A 存下 doc1、doc2 的原始`_source`数据。

---

- t=2s：写入文档3、文档4，进入buffer
- t=2s：refresh触发 → 生成 **Segment‑B**，包含文档3、4

Segment‑B 是另一套独立完整子索引，只针对文档3、4：
```
# Segment‑B 的词典+倒排表（只看3、4）
"摄影师" → [doc3]
"外拍"   → [doc3]
"妆娘"   → [doc4]
"cos"    → [doc4]
```
Segment‑B 也存下 doc3、doc4 的原始`_source`。

现在，**shard分片下面同时存在两套独立子索引 Segment‑A、Segment‑B**。
> ⚠️重点：没有一个全局统一的大倒排表！
> `"妆娘"`这个词条，A里面有一份，B里面又单独有一份，两者互不干涉。

## 当执行搜索：搜关键词「妆娘」发生什么
1. ES拿到shard下所有segment：Segment‑A、Segment‑B
2. **分别在每个Segment内部独立搜索**
  - 在 Segment‑A 的内部词典查找`妆娘` → 拿到文档 [1,2]，同时算出BM25分数
  - 在 Segment‑B 的内部词典查找`妆娘` → 拿到文档 [4]，算出BM25分数
3. 把两段返回的结果集合 `[1,2]` 和 `[4]` 做归并，合并打分排序，过滤掉`.del`标记删除的文档，返回最终 `[1,2,4]`。

> 相当于：**同时查询两个微型小索引，最后把结果拼在一起。**

## 段合并 merge 做了什么
后台merge线程读取A、B两个segment全部有效文档：doc1,doc2,doc3,doc4。
把这4条文档全部拿出来，**重新完整构建一套全新的倒排索引，生成 Segment‑C**。

Segment‑C（新的子索引）
```
"妆娘"   → [doc1, doc2, doc4]
"接单"   → [doc1]
"古风"   → [doc2]
"摄影师" → [doc3]
"外拍"   → [doc3]
"cos"    → [doc4]
```
生成C成功之后，就把旧的 Segment‑A、Segment‑B 的磁盘文件直接删掉。
此时分片下只有 Segment‑C，搜索只需要查这一套子索引，性能更高。

## 模拟更新场景，加深理解
现在更新文档2：
1. Segment‑A 是只读的，完全不动，A里面的doc2保留原样。
2. 更新请求把新版本doc2写入buffer，下一次refresh，生成 Segment‑D。
3. 在`.del`删除标记中标记 Segment‑A 内的doc2已经被删除。

现在分片有 A、B、D三个segment。
搜索的时候：
- 查询A得到doc1、doc2；发现doc2被标记删除，丢弃doc2，只保留doc1。
- 查询B得到doc3、doc4。
- 查询D得到新版本doc2。

合并结果得到：doc1，doc3，doc4，新版doc2。
> 旧数据还躺在A中，直到merge的时候，才物理丢弃A中被标记删除的doc2。

## 核心总结
1. 所谓**完整子索引**：每个segment拥有自己独立的term词典、倒排表、文档存储，**自己就可以独立完成一次搜索**，不需要依赖别的segment。
2. 词条不会跨段合并，同一个term（妆娘），每个segment都维护自己一份postings列表。
3. 查询 = 并行查询分片下每一个segment子索引，归并全部结果。
4. merge本质：读取多个子索引全部有效文档，重新生成一个全新完整子索引，销毁旧的。

> 对比MySQL：MySQL一张表就一套B+树索引；ES一个shard下有多套独立小索引（segment）。

## 容易踩坑的点
segment越多，就要执行越多的子索引查询，结果归并开销越大。
这就是为什么大量小segment会拖慢查询性能。

要不要我顺带解释下，为什么这个segment机制，会造成ES深度分页from+size性能差？
可以这么理解，但要把层级再精确一点，修正两处小细节：

> ✅大方向：**分片(shard)由多个Segment（段）组成；Segment里面包含文档数据 + 这套段自己完整的倒排索引**
> ⚠️注意：**索引(index) ≠ 直接由段组成，索引由若干分片(shard)组成，每个分片内部才是由段构成**

完整层级（从大到小）
```
索引(index)
├─ 主分片0（shard‑0）
│   ├─ Segment_0
│   └─ Segment_1
└─ 主分片1（shard‑1）
    ├─ Segment_A
    └─ Segment_B
```

---

## Segment内部有什么？
一个Segment = 一整套独立、完整的子索引：
1. **文档原始数据**（`.fdt/.fdx`，存储`_source`原始json）
2. **本segment专属的倒排索引全套文件**
  - term词典(tim/tip)
  - postings倒排表(doc)
  - 词位置(pos)
  - doc_values排序聚合列存(dvd/dvm)
3. 附加删除标记文件`.del`（标记本段哪些文档逻辑删除，**不会修改段本身文件**）

> 👉每个Segment都拥有自己独立完整的倒排索引，段与段之间完全隔离，互不共享词典。
> 查询的时候，要把shard下**每一个segment分别查询**，再把结果归并、合并打分、过滤删除文档。

### 纠正你原话的两处小偏差
1. “索引是由段组成” → **索引由分片组成；分片由段组成**。一个索引可以跨机器分到多个shard，每个shard各自维护自己的segment列表。
2. “段是由文档及其倒排索引组成” → ✔这个是对的。segment内部保存该段内所有文档，以及针对这批文档生成的专属倒排索引。

---

## 结合写入流程再串一遍
1. 写入文档，落到某个shard的Index Buffer；
2. refresh触发：buffer中的一批文档，构建出**一个全新Segment**，里面生成这批文档对应的倒排索引；
3. flush把segment持久化磁盘；
4. merge把多个小segment读取里面有效文档（过滤`.del`删除项），重新构建一个新大segment（重新生成一套全新倒排索引），旧段删除。

> 关键点：**每个segment的倒排索引只属于自己，只覆盖本segment里面的文档，不会跨段。**

### 极简记忆口诀
> 索引 → 分片 → 多个段
> 每一个段 = 一批文档 + 该批文档独立完整的倒排索引

如果你愿意，我可以画一个极简的对比：MySQL（表→页→行） vs ES（索引→shard→segment→文档），方便对比记忆。
# Segment 机制为什么导致 from + size 深度分页性能差
> 前置回忆：
> 1. 一个shard分片存在多个segment，**每个segment是独立完整子索引**；
> 2. 查询时，**每个segment都要独立完成搜索、打分、排序，再把所有segment的结果归并，再做全局排序**。

举个例子：
集群配置：`number_of_shards:3`，3个主分片。
需求：`from=1000，size=10`，要拿**第1001‑1010条数据**。

## 第一步：ES的执行逻辑
ES并不知道哪10条是全局的1001~1010，**它不能直接跳过前面1000条**。
因为每个shard的segment是独立的，每个段的`_score`打分只在本段内部有效，**一个segment的score不能直接和另一个segment的score做比较**。

执行步骤：
1. **每个shard内部，向它下面所有segment查询**：要求返回 `from + size = 1010` 条最高分文档。
  - shard‑0：遍历自己全部segment，每个segment算出本段内top‑1010，归并得到shard本地top‑1010
  - shard‑1：同样算出本地top‑1010
  - shard‑2：同样算出本地top‑1010
2. 协调节点（coordinate node）拿到3份，每份1010条，一共 3 × 1010 = **3030条文档**。
3. 在协调节点内存里，对全部3030条做**全局排序**。
4. 丢弃前面1000条，截取后面10条返回给用户。

> 关键点：
> `from=1000 size=10`，不是只查10条；每个分片都要拉取 `from+size` 条，协调节点收集、排序、丢弃前面from条。
> from越大，每个分片要返回的数据越多，协调节点内存压力、CPU排序开销暴涨。

## 再叠加segment多的情况，雪上加霜
假设某shard下面有20个小segment：
为了拿到shard本地top‑1010：
- 需要对**20个segment分别执行完整搜索打分**，每个segment输出本段top‑1010；
- 然后在shard内部，把20组1010条结果归并排序，得到shard的top‑1010。

segment数量越多，分片本地归并排序的代价就越大。
> 小segment不仅影响查询，还会放大深度分页的性能损耗。

## ES默认限制：`index.max_result_window = 10000`
默认 `from + size ≤ 10000`。
也就是 `from=9990，size=10` 是允许的；`from=10000，size=10`直接抛异常。
目的就是防止用户写超大from，把协调节点内存打崩。

## 两种解决方案
### 方案1：search_after（游标分页，项目里面用的）
不使用from，拿上一页最后一条文档的排序值，作为下一页的游标。
示例：
```json
GET /merchant/_search
{
  "size":10,
  "search_after":[4.8, 16888],
  "sort": [{"avgScore":"desc"},{"_id":"asc"}]
}
```
执行逻辑：
1. 每个shard、每个segment，**直接从search_after游标位置开始读取，不需要读取前面的数据，不需要丢弃大量文档**。
2. 协调节点只聚合每个shard返回的size条，不需要加载海量中间数据。

> ✅适合滚动翻页（往下一页、下一页），不适合跳页（直接跳到第1000页）。
> 本项目MHP商家搜索，`CursorPageVO`底层就是封装search_after。

> 注意：sort字段必须保证**全局唯一**，一般业务字段 + `_id`组合，防止排序值重复，游标定位错乱。

### 方案2：scroll滚动查询
scroll会生成一个快照，保存本次查询的segment视图，一页一页拉。
缺点：快照会占用资源，实时性差，不适合高并发线上业务，适合后台大批量导出数据。

## 对比总结
| 分页方式 | 原理 | 优点 | 缺点 |适用场景 |
|---|---|---|---|---|
| from/size | 各分片拉取from+size，协调节点全局排序丢弃前面 | 支持随意跳页 | from大性能爆炸，受max_result_window限制 |浅分页，前几十页 |
| search_after | 根据排序游标向后读取 | 性能稳定，不受10000限制 |**不能跳页，只能顺序往下翻** |APP/网页滚动加载（本项目） |
| scroll |建立索引快照，批量迭代拉取 |适合大批量导出 |占用内存，快照非实时，不适合用户搜索 |后台数据导出，初始化全量同步 |

## 再串联一遍完整因果链（面试可以直接背）
1. ES一个shard由多个不可变segment组成，每个segment是独立子索引，打分只在段内有效。
2. from+size深度分页，每个shard都要查询所有segment，取出`from+size`条。
3. 协调节点收集所有分片结果，在内存做全局排序，丢弃前面from条。
4. from越大，传输、内存、排序开销越大；segment越多，分片本地归并开销进一步放大。
5. 所以ES不建议深度分页，线上业务优先使用`search_after`游标分页。

> 面试高频追问：search_after为什么不能跳页？
> 因为它没有保存全量的排序结果，只有游标，只能向后遍历，不能随机跳到任意页码。
# 关键点
**集群里可以有很多个索引，每个索引的主分片数量互相独立，不需要一样。**
> 集群是一堆机器；**分片数是属于索引自己的配置，不是集群全局配置**。

举例子：
集群有3台机器，同时存在3个索引：
1. `merchant`（商家，数据量大）：创建时设置 `number_of_shards:3`
2. `order`（订单，中等数据）：创建时设置 `number_of_shards:2`
3. `log`（日志，量很小）：创建时设置 `number_of_shards:1`

✅完全合法。
- merchant：3个主分片；
- order：2个主分片；
- log：1个主分片。

> 7.0以后ES**索引默认主分片数 =1**，不是5了。
> 你创建每个索引的时候，可以在PUT index的settings里，单独指定该索引自己的主分片数。

```http
PUT /merchant
{
  "settings":{
    "number_of_shards":3,
    "number_of_replicas":1
  }
}

PUT /order
{
  "settings":{
    "number_of_shards":2,
    "number_of_replicas":1
  }
}
```

## 容易混淆的两个概念
1. ❌“集群全局设置主分片数量，所有索引都继承这个数字”
> ES**没有集群全局的主分片数**。每个索引创建的时候自己决定，一旦建好就不能改主分片数。

2. ✅索引模板 index_template
   可以预先写模板，匹配名字符合 `logs-*` 的索引，自动套用模板的分片配置。
> 这只是创建新索引时自动帮你填参数，**不是集群全局强制，旧索引不受模板影响**。

## 回到之前深度分页的例子
之前举例说 `number_of_shards:3`，指的是**merchant这个索引自己有3个主分片**，不是集群所有索引都必须3个。
查询`merchant`，才会访问它自己的3个主分片；查询`order`，只会访问它自己的2个主分片。

### 完整层级再捋一遍（背诵版）
```
ES集群（一堆机器节点）
├─索引A merchant（自己配置：3主分片）
│  ├─主分片0 → 多个segment
│  ├─主分片1 → 多个segment
│  └─主分片2 → 多个segment
├─索引B order（自己配置：2主分片）
│  ├─主分片0 → 多个segment
│  └─主分片1 → 多个segment
└─索引C log（自己配置：1主分片）
   └─主分片0 → 多个segment
```

> 一句话记忆：**索引A的分片归A管；索引B的分片归B管，互不干涉。分片数是每个索引的私有属性。**

## 面试高频坑
> 问：一个ES集群所有索引主分片数必须相同？
答：错。每个索引创建时独立指定主分片数，互不影响；主分片数只对当前索引生效，创建后不可修改。副本数每个索引也可以不一样。

### 顺带区分路由公式
`shard = hash(_id) % number_of_primary_shards`
👉这里取模用的，**是当前被写入的那个索引自己的主分片数，不是集群别的索引的**。
往merchant写文档，就%3；往order写文档，就%2。

## 三、BM25 评分算法

ES 的相关度评分用 **BM25**（BM = Best Match，25 是第 25 次迭代）。

### 为什么不用 TF-IDF？

TF-IDF 的问题：TF 是线性的，"妆娘"出现 100 次的文档比出现 2 次的评分高 50 倍，但实际上用户对"妆娘"出现 100 次的文章并不一定更感兴趣。

BM25 对 TF 做了饱和处理：TF 增长但边际效益递减。

### BM25 公式（简化版）

```
score(D, Q) = Σ  IDF(qi) × TF_saturation(qi, D)
              词条qi

TF_saturation = tf(qi,D) × (k1 + 1)
                ─────────────────────────────
                tf(qi,D) + k1 × (1 - b + b × |D|/avgdl)

其中：
  tf(qi,D)  = 词条 qi 在文档 D 中出现的次数
  |D|       = 文档 D 的长度（词条总数）
  avgdl     = 所有文档的平均长度
  k1        = 词频饱和参数（默认 1.2）
  b         = 长度归一化参数（默认 0.75）
  IDF(qi)   = log(1 + (N - n(qi) + 0.5) / (n(qi) + 0.5))
              N = 总文档数，n(qi) = 包含 qi 的文档数
```

### 直观理解

**TF（词频）**：词条在文档中出现越多，该文档越相关。但 BM25 加了饱和：出现 5 次和出现 100 次差不多，不是线性的。

**IDF（逆文档频率）**：在越少文档中出现的词条越重要。"妆娘"在少数商家简介中出现 → IDF 高 → 搜到了很有价值。"的"字每个文档都有 → IDF 极低 → 基本不影响评分（这就是停止词的效果）。

**长度归一化（b 参数）**：长简介的商家自然包含更多词，如果不归一化，简介越长越占便宜。b=0.75 意味着对文档长度做 75% 的归一化。

这就是 Kibana 的 `explain=true` 里 `freq`、`k1`、`b`、`dl`、`avgdl` 这些参数的来源（你图1截图里展示的就是 BM25 的计算过程）。

---

## 四、DSL 查询语法（HTTP 接口）

ES 通过 RESTful + JSON（DSL）操作，所有操作都是 HTTP 请求。

### 索引库操作

```bash
# 创建索引
PUT /merchant
{
  "settings": {
    "analysis": { "analyzer": { ... } }
  },
  "mappings": {
    "properties": {
      "nickname": { "type": "text", "analyzer": "ik_max_word" },
      "city": { "type": "keyword" }
    }
  }
}

# 查看索引 mapping
GET /merchant/_mapping

# 查看索引 settings
GET /merchant/_settings

# 删除索引（危险！不可恢复）
DELETE /merchant

# 不能修改已有字段 type，只能新增字段
PUT /merchant/_mapping
{
  "properties": {
    "new_field": { "type": "keyword" }
  }
}
```

### 文档操作

```bash
# 新增（id 由 ES 自动生成）
POST /merchant/_doc
{ "nickname": "专业妆娘", "city": "北京" }

# 新增（指定 id）
PUT /merchant/_doc/1
{ "nickname": "专业妆娘", "city": "北京" }

# 查询
GET /merchant/_doc/1

# 全量替换（不存在则新增）
PUT /merchant/_doc/1
{ "nickname": "改名了", "city": "上海" }

# 局部更新（只更新指定字段）
POST /merchant/_update/1
{ "doc": { "city": "上海" } }

# 删除
DELETE /merchant/_doc/1
```

### 查询类型详解

#### 全文搜索（Text 字段用）

```bash
# match：分词后搜索，"妆娘摄影"→["妆娘","摄影"]，文档包含任一即可
GET /merchant/_search
{
  "query": { "match": { "intro": "妆娘摄影" } }
}

# match_phrase：短语匹配，词必须连续出现且顺序一致
GET /merchant/_search
{
  "query": { "match_phrase": { "intro": "专业妆娘" } }
}

# multi_match：在多个字段搜索，取最高分
GET /merchant/_search
{
  "query": {
    "multi_match": {
      "query": "妆娘",
      "fields": ["nickname^2", "intro"]
    }
  }
}
# nickname^2 表示 nickname 字段的评分乘以 2

# match_all：匹配所有文档，_score 均为 1.0
GET /merchant/_search
{
  "query": { "match_all": {} }
}
```

#### 精确匹配（Keyword/数值字段用）

```bash
# term：精确匹配单个值（keyword 字段）
GET /merchant/_search
{
  "query": { "term": { "city": { "value": "北京" } } }
}

# terms：精确匹配多个值（OR 关系）
GET /merchant/_search
{
  "query": { "terms": { "city": ["北京", "上海"] } }
}

# range：范围查询（数值/日期）
GET /merchant/_search
{
  "query": {
    "range": {
      "avgScore": { "gte": 4.0, "lte": 5.0 }
    }
  }
}
```

#### bool 复合查询（最常用）

```bash
GET /merchant/_search
{
  "query": {
    "bool": {
      "must":     [...],   # 必须匹配，影响 _score（AND）
      "should":   [...],   # 至少匹配一个，影响 _score（OR）
      "filter":   [...],   # 必须匹配，不影响 _score（AND，有缓存，性能好）
      "must_not": [...]    # 必须不匹配，不影响 _score
    }
  }
}
```

**must vs filter 的区别**：
- `must`：参与 BM25 评分计算，慢一点
- `filter`：只判断是否匹配，结果被 ES 缓存，快很多
- **规律**：keyword 字段的精确筛选放 filter，全文搜索放 must

#### function_score（业务评分加权）

```bash
GET /merchant/_search
{
  "query": {
    "function_score": {
      "query": { "bool": { ... } },
      "functions": [
        {
          "field_value_factor": {
            "field": "avgScore",
            "factor": 1.2,
            "modifier": "none",
            "missing": 3.0
          }
        },
        {
          "field_value_factor": {
            "field": "reviewCount",
            "factor": 0.5,
            "modifier": "log1p",
            "missing": 0
          }
        }
      ],
      "score_mode": "sum",      # 多个 function 的得分相加
      "boost_mode": "multiply"  # 与 query._score 相乘
    }
  }
}
```

**modifier 取值**：

| modifier | 计算方式 | 适用场景 |
|---|---|---|
| `none` | 直接用字段值 | avgScore（0~5，线性） |
| `log1p` | log(1 + 值) | reviewCount（防百万接单量独大） |
| `sqrt` | √值 | 中等压制 |
| `square` | 值² | 放大差异 |

**boost_mode 取值**：

| boost_mode | 公式 | 效果 |
|---|---|---|
| `multiply` | _score × 函数分 | 相关度和业务指标都重要 |
| `replace` | 函数分（忽略 _score）| 完全按业务排序 |
| `sum` | _score + 函数分 | 两者叠加 |

### 分页、排序、高亮

```bash
GET /merchant/_search
{
  "query": { "match": { "intro": "妆娘" } },
  "from": 0,          # 跳过的文档数（from = (page-1) × size）
  "size": 10,         # 每页数量
  "_source": ["nickname", "city", "avgScore"],  # 只返回这些字段
  "sort": [
    { "avgScore": { "order": "desc" } },
    "_score"
  ],
  "highlight": {
    "fields": {
      "intro": {}     # 高亮 intro 字段的匹配词
    }
  }
}
```

**分页注意**：
- `from + size <= 10000`（ES 默认限制），深度分页用 `search_after`（游标分页）
- 本项目用 `CursorPageVO` 对应 ES 的 `search_after`

---

## 五、IK 分词器详解

### ik_max_word vs ik_smart

```
输入：  "专业妆娘接单"
ik_max_word → ["专业", "妆娘", "接单", "专业妆娘", "妆娘接单"]  最细粒度（用于建索引）
ik_smart    → ["专业", "妆娘", "接单"]                           最粗粒度（用于搜索）
```

**为什么建索引用 max_word，搜索用 smart？**
- 建索引时切得越细，召回率越高（找得到更多）
- 搜索时切得越粗，精准率越高（避免错误匹配）
- 搜"妆娘服务" → ik_smart → ["妆娘","服务"] → 需要文档同时包含这两词
- 如果搜索也用 max_word，可能把"妆娘服务"切成"妆"+"娘"+"服"+"务"，精准度下降

### 为什么要加扩展词典？

IK 默认词典是几年前的，缺少：
- 二次元角色名（"洛天依"被拆成"洛"+"天依"）
- 行业术语（"妆娘"被拆成"妆"+"娘"）
- 新词、网络用语（"破防"、"塌房"）
- 地标（"陶然亭"被拆成"陶然"+"亭"）

加入扩展词典后，这些词被当作原子词不再拆分，搜索精准度大幅提升。

### 远程热更新原理

```
IK 插件（ES 内部）
  ↓ 每 60 秒
GET http://mhp-account:8081/internal/ik/ext-words
  ↓ 检查响应头 Last-Modified
  ↓ 与上次拉取时的 Last-Modified 比较
  ├─ 相同 → 词库未变化，跳过
  └─ 不同 → 读响应体（每行一个词），热重载词库（不重启 ES）
```

### 拼音插件（analysis-pinyin）+ IK 组合

**问题**：用户搜"zhuangniang"，想找到"妆娘"的商家。
**方案**：在 nickname 和 intro 上建拼音子字段（multi-field）。

**自定义 analyzer "ik_pinyin" 的分词过程**：

```
输入："妆娘专业接单"
       ↓ IK 分词（tokenizer）
["妆娘", "专业", "接单"]
       ↓ pinyin filter（对每个 token 做拼音转换）
["妆娘",  "zhuang","niang","zn",    # keep_original=true 保留中文
 "专业",  "zhuan","ye","zy",
 "接单",  "jie","dan","jd"]
       ↓ remove_duplicated_term=true 去重

索引中存储的 tokens：
["妆娘","zhuang","niang","zn","专业","zhuan","ye","zy","接单","jie","dan","jd"]
```

**用户搜索"zhuangniang"的流程**：

```
输入："zhuangniang"（非中文）
       ↓ IK（无法识别，原样输出）
["zhuangniang"]
       ↓ pinyin filter（none_chinese_pinyin_tokenize=true → 按拼音音节切割）
["zhuang", "niang"]
       ↓ 在倒排索引中查找
["zhuang"] → [文档1, 文档2]
["niang"]  → [文档1, 文档3]
       ↓ 取交集（AND 关系）
结果：文档1 ✓
```

---

## 六、Spring Data Elasticsearch Java API

### 依赖和自动配置原理

```xml
<!-- pom.xml -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-data-elasticsearch</artifactId>
</dependency>
```

**为什么只需加一个依赖，不用写任何 Bean 配置？**

Spring Boot 自动配置（`ElasticsearchRestClientAutoConfiguration` 等）：
1. 检测到 classpath 有 `elasticsearch-java.jar`
2. 读取 `spring.elasticsearch.uris` 配置（application.yaml）
3. 自动创建 `ElasticsearchClient` Bean（底层 HTTP 连接池）
4. 自动创建 `ElasticsearchOperations` Bean（高级操作接口）
5. `@Repository` 接口自动被 Spring Data 代理，生成 `ElasticsearchRepository` 实现

图片中尚硅谷教程手写的那一堆 SSL 代码，是因为他们的 ES 开启了 `xpack.security.enabled=true`（HTTPS + 认证）。本项目 docker-compose 中设置 `xpack.security.enabled=false`，所以 Spring Boot 自动配置即可，不需要手写证书代码。

**三层 API 的关系**：

```
你的代码
  ├── ElasticsearchRepository     （最高层，CRUD 够用就用这个）
  └── ElasticsearchOperations     （中间层，复杂查询用这个）
         └── ElasticsearchClient  （最底层，自动注入，一般不直接用）
                └── RestClient    （HTTP 传输层，连接 ES 9200 端口）
```

### 实体类注解

```java
// @Document：声明这个类对应 ES 的哪个索引
// createIndex=true（默认）：启动时如果索引不存在则自动创建
@Document(indexName = "merchant", createIndex = true)

// @Setting：指定索引的 settings 文件路径（classpath 相对路径）
// 文件中定义自定义 analyzer，@InnerField 引用的 analyzer 必须在这里声明
@Setting(settingPath = "elasticsearch/merchant-settings.json")

// @Field：声明字段的 ES 类型和分词器
@Field(type = FieldType.Text, analyzer = "ik_max_word", searchAnalyzer = "ik_smart")
private String intro;

// @Field(index=false)：不建倒排索引，只存储，节省空间
@Field(type = FieldType.Keyword, index = false)
private String avatar;

// @Id：对应 ES 文档的 _id
@Id
private Long id;
```

**FieldType 枚举对照表**：

| ES 类型 | FieldType | 说明 |
|---------|-----------|------|
| text | `FieldType.Text` | 全文搜索，会分词 |
| keyword | `FieldType.Keyword` | 精确匹配，不分词 |
| integer | `FieldType.Integer` | 整数 |
| double | `FieldType.Double` | 浮点数 |
| date | `FieldType.Date` | 日期 |
| boolean | `FieldType.Boolean` | 布尔 |
| nested | `FieldType.Nested` | 嵌套对象（数组对象搜索用） |

**@MultiField（多字段映射）**：

```java
// 同一字段对应 ES 中的 multi-field：
// nickname     → Text，IK 分词（主字段）
// nickname.pinyin → Text，拼音分词（子字段）
@MultiField(
    mainField  = @Field(type = FieldType.Text,
                        analyzer = "ik_max_word",
                        searchAnalyzer = "ik_smart"),
    otherFields = {
        @InnerField(suffix = "pinyin",
                    type = FieldType.Text,
                    analyzer = "ik_pinyin",
                    searchAnalyzer = "ik_pinyin")
    }
)
private String nickname;
```

生成的 ES mapping：
```json
"nickname": {
  "type": "text",
  "analyzer": "ik_max_word",
  "search_analyzer": "ik_smart",
  "fields": {
    "pinyin": {
      "type": "text",
      "analyzer": "ik_pinyin",
      "search_analyzer": "ik_pinyin"
    }
  }
}
```

### ElasticsearchRepository（简单 CRUD）

```java
@Repository
public interface MerchantEsRepository extends ElasticsearchRepository<MerchantDoc, Long> {
    // 继承了以下方法，无需实现：
    // save(doc)          → PUT /merchant/_doc/{id}
    // saveAll(docs)      → POST /merchant/_bulk（批量）
    // findById(id)       → GET /merchant/_doc/{id}
    // deleteById(id)     → DELETE /merchant/_doc/{id}
    // findAll()          → GET /merchant/_search（返回所有）
    // count()            → GET /merchant/_count

    // 支持方法名派生查询（自动生成 ES 查询，不需要写 DSL）：
    List<MerchantDoc> findByCity(String city);
    List<MerchantDoc> findByCityAndAvgScoreGreaterThan(String city, Double score);
    // Spring Data ES 解析方法名 → 生成对应 ES query DSL
}
```

**什么时候用 Repository，什么时候用 Operations？**
- Repository：简单 CRUD、根据字段精确查询 → 代码最少
- Operations：bool query、function_score、聚合、分页排序等复杂查询

### ElasticsearchOperations（复杂查询）

`ElasticsearchOperations` 是 Spring Data ES 的核心查询接口，通过注入使用：

```java
@Service
@RequiredArgsConstructor
public class MyService {
    private final ElasticsearchOperations elasticsearchOperations;
}
```

**核心方法**：

```java
// 搜索文档，返回 SearchHits（包含 totalHits 和命中列表）
SearchHits<MerchantDoc> hits = elasticsearchOperations.search(query, MerchantDoc.class);

// 获取 totalHits（分页用）
long total = hits.getTotalHits();

// 遍历命中结果
hits.stream()
    .map(SearchHit::getContent)  // SearchHit → MerchantDoc
    .collect(Collectors.toList());

// 单文档操作
MerchantDoc doc = elasticsearchOperations.get("123", MerchantDoc.class);
elasticsearchOperations.save(doc);
elasticsearchOperations.delete("123", MerchantDoc.class);

// 索引操作（管理索引结构）
IndexOperations indexOps = elasticsearchOperations.indexOps(MerchantDoc.class);
indexOps.create();    // 创建索引
indexOps.delete();    // 删除索引
indexOps.refresh();   // 强制 refresh（测试时用）
```

### NativeQuery 构建查询

`NativeQuery` 是 Spring Data ES 对原生 ES DSL 的 Java 封装，支持写任意 ES 查询。

```java
// 基本骨架
NativeQuery query = NativeQuery.builder()
    .withQuery(q -> q.bool(b -> b       // 查询条件
        .must(...)
        .filter(...)
    ))
    .withPageable(PageRequest.of(0, 10)) // 分页（页码从 0 开始）
    .withSort(Order.desc("avgScore"))    // 排序
    .build();

SearchHits<MerchantDoc> hits = elasticsearchOperations.search(query, MerchantDoc.class);
```

**Lambda 嵌套语法说明**：

ES 的 Java 客户端大量使用 Builder 模式 + Lambda，初看很难懂，实际上是固定套路：

```java
// 每一层都是：类型.of(变量 -> 变量.具体方法(...))
Query.of(q ->          // q 是 Query.Builder
    q.bool(b ->        // b 是 BoolQuery.Builder
        b.must(m ->    // m 是 Query.Builder（must 的子查询）
            m.match(match ->    // match 是 MatchQuery.Builder
                match.field("nickname").query("妆娘")
            )
        )
        .filter(f ->   // f 是 Query.Builder
            f.term(t ->
                t.field("city").value("北京")
            )
        )
    )
)
```

等价的 ES DSL（JSON）：
```json
{
  "query": {
    "bool": {
      "must": { "match": { "nickname": "妆娘" } },
      "filter": { "term": { "city": "北京" } }
    }
  }
}
```

**常用 Query 类型对照**：

```java
// match 全文搜索
Query.of(q -> q.match(m -> m.field("intro").query("妆娘")))

// multi_match 多字段搜索
Query.of(q -> q.multiMatch(mm -> mm
    .fields("nickname^2", "intro", "nickname.pinyin")
    .query("妆娘")
))

// term 精确匹配
Query.of(q -> q.term(t -> t.field("city").value("北京")))

// match_all 匹配所有
Query.of(q -> q.matchAll(ma -> ma))

// range 范围查询
Query.of(q -> q.range(r -> r
    .field("avgScore")
    .gte(JsonData.of(4.0))
    .lte(JsonData.of(5.0))
))
```

### Function Score 完整示例

```java
// 1. 构建内层 bool query
Query boolQuery = Query.of(q -> q.bool(b -> b
    .must(m -> m.multiMatch(mm -> mm
        .fields("nickname^2", "intro", "nickname.pinyin", "intro.pinyin")
        .query("妆娘")
    ))
    .filter(buildFilters("北京", 1))  // term filter 精确筛选
));

// 2. 包裹 function_score
Query finalQuery = Query.of(q -> q.functionScore(fs -> fs
    .query(boolQuery)
    .functions(
        // 函数1：avgScore 线性加权（评分 × 1.2）
        FunctionScore.of(fn -> fn.fieldValueFactor(fvf -> fvf
            .field("avgScore")
            .factor(1.2d)
            .modifier(FieldValueFactorModifier.None)
            .missing(3.0d)   // null → 默认 3 分
        )),
        // 函数2：reviewCount 对数加权（log(1 + count) × 0.5）
        FunctionScore.of(fn -> fn.fieldValueFactor(fvf -> fvf
            .field("reviewCount")
            .factor(0.5d)
            .modifier(FieldValueFactorModifier.Log1p)
            .missing(0.0d)
        ))
    )
    .scoreMode(FunctionScoreMode.Sum)       // 两个函数分相加
    .boostMode(FunctionBoostMode.Multiply)  // 与 bool._score 相乘
));

// 3. 执行
NativeQuery nativeQuery = NativeQuery.builder()
    .withQuery(finalQuery)
    .withPageable(PageRequest.of(0, 10))
    .build();

SearchHits<MerchantDoc> hits = elasticsearchOperations.search(nativeQuery, MerchantDoc.class);
```

**必须导入的包**：

```java
import co.elastic.clients.elasticsearch._types.query_dsl.FieldValueFactorModifier;
import co.elastic.clients.elasticsearch._types.query_dsl.FunctionBoostMode;
import co.elastic.clients.elasticsearch._types.query_dsl.FunctionScore;
import co.elastic.clients.elasticsearch._types.query_dsl.FunctionScoreMode;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.elasticsearch.client.elc.NativeQuery;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.SearchHits;
```

### 聚合查询（Aggregation）

聚合类似 SQL 的 GROUP BY + 聚合函数，用于统计分析。

```java
// 查询各城市的商家数量（DSL 写法）
// GET /merchant/_search
// { "aggs": { "city_count": { "terms": { "field": "city" } } } }

NativeQuery query = NativeQuery.builder()
    .withQuery(q -> q.matchAll(ma -> ma))
    .withAggregation("city_count", Aggregation.of(a -> a
        .terms(t -> t.field("city").size(20))   // 按 city 分组，最多返回 20 个桶
    ))
    .withPageable(PageRequest.of(0, 0))  // size=0 表示只要聚合结果，不要文档
    .build();

SearchHits<MerchantDoc> hits = elasticsearchOperations.search(query, MerchantDoc.class);

// 取聚合结果
ElasticsearchAggregations aggregations = (ElasticsearchAggregations) hits.getAggregations();
StringTermsAggregate cityAgg = aggregations.get("city_count").sterms();
for (StringTermsBucket bucket : cityAgg.buckets().array()) {
    String city = bucket.key().stringValue();
    long count = bucket.docCount();
    System.out.println(city + ": " + count + "家商家");
}
```

**常见聚合类型**：

```java
// terms：分组统计（类似 GROUP BY）
Aggregation.of(a -> a.terms(t -> t.field("city")))

// avg：求平均值
Aggregation.of(a -> a.avg(avg -> avg.field("avgScore")))

// max / min：最大最小值
Aggregation.of(a -> a.max(m -> m.field("priceMax")))

// range：按范围分组
Aggregation.of(a -> a.range(r -> r
    .field("avgScore")
    .ranges(
        AggregationRange.of(ar -> ar.to("3").key("差")),
        AggregationRange.of(ar -> ar.from("3").to("4").key("中")),
        AggregationRange.of(ar -> ar.from("4").key("优"))
    )
))
```

### 批量操作（Bulk）

批量写入性能远高于逐条写入（减少 HTTP 往返次数）：

```java
// 用 saveAll 底层走 ES Bulk API
List<MerchantDoc> docs = merchants.stream()
    .map(m -> toDoc(m, userMap.get(m.getUserId())))
    .collect(Collectors.toList());
merchantEsRepository.saveAll(docs);
// 等价于：POST /_bulk { index + source } × N
```

---

## 七、MHP 项目架构实战

### 商家搜索完整链路

```
前端 HomeView.vue
  ↓ GET /api/merchant/search?keyword=妆娘&city=北京
Spring Cloud Gateway
  ↓ lb://mhp-account
MerchantController.search()
  ↓
MerchantServiceImpl.search()
  ↓ NativeQuery（function_score + bool query + pinyin）
ElasticsearchOperations.search()
  ↓ HTTP GET /merchant/_search（JSON DSL）
Elasticsearch 8.x
  ↓ 倒排索引查找 + BM25 评分 + function_score 加权
SearchHits<MerchantDoc>
  ↓ docToVO()
Page<MerchantVO> → 前端
```

### Canal CDC 数据同步链路

```
运营人员更新商家简介
  ↓ MerchantServiceImpl.updateInfo() → MySQL merchant 表 UPDATE
  ↓ binlog（ROW 格式，记录每行的前镜像 + 后镜像）
Canal Server（伪装成 MySQL 从库，接收 binlog）
  ↓ Canal 客户端（CanalSyncService 后台线程）
  ↓ parseFrom(entry.getStoreValue())
handleMerchantChange()
  ↓ merchantMapper.selectById(id)  （查最新完整数据，比直接用 binlog 可靠）
  ↓ userMapper.selectById(userId)  （昵称在 user 表，需要关联）
merchantEsRepository.save(toDoc(merchant, user))
  ↓ PUT /merchant/_doc/{id}
Elasticsearch 文档更新完成
  ↓（下次 Refresh 后可搜索，≤1 秒）
```

### 索引初始化 vs 增量同步

| | 初始化 | 增量同步 |
|---|---|---|
| 触发时机 | 首次部署或 ES 数据丢失时 | 持续自动 |
| 接口 | `POST /internal/merchant/es/init` | 无（Canal 自动） |
| 实现 | `saveAll(allDocs)` Bulk API | `save(doc)` 单条 |
| 调用次数 | 一次 | 每次数据库变更时 |

### 修改 ES 索引 Mapping 的步骤

ES **不支持修改已有字段的类型**，也不能删除字段。如果修改了 `MerchantDoc.java` 的 `@Field` 注解或 `@Setting` 中的 analyzer：

```bash
# 1. 删除旧索引（所有数据丢失！）
DELETE http://localhost:9200/merchant

# 2. 重启 mhp-account（Spring Data ES 会按新 mapping 自动建索引）
# 或手动创建：POST http://localhost:8081/actuator/... （如果配置了）

# 3. 全量导入数据
POST http://localhost:8081/internal/merchant/es/init
```

---

## 八、各查询方式选择总结

| 需求 | 推荐方式 | 示例 |
|------|----------|------|
| 按 id 查单条 | `esRepository.findById(id)` | - |
| 保存/更新 | `esRepository.save(doc)` | Canal 同步时 |
| 批量保存 | `esRepository.saveAll(docs)` | 初始化时 |
| 删除 | `esRepository.deleteById(id)` | 商家注销时 |
| 简单字段过滤 | `esRepository.findByCity(city)` | 方法名派生 |
| 全文搜索 + 分页 | `ElasticsearchOperations.search()` + `NativeQuery` | 商家搜索 |
| 聚合统计 | `ElasticsearchOperations.search()` + `withAggregation` | 城市统计 |
| function_score | `ElasticsearchOperations.search()` + `NativeQuery` | 本项目搜索 |

---

## 九、常用 Kibana 调试命令

```bash
# 查看集群健康
GET /_cluster/health

# 查看索引列表
GET /_cat/indices?v

# 查看索引的 mapping
GET /merchant/_mapping

# 分析器测试（看 IK 如何分词）
GET /merchant/_analyze
{
  "analyzer": "ik_max_word",
  "text": "专业妆娘接单北京陶然亭"
}

# 查询并显示评分原理（BM25 计算过程）
GET /merchant/_search?explain=true
{
  "query": { "match": { "intro": "妆娘" } }
}

# 查看所有文档
GET /merchant/_search
{
  "query": { "match_all": {} },
  "size": 20
}

# 强制 refresh（让刚写入的文档立即可搜索，测试用）
POST /merchant/_refresh
```
