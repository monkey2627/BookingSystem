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
2. 在倒排索引（词典）中查找"妆娘" → O(log N)
3. 取出对应的文档 ID 列表 → [文档1, 文档2]
4. 按照评分排序后返回

### 倒排索引的三个组成部分

| 组件 | 说明 | 数据结构 |
|------|------|----------|
| **Term（词条）** | 文档分词后的最小单元，如"妆娘" | - |
| **Term Dictionary（词典）** | 所有词条的有序集合 | B+ Tree（查找 O(log N)）|
| **Postings List（倒排表）** | 每个词条对应的文档 ID + 词频 + 位置信息 | 压缩整数列表 |

**搜索的本质**：词典查找（O(log N)）+ 文档 ID 取交/并（bool query）

---

## 二、Segment（段）— ES 存储核心

### 2.1 为什么需要 Segment？不可变的设计哲学

倒排索引一旦写入磁盘就**永远不可修改**（immutable）。这是刻意的设计：
- 不可变 → 读操作无需加锁，并发性能极高
- 不可变 → 可以安全地被多个线程同时读取

但"不可变"带来了问题：怎么处理文档的更新和删除？

答案是：**按段（Segment）写入，用删除标记处理更新/删除**。

**核心定义：**
> Segment 是磁盘上一组不可变的倒排索引文件集合。一次 refresh 生成一个新 Segment；一个索引可以有多个 Segment，查询时遍历全部 Segment 并合并结果。

---

### 2.2 写入全流程：Buffer → Refresh → Flush → Merge

```
客户端写入文档
       ↓
[内存 Index Buffer（索引缓冲区）]  ← 写入速度最快，但断电丢失
       ↓ refresh（默认每 1 秒触发一次）
[内存 Segment（可搜索）]           ← 文档立刻可被搜索（近实时 NRT）
       ↓ flush（Translog 达到 512MB 或 30 分钟）
[磁盘 Segment 文件]                ← 持久化，数据安全
       ↓ merge（后台自动）
[合并后的大 Segment]               ← 减少小 Segment 数量，提升查询性能
```

#### Refresh（Buffer → 内存 Segment）

- **`index.refresh_interval: 1s` 的含义：每隔 1 秒触发一次 refresh 任务，不是做一次 refresh 要花 1 秒。**
- refresh 本身耗时很短，通常几毫秒即可完成。
- 将 Buffer 中**当前积攒的所有文档**一次性写入一个新的内存 Segment。
- 写入后文档立刻可被搜索 → 这是 ES"近实时搜索（NRT）"的来源（最多等 ≤1 秒）。

**时间线示例：**

```
0.000s  写入一批文档，进入 Index Buffer
0.005s  refresh 执行完毕（耗时 5ms）→ 生成 Segment-1，Buffer 清空
1.000s  定时器再次触发，执行 refresh
1.003s  refresh 完毕 → 生成 Segment-2
2.000s  再次触发 refresh...
```

**每次 refresh 生成几个 Segment？**
> **一次 refresh = 1 个新 Segment**，不管这次 Buffer 里积攒了 1 条还是 10000 条文档。

❌ 误区：写 1 条文档 → 1 个 Segment  
✅ 事实：写 N 条，攒到 refresh 触发，统一打包成 1 个 Segment

**触发 refresh 的三种方式：**

| 方式 | 说明 |
|------|------|
| 定时自动 | `refresh_interval: 1s`，每 1 秒触发一次 |
| 手动 API | `POST /merchant/_refresh`，立刻把 Buffer 生成 Segment |
| 请求参数 | `POST /merchant/_bulk?refresh=true`，请求结束立刻 refresh |

**高写入场景配置：**

```yaml
# 批量导入时关闭自动 refresh，导入完再恢复，减少 Segment 碎片
index.refresh_interval: -1   # -1 = 完全关闭定时 refresh

# 恢复
index.refresh_interval: 1s
```

⚠️ 每写一条就带 `?refresh=true`，会产生大量只有 1 条文档的极小 Segment，查询性能会急剧下降。

#### Translog（事务日志，类似 MySQL 的 redo log）

```
写入顺序：内存 Buffer → Translog（追加写，极快）→ 磁盘 Segment（异步）
```

- Translog 记录每次写入操作（追加写，不修改已有内容，速度极快）
- **目的**：防止 Buffer 中的数据因服务崩溃丢失
- 恢复时：重放 Translog 中未 flush 的操作，恢复内存状态
- 类比：MySQL redo log 在事务提交时先写，保证崩溃恢复

#### Flush（内存 Segment → 磁盘）

- 触发条件：Translog 达到 512MB，或距上次 flush 超过 30 分钟
- 将内存中的 Segment 序列化写入磁盘，生成一整套磁盘文件，然后清空 Translog
- Flush 后数据真正持久化，不再需要 Translog 来恢复

#### Merge（段合并）

- 问题：每次 refresh 产生一个新 Segment，长期运行会有很多小 Segment
- 后台线程自动把多个小 Segment 合并成一个大 Segment
- 合并时：读取多个旧 Segment 的全部有效文档（跳过 `.del` 标记的），重新构建一套全新倒排索引
- 合并成功后，旧 Segment 文件被物理删除，被标记删除的文档也在此时真正释放空间

**三者区别速记：**

| 操作 | 触发 | 结果 |
|------|------|------|
| refresh | 每 1 秒（定时）| Buffer → 内存 Segment，文档可搜索，不落磁盘 |
| flush | Translog 大小/30 分钟 | 内存 Segment → 磁盘文件，Translog 清空 |
| merge | 后台自动 | 多个小 Segment → 一个大 Segment，旧段物理删除 |

---

### 2.3 Segment 内部文件结构

每个 Segment 对应磁盘上一批文件，以段名 `_0` 举例：

| 文件后缀 | 作用 | 存储内容 |
|----------|------|----------|
| `.fdt` | 文档存储文件 | 原始文档数据（各字段原始值，`_source` 来源） |
| `.fdx` | 文档索引 | 每个 docId 在 `.fdt` 中的偏移位置，快速定位文档 |
| `.tim` | Term 词典 | 词条字典（B+ Tree 结构，词条有序，快速查找 term） |
| `.tip` | Term 词典索引 | `.tim` 的索引，加速 term 查找 |
| `.doc` | Postings 倒排表 | term 对应的文档 id、词频、位置、偏移（核心搜索数据） |
| `.pos` | 词条位置 | 词在文档中出现的位置，用于 `match_phrase` 短语搜索 |
| `.pay` | 偏移量 | 高亮需要的字符偏移信息 |
| `.dvd` / `.dvm` | 文档值 | doc_values，排序、聚合用的列存数据 |
| `.del` | 删除标记文件 | 记录哪些 docId 已被删除/更新，不修改原 Segment 数据 |

> - Segment 文件一旦生成，**只读，绝不修改**。更新、删除不会改动旧 Segment 的任何文件。
> - `.del` 不属于 Segment 本体，是附加标记；查询命中文档后检查 `.del`，已标记删除则过滤掉。
> - 只有 **merge 时**才物理丢弃被删除的文档，生成全新 Segment。

---

### 2.4 删除和更新的实现方式

**删除：**
- 只在 `.del` 文件中标记该文档已删除，不修改 Segment
- 查询时命中被删除文档会被过滤掉
- 真正释放空间：等 merge 时物理删除

**更新（本质是写新 + 标记旧）：**

```
1. 旧文档在 Segment-A，A 完全不动
2. 新版本文档写入 Buffer → refresh → 生成 Segment-B
3. 在 .del 中标记 Segment-A 里旧 docId 已删除
4. 查询时：A 命中旧文档 → 发现 .del 标记 → 丢弃；B 命中新文档 → 返回
5. merge 时：物理丢弃 A 中被标记删除的文档
```

> ES 更新不是原地修改，是**写新的，标记旧的作废**。

---

### 2.5 Segment 是完整的独立子索引

> **每个 Segment 相当于一个微型、独立的小 ES 索引。它自己内部有文档、词典、倒排表、doc_values，只属于当前这一批文档，可以独立完成一次搜索。**

这不是一个大倒排索引被切分成很多块，而是**每个 Segment 都单独做了一份完整倒排索引**。

**具体示例（refresh_interval=1s）：**

```
t=0s：写入文档1、文档2，进入 Buffer
t=1s：触发 refresh → 生成 Segment-A，包含文档 1、2
```

Segment-A 内部自己生成属于【文档1、2】的整套倒排：
```
# Segment-A 自己的词典 + 倒排表（只覆盖文档 1、2）
"妆娘"  → [doc1, doc2]
"接单"  → [doc1]
"古风"  → [doc2]
```

```
t=2s：写入文档3、文档4，进入 Buffer
t=2s：refresh 触发 → 生成 Segment-B，包含文档 3、4
```

Segment-B 是另一套独立完整子索引，只针对文档 3、4：
```
# Segment-B 的词典 + 倒排表（只覆盖文档 3、4）
"摄影师" → [doc3]
"外拍"   → [doc3]
"妆娘"   → [doc4]
"cos"    → [doc4]
```

**⚠️ 重点：没有一个全局统一的大倒排表！**  
`"妆娘"` 这个词条，A 里面有一份，B 里面又单独有一份，两者互不干涉。

**当搜索关键词「妆娘」：**

```
1. 拿到 shard 下所有 Segment：Segment-A、Segment-B
2. 分别在每个 Segment 内部独立搜索：
   - Segment-A：查找 "妆娘" → [doc1, doc2]，算 BM25 分
   - Segment-B：查找 "妆娘" → [doc4]，算 BM25 分
3. 归并两组结果，合并打分，过滤 .del 标记，返回 [doc1, doc2, doc4]
```

**merge 之后：**

后台 merge 线程读取 A、B 全部有效文档（doc1~doc4），重新构建一套完整倒排索引生成 Segment-C：
```
Segment-C（统一的新子索引）
"妆娘"   → [doc1, doc2, doc4]
"接单"   → [doc1]
"古风"   → [doc2]
"摄影师" → [doc3]
"外拍"   → [doc3]
"cos"    → [doc4]
```
Segment-A 和 Segment-B 的磁盘文件被物理删除，shard 下只剩 Segment-C。

---

### 2.6 为什么 Segment 过多会拖慢查询？

假设某索引 shard 下有 100 个小 Segment：
1. 查询需要打开 100 套独立倒排索引，分别执行 term 查找，拿到 100 组文档 id 列表
2. 对 100 组结果做归并排序，合并打分，过滤删除标记

Segment 数量越多，IO、内存、归并排序开销越大。这就是 merge 后台任务存在的原因——把一堆小 Segment 合并成大 Segment，减少段数量。

> 对比 MySQL：MySQL 一张表就一套 B+ 树索引；ES 一个 shard 下有多套独立小索引（Segment）。

---

## 三、索引层级：集群 → 索引 → 分片 → Segment

### 完整层级

```
ES 集群（一堆机器节点）
├─ 索引 A  merchant（自己配置：3 主分片）
│   ├─ 主分片 0 → 多个 Segment
│   ├─ 主分片 1 → 多个 Segment
│   └─ 主分片 2 → 多个 Segment
├─ 索引 B  order（自己配置：2 主分片）
│   ├─ 主分片 0 → 多个 Segment
│   └─ 主分片 1 → 多个 Segment
└─ 索引 C  log（自己配置：1 主分片）
    └─ 主分片 0 → 多个 Segment
```

**极简记忆口诀：**
> 索引 → 分片（Shard）→ 多个段（Segment）  
> 每一个段 = 一批文档 + 该批文档独立完整的倒排索引

### 关键点

**分片数是每个索引的私有属性，不是集群全局配置：**

```http
PUT /merchant
{
  "settings":{
    "number_of_shards": 3,
    "number_of_replicas": 1
  }
}
```

- ES 7.0 之后，索引默认主分片数 = 1（旧版是 5）
- 主分片数一旦创建就**不可修改**（副本数可以随时改）
- 往 merchant 写文档 → `shard = hash(_id) % 3`；往 order 写 → `hash(_id) % 2`（各自独立）

**容易混淆的点：**

| 误解 | 正确理解 |
|------|----------|
| "集群全局设置主分片数" | ES 没有集群全局分片数；每个索引创建时独立指定 |
| "索引由段组成" | 索引由**分片**组成；每个分片内部由多个段组成 |
| Shard 和 Segment 的区别 | Shard 是逻辑分片（分布在不同节点）；Segment 是 Shard 内部的物理文件集合 |

> **可以用 index_template（索引模板）**预先定义分片配置，匹配 `logs-*` 的索引自动套用，但这只是创建时的默认参数，不是强制的集群全局配置。

---

## 四、BM25 相关度评分

ES 的相关度评分用 **BM25**（BM = Best Match，25 是第 25 次迭代）。

### 为什么不用 TF-IDF？

TF-IDF 的问题：TF 是线性的，"妆娘"出现 100 次的文档比出现 2 次的评分高 50 倍，但实际上用户对"妆娘"出现 100 次的文章并不一定更感兴趣。

BM25 对 TF 做了**饱和处理**：TF 增长但边际效益递减。

### BM25 公式（简化版）

```
score(D, Q) = Σ  IDF(qi) × TF_saturation(qi, D)
              词条qi

TF_saturation = tf(qi,D) × (k1 + 1)
                ─────────────────────────────────────
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

> Kibana 用 `?explain=true` 可以看到 BM25 的完整计算过程，包含 `freq`、`k1`、`b`、`dl`、`avgdl` 等参数。

---

## 五、深度分页性能问题

### 5.1 from + size 为什么性能差

**执行流程（以 3 主分片、from=1000、size=10 为例）：**

```
1. 每个 shard 内部查询：
   - shard-0：遍历自己所有 Segment，归并得到本地 top-1010
   - shard-1：同上，得到本地 top-1010
   - shard-2：同上，得到本地 top-1010

2. 协调节点收到 3 份，每份 1010 条 → 共 3030 条

3. 协调节点在内存中对 3030 条做全局排序

4. 丢弃前 1000 条，截取后 10 条返回
```

**关键点：**
- `from=1000, size=10`，每个分片都要拉取 `from+size=1010` 条，不是只查 10 条
- from 越大，每个分片要返回的数据越多，协调节点内存和 CPU 排序开销暴涨
- Segment 越多，分片本地归并排序的代价进一步放大（雪上加霜）

**ES 默认限制：`index.max_result_window = 10000`**  
即 `from + size ≤ 10000`，超出直接抛异常，防止协调节点内存打崩。

### 5.2 三种分页方案对比

| 分页方式 | 原理 | 优点 | 缺点 | 适用场景 |
|----------|------|------|------|----------|
| `from/size` | 各分片拉取 from+size，协调节点全局排序丢弃前面 | 支持随意跳页 | from 大时性能爆炸，受 10000 限制 | 浅分页（前几十页） |
| `search_after` | 根据排序游标向后读取 | 性能稳定，不受 10000 限制 | 不能跳页，只能顺序往下翻 | APP/网页滚动加载（本项目） |
| `scroll` | 建立索引快照，批量迭代拉取 | 适合大批量导出 | 占内存，快照非实时，不适合用户搜索 | 后台数据导出、全量同步 |

### 5.3 search_after（本项目方案）

```json
GET /merchant/_search
{
  "size": 10,
  "search_after": [4.8, 16888],
  "sort": [
    { "avgScore": "desc" },
    { "_id": "asc" }
  ]
}
```

**执行逻辑：**
- 每个 Shard、每个 Segment，**从 search_after 游标位置开始读取**，不需要读取前面的数据
- 协调节点只聚合每个 shard 返回的 size 条，不加载海量中间数据

**注意：**
- sort 字段必须保证**全局唯一**，一般业务字段 + `_id` 组合，防止排序值重复导致游标定位错乱
- 本项目 `CursorPageVO` 底层就是封装 search_after

> **面试常问：search_after 为什么不能跳页？**  
> 因为它没有保存全量排序结果，只有游标，只能向后遍历，不能随机跳到任意页码。

---

## 六、DSL 查询语法（HTTP 接口）

ES 通过 RESTful + JSON（DSL）操作，所有操作都是 HTTP 请求。

### 索引库操作

```bash
# 创建索引
PUT /merchant
{
  "settings": {
    "number_of_shards": 3,
    "number_of_replicas": 1,
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

**must vs filter 的区别：**
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

**modifier 取值：**

| modifier | 计算方式 | 适用场景 |
|----------|----------|----------|
| `none` | 直接用字段值 | avgScore（0~5，线性） |
| `log1p` | log(1 + 值) | reviewCount（防百万接单量独大） |
| `sqrt` | √值 | 中等压制 |
| `square` | 值² | 放大差异 |

**boost_mode 取值：**

| boost_mode | 公式 | 效果 |
|------------|------|------|
| `multiply` | _score × 函数分 | 相关度和业务指标都重要（本项目用此） |
| `replace` | 函数分（忽略 _score）| 完全按业务排序 |
| `sum` | _score + 函数分 | 两者叠加 |

### 分页、排序、高亮

```bash
GET /merchant/_search
{
  "query": { "match": { "intro": "妆娘" } },
  "from": 0,          # 跳过的文档数
  "size": 10,         # 每页数量
  "_source": ["nickname", "city", "avgScore"],
  "sort": [
    { "avgScore": { "order": "desc" } },
    "_score"
  ],
  "highlight": {
    "fields": {
      "intro": {}
    }
  }
}
```

---

## 七、IK 分词器详解

### ik_max_word vs ik_smart

```
输入：  "专业妆娘接单"
ik_max_word → ["专业", "妆娘", "接单", "专业妆娘", "妆娘接单"]  最细粒度（建索引用）
ik_smart    → ["专业", "妆娘", "接单"]                           最粗粒度（搜索用）
```

**为什么建索引用 max_word，搜索用 smart？**
- 建索引时切得越细，召回率越高（找得到更多）
- 搜索时切得越粗，精准率越高（避免错误匹配）
- 搜"妆娘服务" → ik_smart → ["妆娘","服务"] → 需要文档同时包含这两词
- 如果搜索也用 max_word，可能把"妆娘服务"切成"妆"+"娘"+"服"+"务"，精准度下降

### 为什么要加扩展词典？

IK 默认词典较旧，缺少：
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

**自定义 analyzer "ik_pinyin" 的分词过程：**

```
输入："妆娘专业接单"
       ↓ IK 分词（tokenizer）
["妆娘", "专业", "接单"]
       ↓ pinyin filter（对每个 token 做拼音转换）
["妆娘",  "zhuang","niang","zn",
 "专业",  "zhuan","ye","zy",
 "接单",  "jie","dan","jd"]
       ↓ remove_duplicated_term=true 去重

索引中存储的 tokens：
["妆娘","zhuang","niang","zn","专业","zhuan","ye","zy","接单","jie","dan","jd"]
```

**用户搜索"zhuangniang"的流程：**

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

## 八、Spring Data Elasticsearch Java API

### 依赖和自动配置原理

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-data-elasticsearch</artifactId>
</dependency>
```

**为什么只需加一个依赖，不用写任何 Bean 配置？**

Spring Boot 自动配置（`ElasticsearchRestClientAutoConfiguration` 等）：
1. 检测到 classpath 有 `elasticsearch-java.jar`
2. 读取 `spring.elasticsearch.uris` 配置
3. 自动创建 `ElasticsearchClient` Bean（底层 HTTP 连接池）
4. 自动创建 `ElasticsearchOperations` Bean（高级操作接口）
5. `@Repository` 接口自动被 Spring Data 代理，生成 `ElasticsearchRepository` 实现

> 尚硅谷教程手写那一堆 SSL 代码，是因为他们的 ES 开启了 `xpack.security.enabled=true`（HTTPS + 认证）。本项目 docker-compose 设置 `xpack.security.enabled=false`，Spring Boot 自动配置即可，不需要手写证书代码。

**三层 API 的关系：**

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

**FieldType 枚举对照表：**

| ES 类型 | FieldType | 说明 |
|---------|-----------|------|
| text | `FieldType.Text` | 全文搜索，会分词 |
| keyword | `FieldType.Keyword` | 精确匹配，不分词 |
| integer | `FieldType.Integer` | 整数 |
| double | `FieldType.Double` | 浮点数 |
| date | `FieldType.Date` | 日期 |
| boolean | `FieldType.Boolean` | 布尔 |
| nested | `FieldType.Nested` | 嵌套对象（数组对象搜索用） |

**@MultiField（多字段映射）：**

```java
// 同一字段对应 ES 中的 multi-field：
// nickname       → Text，IK 分词（主字段）
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
}
```

**什么时候用 Repository，什么时候用 Operations？**
- Repository：简单 CRUD、根据字段精确查询 → 代码最少
- Operations：bool query、function_score、聚合、分页排序等复杂查询

### ElasticsearchOperations（复杂查询）

```java
@Service
@RequiredArgsConstructor
public class MyService {
    private final ElasticsearchOperations elasticsearchOperations;
}
```

**核心方法：**

```java
// 搜索文档
SearchHits<MerchantDoc> hits = elasticsearchOperations.search(query, MerchantDoc.class);

// 获取 totalHits（分页用）
long total = hits.getTotalHits();

// 遍历命中结果
hits.stream()
    .map(SearchHit::getContent)
    .collect(Collectors.toList());

// 单文档操作
MerchantDoc doc = elasticsearchOperations.get("123", MerchantDoc.class);
elasticsearchOperations.save(doc);
elasticsearchOperations.delete("123", MerchantDoc.class);

// 索引操作
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
    .withQuery(q -> q.bool(b -> b
        .must(...)
        .filter(...)
    ))
    .withPageable(PageRequest.of(0, 10))
    .withSort(Order.desc("avgScore"))
    .build();

SearchHits<MerchantDoc> hits = elasticsearchOperations.search(query, MerchantDoc.class);
```

**Lambda 嵌套语法说明（固定套路，看懂一个全懂）：**

```java
// 每一层都是：类型.of(变量 -> 变量.具体方法(...))
Query.of(q ->          // q 是 Query.Builder
    q.bool(b ->        // b 是 BoolQuery.Builder
        b.must(m ->    // m 是 Query.Builder
            m.match(match ->
                match.field("nickname").query("妆娘")
            )
        )
        .filter(f ->
            f.term(t ->
                t.field("city").value("北京")
            )
        )
    )
)
```

等价的 DSL（JSON）：
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

**常用 Query 类型对照：**

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
    .filter(buildFilters("北京", 1))
));

// 2. 包裹 function_score
Query finalQuery = Query.of(q -> q.functionScore(fs -> fs
    .query(boolQuery)
    .functions(
        FunctionScore.of(fn -> fn.fieldValueFactor(fvf -> fvf
            .field("avgScore")
            .factor(1.2d)
            .modifier(FieldValueFactorModifier.None)
            .missing(3.0d)
        )),
        FunctionScore.of(fn -> fn.fieldValueFactor(fvf -> fvf
            .field("reviewCount")
            .factor(0.5d)
            .modifier(FieldValueFactorModifier.Log1p)
            .missing(0.0d)
        ))
    )
    .scoreMode(FunctionScoreMode.Sum)
    .boostMode(FunctionBoostMode.Multiply)
));

// 3. 执行
NativeQuery nativeQuery = NativeQuery.builder()
    .withQuery(finalQuery)
    .withPageable(PageRequest.of(0, 10))
    .build();

SearchHits<MerchantDoc> hits = elasticsearchOperations.search(nativeQuery, MerchantDoc.class);
```

**必须导入的包：**

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

```java
NativeQuery query = NativeQuery.builder()
    .withQuery(q -> q.matchAll(ma -> ma))
    .withAggregation("city_count", Aggregation.of(a -> a
        .terms(t -> t.field("city").size(20))
    ))
    .withPageable(PageRequest.of(0, 0))  // size=0 只要聚合结果
    .build();

SearchHits<MerchantDoc> hits = elasticsearchOperations.search(query, MerchantDoc.class);

ElasticsearchAggregations aggregations = (ElasticsearchAggregations) hits.getAggregations();
StringTermsAggregate cityAgg = aggregations.get("city_count").sterms();
for (StringTermsBucket bucket : cityAgg.buckets().array()) {
    String city = bucket.key().stringValue();
    long count = bucket.docCount();
}
```

**常见聚合类型：**

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

```java
// saveAll 底层走 ES Bulk API
List<MerchantDoc> docs = merchants.stream()
    .map(m -> toDoc(m, userMap.get(m.getUserId())))
    .collect(Collectors.toList());
merchantEsRepository.saveAll(docs);
// 等价于：POST /_bulk { index + source } × N
```

---

## 九、MHP 项目实战

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
  ↓ merchantMapper.selectById(id)   （查最新完整数据）
  ↓ userMapper.selectById(userId)   （昵称在 user 表，需要关联）
merchantEsRepository.save(toDoc(merchant, user))
  ↓ PUT /merchant/_doc/{id}
Elasticsearch 文档更新完成
  ↓（下次 Refresh 后可搜索，≤1 秒）
```

### 索引初始化 vs 增量同步

| | 初始化 | 增量同步 |
|-|--------|----------|
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

# 3. 全量导入数据
POST http://localhost:8081/internal/merchant/es/init
```

---

## 十、各查询方式选择总结

| 需求 | 推荐方式 | 示例场景 |
|------|----------|----------|
| 按 id 查单条 | `esRepository.findById(id)` | - |
| 保存/更新 | `esRepository.save(doc)` | Canal 同步时 |
| 批量保存 | `esRepository.saveAll(docs)` | 初始化时 |
| 删除 | `esRepository.deleteById(id)` | 商家注销时 |
| 简单字段过滤 | `esRepository.findByCity(city)` | 方法名派生 |
| 全文搜索 + 分页 | `ElasticsearchOperations.search()` + `NativeQuery` | 商家搜索 |
| 聚合统计 | `ElasticsearchOperations.search()` + `withAggregation` | 城市统计 |
| function_score | `ElasticsearchOperations.search()` + `NativeQuery` | 本项目搜索 |

---

## 十一、常用 Kibana 调试命令

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

# 查看 Segment 信息（段数、文档数、删除文档数、内存占用）
GET /merchant/_segments
```
