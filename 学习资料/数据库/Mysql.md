# MySQL 系统笔记

---

## 一、基础概念

### 1.1 数据库操作

```sql
CREATE DATABASE db_name;            -- 创建
DROP DATABASE db_name;              -- 删除
USE db_name;                        -- 切换
SHOW DATABASES;                     -- 查看所有数据库
SHOW TABLES;                        -- 查看当前库的所有表
```

### 1.2 数据类型

| 类型 | 说明 | 使用场景 |
|------|------|---------|
| `INT` | 整数（-21亿~21亿） | 普通整数字段 |
| `BIGINT` | 大整数 | 主键（推荐） |
| `VARCHAR(n)` | 可变长字符串，最多 n 字符 | 姓名、标题 |
| `CHAR(n)` | 定长字符串，不足补空格 | 固定长度如性别 |
| `TEXT` | 长文本（最大 65535 字节） | 文章内容 |
| `DATE` | 日期 `YYYY-MM-DD` | 生日 |
| `DATETIME` | 日期时间 `YYYY-MM-DD HH:MM:SS` | 创建时间 |
| `DECIMAL(p, s)` | 精确小数，p 总位数，s 小数位 | 价格 `DECIMAL(10,2)` |
| `TINYINT(1)` | 布尔值（0/1） | 是否标志 |

### 1.3 SQL 语言分类（面试高频）

| 分类 | 全称 | 作用 | 常用语句 |
|------|------|------|---------|
| **DML** | Data Manipulation Language | 操作**行数据** | `SELECT` / `INSERT` / `UPDATE` / `DELETE` |
| **DDL** | Data Definition Language | 操作**表/库结构** | `CREATE` / `ALTER` / `DROP` / `TRUNCATE` |
| **DCL** | Data Control Language | 权限控制 | `GRANT` / `REVOKE` |
| **TCL** | Transaction Control Language | 事务控制 | `COMMIT` / `ROLLBACK` / `SAVEPOINT` |

**三个关键区分点**：
1. DML 操作行，产生 undo log，可以事务回滚；**DDL 操作结构，执行时自动隐式提交，不能回滚**
2. **`TRUNCATE` 是 DDL 不是 DML**——删全表数据，不记行级 undo log，无法回滚；`DELETE` 才是 DML，可回滚
3. 有些教材把 SELECT 单独列为 DQL，面试时广义上 SELECT 属于 DML，两种说法都见过，看题目语境

---

## 二、表结构操作（DDL）

### 创建表（CREATE TABLE）

```sql
CREATE TABLE student (
    s_id     INT AUTO_INCREMENT,
    s_name   VARCHAR(50)  NOT NULL,
    s_birth  DATE,
    s_sex    CHAR(2)      NOT NULL DEFAULT '男',
    email    VARCHAR(100) UNIQUE,
    created_at DATETIME   DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (s_id)
);
```

常用约束：`NOT NULL` / `DEFAULT` / `UNIQUE` / `AUTO_INCREMENT` / `PRIMARY KEY` / `FOREIGN KEY`

### 修改表结构（ALTER TABLE）

```sql
ALTER TABLE student ADD COLUMN phone VARCHAR(20);
ALTER TABLE student MODIFY COLUMN s_name VARCHAR(100);
ALTER TABLE student CHANGE COLUMN email contact_email VARCHAR(100); -- 改列名+类型
ALTER TABLE student DROP COLUMN phone;
ALTER TABLE student ADD INDEX idx_name (s_name);
ALTER TABLE student ADD UNIQUE INDEX idx_email (email);
```

### 删除/清空表

```sql
DROP TABLE student;           -- 删表（结构+数据全删，DDL）
TRUNCATE TABLE student;       -- 清空数据，保留结构，自增归零（DDL，不可回滚）
DELETE FROM student;          -- 清空数据（DML，可回滚，慢）
```

---

## 三、SQL 查询与操作

### 3.1 CRUD 增删改查

**INSERT**
```sql
INSERT INTO student (s_name, s_birth, s_sex) VALUES ('赵雷', '1990-01-01', '男');

-- 批量插入（效率更高）
INSERT INTO student (s_name, s_birth, s_sex) VALUES
    ('钱电', '1990-12-21', '男'),
    ('孙风', '1990-05-20', '男');

-- 主键冲突时更新（UPSERT）
INSERT INTO student (s_id, s_name) VALUES (1, '赵雷')
ON DUPLICATE KEY UPDATE s_name = '赵雷';
```

**DELETE / UPDATE**
```sql
-- ⚠️ 必须加 WHERE，否则操作全表！
DELETE FROM student WHERE s_id = 1;
UPDATE student SET s_name = '赵七', s_birth = '1991-01-01' WHERE s_id = 1;
UPDATE score SET s_score = s_score + 5 WHERE c_id = 1;  -- 批量更新
```

**SELECT 完整语法 + 执行顺序**
```sql
SELECT   列名 / 表达式 / 聚合函数      -- ⑥ 最后决定输出哪些列
FROM     表名                           -- ① 先确定数据来源
  JOIN   其他表 ON 关联条件             -- ② 关联
WHERE    行级过滤条件                   -- ③ 过滤行（聚合前）
GROUP BY 分组列                         -- ④ 分组
HAVING   组级过滤条件                   -- ⑤ 过滤组（聚合后）
ORDER BY 排序列 ASC/DESC                -- ⑦ 排序
LIMIT    offset, count;                 -- ⑧ 限制行数
```

> **执行顺序**：FROM → JOIN → WHERE → GROUP BY → HAVING → SELECT → ORDER BY → LIMIT

**UPDATE / DELETE 也涉及"读"**：执行时必须先用**当前读**找到目标行，这一步会自动加锁，然后再做修改/删除。

### 3.2 WHERE 条件过滤

```sql
WHERE age BETWEEN 18 AND 30   -- 含两端
WHERE name IN ('张三', '李四')
WHERE name LIKE '张%'         -- % 任意多字符，_ 一个字符
WHERE email IS NULL           -- NULL 只能用 IS，不能用 =
WHERE age > 18 AND city = '北京'
WHERE age < 18 OR age > 60
```

### 3.3 排序与分页

```sql
-- ORDER BY
SELECT * FROM student ORDER BY s_birth DESC;
SELECT c_id, AVG(s_score) AS avg_score FROM score GROUP BY c_id
ORDER BY avg_score DESC, c_id ASC;   -- 多列排序

-- LIMIT：offset = (页码-1) × 每页数
SELECT * FROM student LIMIT 10;          -- 前10条
SELECT * FROM student LIMIT 10 OFFSET 10; -- 第2页（每页10条）
SELECT * FROM student LIMIT 10, 10;       -- 等价简写
```

### 3.4 聚合函数

| 函数 | 作用 | 无匹配行返回 |
|------|------|------------|
| `COUNT(*)` | 统计行数（含 NULL） | 0 |
| `COUNT(列)` | 统计非 NULL 行数 | 0 |
| `SUM(列)` | 求和（忽略 NULL） | NULL |
| `AVG(列)` | 平均值（忽略 NULL） | NULL |
| `MAX(列)` / `MIN(列)` | 最值 | NULL |

```sql
SELECT c_id, COUNT(*), AVG(s_score) FROM score GROUP BY c_id;
```

### 3.5 GROUP BY + HAVING

> SELECT 后出现的**普通列**（非聚合列），**必须**写在 GROUP BY 里。

```sql
-- ❌ name 不在 GROUP BY
SELECT dept, name, COUNT(*) FROM employee GROUP BY dept;

-- ✅ 加入分组 或 包进聚合函数
SELECT dept, MAX(name), COUNT(*) FROM employee GROUP BY dept;
```

| 对比项 | WHERE | HAVING |
|--------|-------|--------|
| 过滤时机 | GROUP BY **之前**（过滤行） | GROUP BY **之后**（过滤组） |
| 能否用聚合函数 | ❌ | ✅ |
| 性能 | 先过滤再聚合，好 | 先聚合再过滤，开销大 |

```sql
-- WHERE 先缩小范围，HAVING 再过滤聚合结果
SELECT s_id, AVG(s_score) AS avg_score FROM score
WHERE c_id = 1
GROUP BY s_id
HAVING AVG(s_score) >= 60;
```

### 3.6 多表连接（JOIN）

```sql
-- INNER JOIN：只保留两表都匹配的行
SELECT s.s_name, c.c_name, sc.s_score
FROM score sc
INNER JOIN student s ON sc.s_id = s.s_id
INNER JOIN course c ON sc.c_id = c.c_id;

-- LEFT JOIN：左表全部保留，右表无匹配补 NULL
SELECT s.s_name, sc.s_score
FROM student s
LEFT JOIN score sc ON s.s_id = sc.s_id;

-- LEFT JOIN + IS NULL 反连接：查在 A 不在 B 的记录（比 NOT IN 更安全）
SELECT s.* FROM student s
LEFT JOIN score sc ON s.s_id = sc.s_id AND sc.c_id = 2
WHERE sc.s_id IS NULL;
```

### 3.7 子查询

```sql
-- IN 子查询
SELECT * FROM student
WHERE s_id IN (SELECT s_id FROM score GROUP BY s_id HAVING AVG(s_score) > 80);

-- NOT IN（⚠️ 子查询返回 NULL 时结果全为空，生产慎用，改用 NOT EXISTS）
-- EXISTS（推荐：找到即停，大数据量性能好）
SELECT * FROM student s
WHERE EXISTS (SELECT 1 FROM score sc WHERE sc.s_id = s.s_id AND sc.s_score > 80);

-- 标量子查询（返回单值）
SELECT s_name, s_score FROM score sc JOIN student s ON sc.s_id = s.s_id
WHERE s_score = (SELECT MAX(s_score) FROM score);

-- FROM 子查询（派生表）
SELECT s.s_name, t.avg_score FROM student s
JOIN (SELECT s_id, ROUND(AVG(s_score), 2) AS avg_score FROM score GROUP BY s_id) t
ON s.s_id = t.s_id WHERE t.avg_score > 70;
```

**子查询 vs JOIN vs EXISTS 选型**：

| 场景 | 推荐 | 理由 |
|------|------|------|
| 判断是否存在于另一张表 | `EXISTS` | 找到即停，性能最好 |
| 判断不存在 | `NOT EXISTS` | 比 NOT IN 安全（子查询含 NULL 时 NOT IN 全为空） |
| 子查询结果集小 | `IN` | 写法简单 |
| 子查询结果集大 | `JOIN` / `EXISTS` | 避免大列表 |
| 需要子查询的其他列 | `JOIN` | IN/EXISTS 只传递存在性 |

### 3.8 UNION / UNION ALL

```sql
-- UNION：合并去重（有排序开销）
SELECT s_id FROM score WHERE c_id = 1
UNION
SELECT s_id FROM score WHERE s_score > 90;

-- UNION ALL：合并不去重，性能更好（优先用 UNION ALL）
SELECT s_id FROM score WHERE c_id = 1
UNION ALL
SELECT s_id FROM score WHERE s_score > 90;
```

两个 SELECT 的**列数和类型**必须一致。

### 3.9 NULL 处理函数

```sql
IFNULL(s_score, 0)                        -- 为 NULL 时返回默认值
COALESCE(phone, email, '无联系方式')       -- 返回第一个非 NULL（更通用）
total / NULLIF(count, 0)                  -- count=0 时返回 NULL，防除零报错
```

### 3.10 CASE WHEN 条件表达式

```sql
-- 搜索形式（范围/复杂条件）
SELECT s_id, s_score,
    CASE WHEN s_score >= 90 THEN '优秀'
         WHEN s_score >= 70 THEN '良好'
         WHEN s_score >= 60 THEN '及格'
         ELSE '不及格' END AS grade
FROM score;

-- 在聚合中使用（行转列）
SELECT s_id,
    SUM(CASE c_id WHEN 1 THEN s_score ELSE 0 END) AS 语文,
    SUM(CASE c_id WHEN 2 THEN s_score ELSE 0 END) AS 数学
FROM score GROUP BY s_id;
```

### 3.11 常用内置函数

**字符串**
```sql
CHAR_LENGTH('abc')              -- 字符数 → 3
CONCAT('Hello', ' ', 'World')   -- 拼接
SUBSTRING('abcde', 2, 3)        -- 截取（从1开始）→ 'bcd'
UPPER/LOWER('hello')            -- 大小写转换
TRIM('  abc  ')                 -- 去两端空格
REPLACE('abcabc', 'a', 'x')    -- 替换
LPAD('5', 3, '0')               -- 左补零 → '005'
```

**日期**
```sql
NOW() / CURRENT_DATE()
YEAR/MONTH/DAY(s_birth)
DATE_FORMAT(s_birth, '%Y年%m月%d日')
DATEDIFF('2024-01-10', '2024-01-01')   -- 天数差 → 9
DATE_ADD(NOW(), INTERVAL 7 DAY)
TIMESTAMPDIFF(YEAR, s_birth, NOW())    -- 精确年龄（周岁）
```

**数学**
```sql
ROUND(3.567, 2)  -- 四舍五入 → 3.57
CEIL(3.1)        -- 向上取整 → 4
FLOOR(3.9)       -- 向下取整 → 3
MOD(10, 3)       -- 取余 → 1
RAND()           -- 0~1 随机小数
```

### 3.12 窗口函数（MySQL 8.0+）

聚合函数把多行**合并成1行**；窗口函数在每行上**单独计算**，行数不变。

```sql
函数名() OVER (PARTITION BY 分组列  ORDER BY 排序列)
```

**排名函数**
```sql
SELECT s_id, c_id, s_score,
    ROW_NUMBER() OVER (PARTITION BY c_id ORDER BY s_score DESC) AS row_num,  -- 1,2,3（不并列）
    RANK()       OVER (PARTITION BY c_id ORDER BY s_score DESC) AS `rank`,   -- 1,1,3（并列跳号）
    DENSE_RANK() OVER (PARTITION BY c_id ORDER BY s_score DESC) AS dense_r   -- 1,1,2（并列不跳号）
FROM score;
```

**偏移 / 聚合**
```sql
-- LAG 取前 n 行，LEAD 取后 n 行（环比计算常用）
LAG(s_score, 1, 0) OVER (PARTITION BY c_id ORDER BY s_id)

-- 聚合窗口：不折叠行，每行附带分组聚合结果
AVG(s_score) OVER (PARTITION BY c_id)        -- 每行附上课程平均分
SUM(s_score) OVER (ORDER BY s_id)            -- 累计求和
```

**取每门课前 3 名（经典用法）**
```sql
SELECT * FROM (
    SELECT s_id, c_id, s_score,
        RANK() OVER (PARTITION BY c_id ORDER BY s_score DESC) AS rk
    FROM score
) t WHERE rk <= 3;
```

### 3.13 视图（View）

视图是**保存的 SELECT 语句**，使用时像普通表一样查询，本身不存储数据。

```sql
CREATE VIEW v_high_score AS
SELECT s.s_name, c.c_name, sc.s_score
FROM score sc
JOIN student s ON sc.s_id = s.s_id
JOIN course c ON sc.c_id = c.c_id
WHERE sc.s_score >= 80;

SELECT * FROM v_high_score WHERE c_name = '数学';

CREATE OR REPLACE VIEW v_high_score AS ...;   -- 更新定义
DROP VIEW v_high_score;
```

---

## 四、索引

### 4.1 是什么 

索引是帮助数据库高效查询的**有序数据结构**，使查找从 O(n) 全表扫描降到 O(log n)。**代价**：写入时维护索引树变慢；占用磁盘空间。

InnoDB 默认使用 **B+ 树**索引：
- **非叶子节点只存 key（导航键）**，同等页大小容纳更多 key，树更矮（通常 2~3 层覆盖千万级数据）
- **所有数据只存在叶子节点**，查询路径长度固定，性能稳定
- **叶子节点双向链表连接**，范围查询直接遍历链表，无需回溯

### InnoDB B+树索引底层存储

>
> 页大小固定：**16KB**；Extent=64页=1MB；
> 分为：**非叶子页（导航）、叶子页（真实数据）**；所有真实数据全部存在叶子节点。

#### 一、非叶子节点（索引导航层，不存真实行数据）

作用：只存分割key + 子页号，用来路由查找，加快定位叶子页。

1. **逻辑模型（教科书B+树）**
   n个key → 划分出 **n+1个数值区间**，对应 n+1棵子树。
   例：3个key `k0,k1,k2`，4个区间：`<k0`、`

[k0,k1)`、`[k1,k2)`、`≥k2`。

2. **InnoDB真实物理存储（重要考点）**
   不会存储 n+1 个指针。
   物理存储 n 条目录记录：`(key₀, page_no₀), (key₁, page_no₁), (key₂, page_no₂)`

- 每条目录记录是一条完整InnoDB行记录：**key与4字节page_no（子页号）成对出现**，带记录头，参与页内有序链表、页目录二分查找。
- **不存在只有page_no没有key的裸指针**。
- 逻辑上第 n+1 个区间 `≥max_key`，**复用最后一条记录的page_no**，磁盘不额外保存指针。

查找伪逻辑：

```
遍历本页目录项：
    if target < key: 返回该条的page_no
// target大于本页全部key，走最后一条记录的page_no
return last_record.page_no
```

3. 这么设计的原因
   ① 节省页空间：16KB页空间宝贵，尽可能多存放目录项，**提升扇出（分支度），树更矮，减少IO**。
   ② 复用行记录格式：目录项复用统一行格式，不能插入无key的伪记录。
   ③ `≥max_key` 的路由规则可以代码推导，不需要持久化到磁盘。

>
> ⚠️注意：这里的分割key不一定真实存在于表数据，仅做分割标记。
> 当最后一个子页写满发生**页分裂**，父页才新增一条 `(new_key, new_page_no)` 目录项，把超大区间切开。

4. key大小影响扇出

- key就是索引字段本身；主键BIGINT占8字节，page_no固定4字节，单条目录项约12字节。
- key越长，一页容纳目录项越少，扇出下降，B+树高度升高，IO变多。
- 二级索引叶子节点会保存主键，**主键越长，所有二级索引体积都会膨胀**。

---

#### 二、叶子节点（存放真实数据）

##### 1）聚簇索引（主键索引）叶子页

存储：**完整行记录**（主键 + 全部业务字段 + InnoDB隐藏列：事务ID、回滚指针）。
叶子节点之间通过双向链表串联：`next_page` / `prev_page`，范围查询直接顺着链表遍历，不需要回溯上层节点。

>
> InnoDB数据本身就是索引，表数据文件就是主键索引。

##### 2）二级索引（普通索引）叶子页

存储：**索引key + 主键值**，不存储完整行。
查到主键之后，需要回表：拿着主键去聚簇索引叶子页查找完整行数据。

>
> 二级索引的非叶子节点和聚簇索引非叶子节点逻辑完全一致：`(索引key，子页号)`。

---

#### 三、B+树整体特性汇总

1. 非叶子节点只做导航，全部真实数据放在叶子；查询路径长度固定，性能稳定。
2. 千万级数据通常树高 2~3层：根页 → 1层中间非叶子 → 叶子。
3. 叶子双向链表，范围查询友好。
4. 存储单位：页16KB；区Extent=64页=1MB，用于表空间管理。

---

#### 四、高频面试易错点清单

1. ❌错误：非叶子节点物理存储n个key、n+1个指针
   ✅正确：逻辑n个key对应n+1区间；物理只存n组(key+page_no)，最大区间复用最后page_no。
2. ❌错误：分割key一定存在于表的数据
   ✅正确：分割key只是路由标记，可以不存在真实记录。
3. ❌错误：主键长只会影响主键索引
   ✅正确：主键变长，所有二级索引叶子都会存主键，全部索引文件变大。
4. ❌错误：`≥max_key` 的数据会无限堆积在同一个子页
   ✅正确：子页写满触发页分裂，向上父页新增目录项切割区间。
5. ❌混淆：叶子节点的`next_page/prev_page`链表指针 ≠ 非叶子节点的子页page_no。

---
>
> InnoDB B+树非叶子节点逻辑上n个key划分n+1个区间，但物理只存储n组(key+子页号)；没有无key的裸指针，大于本页最大key的数据复用最后一条记录的子页号路由。目的节省页空间，提高扇出使树矮。真实行数据全部存放在聚簇索引叶子节点，叶子之间双向链表；二级索引叶子存索引key+主键，需要回表拿完整数据。
我直接给你**纯 Markdown 完整版**（完全对应当前画布内容、无多余内容、可直接复制保存为 `.md`）

### 4.2 MySQL索引分类

#### 一、索引两大核心分类维度（正交独立）
MySQL 索引分为**逻辑功能分类**和**物理存储分类**，两套维度互不冲突、相互映射，是索引核心考点。

#### 1. 按逻辑功能（约束规则）分类
核心区分：索引列是否允许重复、是否允许为空，属于语法、业务约束层面。

- **主键索引（PRIMARY）**：约束最强，兼具唯一性、非空性，一张表仅能创建一个。
- **唯一索引（UNIQUE）**：保证索引列值不重复，允许存在多个 NULL 值，一张表可创建多个。
- **普通索引（INDEX）**：基础索引，无唯一性约束，允许列值重复、为 NULL，用于常规查询提速。
- **全文索引（FULLTEXT）**：专为文本大字段设计，实现关键词分词检索，替代低效的模糊查询 `%xxx%`。

#### 2. 按物理存储形式（InnoDB 核心）分类
核心区分：B+树叶子节点存储内容，决定查询逻辑与性能，仅分两类。

##### （1）聚簇索引（聚集索引）
- **叶子节点存储**：完整行数据（包含所有业务字段、事务ID、回滚指针等隐藏列）
- **核心特性**：每张表**有且仅有一个**
- **选取优先级规则**：
    1. 优先使用用户自定义主键（PRIMARY）；
    2. 无主键时，选取表中第一个 **NOT NULL + UNIQUE** 列；
    3. 无符合条件列时，InnoDB 自动生成 6 字节隐藏 **rowid** 作为聚簇索引。

##### （2）二级索引（非聚簇索引）
- **叶子节点存储**：当前索引 key + 聚簇索引主键值
- **核心特性**：查询仅能获取主键，需通过**回表**操作，查询聚簇索引获取完整行数据；一张表可存在多个二级索引。
- **包含范围**：普通索引、常规唯一索引、全文索引，默认均属于二级索引。

#### 二、两套索引分类精准映射关系
逻辑功能索引的物理形态不固定，唯一索引存在特殊晋升场景，是高频易错点。

| 逻辑索引类型 | 物理存储类型 | 核心特点 |
| --- | --- | --- |
| 主键索引 | 聚簇索引 | 叶子存完整行，无需回表，约束最强 |
| 普通唯一索引 | 二级索引 | 叶子存索引key+主键，需要回表 |
| 特殊唯一索引（无主键、首个非空唯一列） | 聚簇索引 | 唯一索引晋升为聚簇索引，存完整行数据 |
| 普通索引、全文索引 | 二级索引 | 常规二级索引，非覆盖场景需回表 |

#### 三、主键索引与唯一索引包含关系
##### 1. 逻辑约束层面
主键索引**包含唯一索引的核心能力**：主键 = 唯一约束 + 非空约束，约束强度高于唯一索引。

##### 2. 物理存储层面（核心区分）
二者完全不同，不可等同：
- 主键索引：属于聚簇索引，存储完整行数据，查询性能最优；
- 普通唯一索引：属于二级索引，仅存储索引key+主键，存在回表开销。

#### 四、回表与覆盖索引
- **回表**：二级索引无法存储完整数据，查询时先通过二级索引拿到主键，再通过主键检索聚簇索引获取完整行数据，存在额外IO开销。
- **覆盖索引**：查询所需的所有字段，均包含在二级索引叶子节点中，无需回表查询，是二级索引最优查询形态。
---
### 4.3 创建与管理

```sql
CREATE INDEX idx_name ON student(s_name);
CREATE UNIQUE INDEX idx_email ON student(email);
CREATE INDEX idx_name_birth ON student(s_name, s_birth);  -- 联合索引，字段顺序重要
DROP INDEX idx_name ON student;
SHOW INDEX FROM student;
```

### 4.4 最左前缀原则
联合索引物理上只有**一棵B+树**，索引key为复合键`(a,b,c)`，按**字典序排序：先a，a相同比b，a、b相同再比c**；叶子节点存储`(a,b,c,主键id)`，非叶子节点存储复合key+页指针用于路由。所谓`(a)、(a,b)、(a,b,c)`是**逻辑最左前缀能力，不是三份物理索引**。


1. WHERE条件字段书写顺序无关，MySQL优化器会自动调整顺序，不需要和索引顺序完全一致。
2. 缺少最左起始列a，该联合索引完全无法使用。
3. **范围查询(`> < >= <= between`)之后的索引列失效**：
    - 原因：第一列做范围，后续字段不再保持有序，无法利用索引快速定位，只能做过滤；
    - `>=`/`<=`仍是范围，不能规避该现象。
    - 实践建议：**等值条件放索引前面，范围条件放在联合索引最后一列**。

#### 索引使用对照表（索引(a,b,c)）
| SQL条件 | 用到索引key | 说明 |
| ---- | ---- | ---- |
| where a=1 | (a) | 使用最左前缀a |
| where a=1 and b=2 | (a,b) | a等值，分组内b有序 |
| where a=1 and b=2 and c=3 | (a,b,c) | 使用完整复合key |
| where a=1 and c=3 | (a) | b缺失，c仅做过滤，不走索引定位 |
| where a>1 and b=2 | (a) | a范围，b、c失效 |
| where a=1 and b>2 and c=3 | (a,b) | b范围，c失效 |
| where b=2 / where b=2 and c=3 | 不使用联合索引 | 缺失最左列a |

#### order by 受同样规则约束
- `where a=1 order by b`：a等值，b索引内有序，无需filesort；
- `where a>1 order by b`：a范围，b无序，触发filesort文件排序。

#### 复合key字典序比较规则
依次比较字段：`(1,20,100) < (1,20,200) < (1,30,5) < (2,1,1)`。

> 记忆口诀：**等值优先，范围后置；缺最左前缀索引全废；范围之后索引失效。**
### 4.5 索引失效场景

```sql
-- ❌ 列上做函数运算
WHERE YEAR(created_at) = 2023
-- ✅ 改写为范围
WHERE created_at BETWEEN '2023-01-01' AND '2023-12-31 23:59:59'

-- ❌ LIKE 头部模糊（左侧通配符）
WHERE name LIKE '%张'
-- ✅ 右侧通配符可命中
WHERE name LIKE '张%'

-- ❌ 隐式类型转换（phone 是 VARCHAR，传数字触发转换）
WHERE phone = 13800001111
-- ✅ 加引号
WHERE phone = '13800001111'

-- ❌ OR 有一侧没有索引，则两侧都不走索引
-- ❌ != / NOT IN / NOT EXISTS（一般全表扫描）
-- ❌ IS NULL / IS NOT NULL（取决于数据分布）
```


### 4.7 前缀索引

对长字符串只取前 n 个字符建索引，节省空间：

```sql
CREATE INDEX idx_email_prefix ON users(email(10));
```

n 的选取：`COUNT(DISTINCT LEFT(email, n)) / COUNT(*)` 计算选择性，接近 1 时说明 n 已足够。

### 4.8 SQL 提示（Index Hint）

```sql
SELECT * FROM student USE INDEX(idx_name) WHERE s_name = '张三';    -- 建议（MySQL 可忽略）
SELECT * FROM student IGNORE INDEX(idx_name) WHERE s_name = '张三'; -- 建议忽略
SELECT * FROM student FORCE INDEX(idx_name) WHERE s_name = '张三';  -- 强制
```

### 4.9 SQL 性能分析工具

**执行频率**（判断是否值得优化索引）：
```sql
SHOW GLOBAL STATUS LIKE 'Com_%';  -- 看 Com_select/insert/update/delete 比例
```

**慢查询日志**：
```sql
SET GLOBAL slow_query_log = 1;
SET GLOBAL long_query_time = 2;   -- 超过 2 秒才记录
```

**Profile 详情**（各阶段耗时）：
```sql
SET profiling = 1;
SHOW PROFILES;
SHOW PROFILE FOR QUERY 1;
```

**EXPLAIN 执行计划**（最常用）：
```sql
EXPLAIN SELECT * FROM student WHERE s_name = '张三';
```

重点关注字段：

| 字段 | 说明 |
|------|------|
| `type` | 连接类型，性能从好到差：`const > eq_ref > ref > range > index > ALL` |
| `key` | 实际用到的索引，NULL 表示全表扫描 |
| `key_len` | 用到的索引字节数，越长说明用到的索引列越多 |
| `rows` | 估计扫描行数 |
| `Extra` | `Using index`（覆盖索引）/ `Using filesort`（需优化）/ `Using temporary`（需优化）|

- `const`：主键/唯一索引等值查询，最快
- `ref`：普通索引等值查询
- `range`：索引范围查询
- `ALL`：全表扫描，需要优化

### 4.10 索引设计原则

1. 针对**数据量大、查询频繁**的表建索引
2. 针对 **WHERE / ORDER BY / GROUP BY** 涉及的列建索引
3. **区分度高**的列优先（身份证号 >> 性别）
4. 字符串列尽量用**前缀索引**，控制长度
5. **联合索引优于多个单列索引**（一次查询只能用一个单列索引；联合索引还能利用覆盖索引）
6. 联合索引中**区分度高的列放左边**（配合最左前缀）
7. 单表索引数量**不超过 5 个**，索引越多写入维护代价越高

---
## Mysql的常见引擎概述
存储引擎是MySQL中负责**数据存储、读取、索引实现、事务、锁机制**的底层模块，每张表可以单独指定引擎，`ENGINE=xxx`，不指定默认 `InnoDB`。

查看当前数据库支持的引擎：
```sql
SHOW ENGINES;
```

### 1. InnoDB（默认，最常用）
MySQL5.5之后默认引擎，**支持事务**。
#### 核心特性
1. **ACID事务**，支持`COMMIT / ROLLBACK`，支持保存点
2. **行级锁**：更新操作锁行，并发性能好；DDL是表锁
3. **MVCC多版本并发控制**，实现读不加锁，隔离级别RU/RC/RR/S；MySQL默认隔离级别RR（可重复读）
4. **外键约束**支持
5. 索引：**聚簇索引**，主键和真实数据存放在一起；二级索引叶子存主键值
6. 文件：`.ibd`数据+索引文件，`.frm`表结构；共享表空间/独立表空间
7. 日志：`redo log`崩溃恢复、`undo log`回滚与MVCC、`binlog`归档复制
8. 支持自适应哈希索引、缓冲池Buffer Pool缓存数据页和索引页

#### 适用场景
绝大多数业务表，需要事务、高并发DML、增删改频繁；互联网业务首选。

#### 缺点
- 数据和索引体积相对大；全表扫描比MyISAM略慢；崩溃恢复需要重做redo log。

---

### 2. MyISAM（老版本默认，无事务）
MySQL5.5以前默认引擎。
#### 核心特性
1. **不支持事务、不支持回滚、不支持外键**
2. **表级锁**：写会锁整张表，并发写差，读可以并发
3. **非聚簇索引**，数据和索引分开存储
4. 文件：`.MYD`数据文件，`.MYI`索引文件，`.frm`表结构
5. 支持全文索引(FULLTEXT)、压缩表
6. 崩溃不安全，断电可能损坏数据，需要修复表 `repair table`
7. 计数优化：内部保存表总行数，`SELECT COUNT(*)`不需要扫表，速度极快（不带where条件）

#### 适用场景
只读多、查询多，极少修改；历史归档表、日志静态表。

#### 缺点
无事务，写并发差，宕机容易丢数据，现在新项目几乎不用。

> ⚠️注意：`count(*)`快仅限于无where条件；加where后MyISAM同样要扫描。

---

### 3. Memory（Heap引擎，内存表）
全部数据放在内存，磁盘只存表结构`.frm`。
#### 核心特性
1. 数据驻留内存，断电全部丢失
2. **表级锁**
3. 支持哈希索引、B‑Tree索引；哈希索引等值查询极快，范围查询弱
4. 不支持BLOB、TEXT大字段
5. 最大大小受参数 `max_heap_table_size` 限制

#### 适用场景
临时中间计算、高频临时查询缓存；临时数据，不能存持久业务数据。

> 和临时表不一样：Memory是显式创建；会话临时表销毁自动释放。

---

### 4. Archive 归档引擎
专门做大量归档数据。
#### 核心特性
1. 数据高度压缩，磁盘占用很小
2. **只支持INSERT、SELECT**，不支持UPDATE/DELETE
3. 无索引，只能全表扫描
4. 不支持事务、锁机制简单

#### 适用场景
海量日志归档，只插入，几乎不修改、很少查询。

---

### 5. CSV引擎
数据以标准CSV文本文件存储，磁盘直接就是`.csv`文件。
#### 核心特性
1. 可以直接用Excel打开编辑csv文件
2. **不支持索引**
3. 不支持事务
4. 字段不能为NULL

#### 适用场景：数据导入导出交换，很少业务使用。

---

### 6. Blackhole 黑洞引擎
黑洞，写入全部丢弃，只记录binlog。
- INSERT/UPDATE/DELETE执行成功，但数据不落地磁盘；select永远返回空
- 主要用于主从复制的中继节点，做binlog转发；业务几乎不用。

---

### 7. Federated / FederatedX
访问远程MySQL表，本地不存真实数据，本地表相当于远程表代理。
> MySQL8.0默认移除，需要插件安装；网络故障直接不可用，生产慎用。

---

#### 关键对比表
|特性|InnoDB|MyISAM|Memory|Archive|
|---|---|---|---|---|
|事务|✅|❌|❌|❌|
|行锁|✅|❌(表锁)|❌(表锁)|❌|
|MVCC|✅|❌|❌|❌|
|外键|✅|❌|❌|❌|
|聚簇索引|✅|❌|❌|❌|
|缓存|BufferPool|OS缓存|内存|内存|
|数据持久化|磁盘|磁盘|内存(丢失)|磁盘压缩|
|count(*)|扫描|快速|扫描|扫描|

#### 选型总结
1. **业务表一律 InnoDB**：支持事务、行锁、崩溃安全，现代MySQL标准。
2. MyISAM：新项目尽量避免；只有静态归档只读场景考虑。
3. Memory：临时高速缓存，不能存重要数据。
4. Archive：海量日志归档。
5. CSV/Blackhole/Federated：特定工具场景，业务开发几乎不用。

## 五、InnoDB 引擎
### 5.1 逻辑存储结构

```
表空间（Tablespace，.ibd 文件）
└── 段（Segment）：数据段、索引段、回滚段等
    └── 区（Extent）：固定 1MB = 64 个页
        └── 页（Page）：固定 16KB，InnoDB 与磁盘 IO 的最小单位
            └── 行（Row）：实际数据记录
```

### 5.2 内存架构（Buffer Pool）

**Buffer Pool（缓冲池）**：
- 缓存磁盘上的数据页和索引页，读先查 Buffer Pool，命中直接返回，未命中从磁盘加载并放入
- 写先改 Buffer Pool 中的页（脏页），后台线程异步刷盘
- 使用改进的 LRU 算法（young/old 两区域，防止全表扫描把热数据挤出）

**Change Buffer**：对二级索引的写操作，若对应页不在 Buffer Pool，先记入 Change Buffer，读时合并，减少随机 IO。

**Adaptive Hash Index**：InnoDB 自动检测热点查询，对高频等值查询建内存哈希索引，O(1) 访问。

**Log Buffer**：redo log 写磁盘前的内存缓冲，减少 IO 频次。

### 5.3 磁盘架构

| 文件 | 作用 |
|------|------|
| `ib_logfile0/1` | redo log，循环写入 |
| undo log tablespace | 回滚日志，支持事务回滚和 MVCC |
| Doublewrite Buffer | 脏页刷盘前先写此处，防止页写入一半时崩溃（partial write）|

### 5.4 后台线程

| 线程 | 作用 |
|------|------|
| Master Thread | 核心调度线程，调度脏页刷盘、undo log 回收等 |
| IO Thread | 处理异步 IO（读/写/redo/undo 各有专属） |
| Purge Thread | 清理已提交事务的 undo log，回收空间 |
| Page Cleaner Thread | 专门刷脏页，从 Master Thread 分离，减少用户线程等待 |

---

## 六、事务与并发控制
* **事务 (Transaction)**：是一组 SQL 操作，这一组 SQL 是一个**不可分割的逻辑单元**。单元内多条 SQL，**要么全部成功执行，要么全部失败回滚，不会出现做一半的中间状态**。
### 6.1 基本语法

```sql
START TRANSACTION;   -- 开启事务（也可用 BEGIN）

UPDATE account SET balance = balance - 100 WHERE id = 1;
UPDATE account SET balance = balance + 100 WHERE id = 2;

COMMIT;    -- 全部成功，提交永久生效
ROLLBACK;  -- 任意失败，回滚所有操作
```

### 6.2 ACID 特性

| 特性 | 含义                   | 由什么保证 |
|------|----------------------|----------|
| **原子性**（Atomicity） | 事务内所有操作要么全部成功，要么全部回滚 | undo log |
| **一致性**（Consistency） | 事务前后数据满足所有约束,业务数据始终保持合法状态        | 原子性+隔离性+持久性共同保证 |
| **隔离性**（Isolation） | 并发事务互不干扰，每个事务看到一致快照  | MVCC + 锁 |
| **持久性**（Durability） | 提交后数据永久保存，崩溃不丢失      | redo log（WAL）|
### 6.3 三大并发问题

| 问题 | 具体场景 |
|------|---------|
| **脏读** | 读到其他事务**未提交**的数据（对方回滚后读到假数据） |
| **不可重复读** | 同一事务两次读同一行，值不同（对方 UPDATE 并提交了） |
| **幻读** | 同一事务两次查同一范围，**行数**不同（对方 INSERT 并提交了） |

不可重复读 = 已有行的**值**变了；幻读 = 凭空**多出了新行**。两种问题由不同机制解决。

### 6.4 redo log（持久性保证）
#### WAL 核心思想
**WAL = Write‑Ahead Logging，预写日志**
核心规则：**WAL 只管**磁盘上的写入顺序，修改数据页（磁盘上的数据页）之前，必须先把这次变更对应的日志刷到磁盘。，日志刷盘成功，才算事务提交成功；数据页可以晚点刷磁盘**。
> **先写日志，后写数据**
1. 更新buffer‑pool里的内存页；
2. **先把本次变更写入 redo log（WAL日志），刷到磁盘**；
3. 事务commit成功；
4. **真正的数据页什么时候刷磁盘不要求立刻做，后台线程慢慢刷（flush）**。
##### 如果没有WAL会怎样？
事务提交，必须立刻把修改的数据页刷到磁盘。
- 一条事务可能修改多个数据页；随机IO，磁盘随机写很慢，性能极差。
- 一旦刷到一半宕机，部分页落盘、部分没落盘，数据损坏。
##### WAL带来两大好处
1. **性能提升**：redo log 是**顺序IO**，顺序写磁盘远快于随机写；脏页可以后台批量刷盘。
2. **崩溃安全（持久性D）**：只要redo log已经落盘，就算内存脏页还没刷进ibd数据文件，数据库崩溃重启，依靠redo log重放，把没落地的数据页恢复出来，保证提交的事务不丢失。

**redo log 记录的是物理层面的变更**（磁盘第几页第几个字节改成了什么），只记新值，不记旧值：

```
LSN（日志序列号）: 1234567
状态: prepare / commit
表空间ID: 5   页号: 38   偏移: 1024
新值: 0xE4B88AE6B5B7（'上海'的 UTF-8 字节）
```

redo log 是固定大小的**环形文件**（默认两个文件各 48MB 循环写），写满了从头覆盖，不会无限增长。

**刷盘时机**由 `innodb_flush_log_at_trx_commit` 控制：

| 值 | 行为 | 安全性 |
|----|------|--------|
| 1（默认） | 每次事务提交都 write + fsync 到磁盘 | 最安全，不丢数据 |
| 2 | 每次提交 write 到 OS buffer，每秒 fsync 到磁盘 | MySQL 崩溃不丢，断电丢最多 1 秒 |
| 0 | 每秒 write + fsync | MySQL 崩溃也可能丢最多 1 秒 |

### 6.5 undo log（原子性 + MVCC 基础）

**undo log 记录的是逻辑层面的逆操作**（INSERT → 对应 DELETE；UPDATE → 记录旧值），只记旧值，用于回滚和 MVCC：

```
事务ID: txn_8823
操作:   UPDATE 的逆操作
内容:   UPDATE merchant SET city='北京' WHERE id=1
旧版本指针: → 上一个历史版本（MVCC 版本链）
```

两个用途：
- **事务回滚**：执行 ROLLBACK 时照着 undo log 把数据改回去
- **MVCC 多版本读**：事务 A 修改数据时，事务 B 来读，顺着 undo log 的旧版本指针找到修改前的值，不阻塞

undo log 采用**版本链**结构：每条记录有 `roll_pointer` 指向上一版本，形成历史版本链，是 MVCC 的数据基础。

### 6.6 binlog

binlog 是 MySQL Server 层的日志（不属于 InnoDB），ROW 格式下**同时记录旧值和新值**：

```
时间戳: 2026-08-31 09:05:00
事件类型: UPDATE_ROWS_EVENT
表: mhp.merchant

before: id=1, city='北京', intro='专业妆娘', avg_score=4.8
after:  id=1, city='上海', intro='专业妆娘', avg_score=4.8
```

**用途**：
- 主从复制（从库重放 binlog 保持同步）
- CDC（Canal 监听 binlog 同步到 ES/Redis 等）
- 时间点恢复（全量备份 + binlog 重放恢复到任意时刻）
- 闪回（ROW 格式记录了旧值，可以把 DELETE 反转成 INSERT 找回误删数据）

**刷盘时机**由 `sync_binlog` 控制，生产环境设为 1（每次提交都 fsync）。

### 6.7 三种日志对比与事务提交流程（两阶段提交）

| 日志 | 层级 | 记录内容 | 旧值 | 新值 | 用途 |
|------|------|---------|------|------|------|
| undo log | InnoDB | 逻辑逆操作 | ✅ | ❌ | 回滚、MVCC |
| redo log | InnoDB | 物理页变更 | ❌ | ✅ | 崩溃恢复 |
| binlog | Server | 行级变更 | ✅ | ✅ | 主从复制、CDC、时间点恢复 |

**完整事务提交流程**以 `UPDATE merchant SET city='上海' WHERE id=1`（原 city='北京'）为例：

```
① 写 undo log（写入 Buffer‑Pool 内 undo 页内存，不直接刷 undo 表空间磁盘）
      记录逆操作：UPDATE merchant SET city='北京' WHERE id=1
      供回滚和 MVCC 使用，对 undo 页的修改也会生成对应的 redo log，交由 WAL 机制保护。

② 修改 Buffer Pool（内存）
      找到 id=1 所在的数据页，将内存中的city 改成'上海'，该页变为脏页
---------两阶段-Prepare阶段-------------------
③ redo log 写入磁盘，标记 prepare 状态
      记录物理变更（第38页偏移1024改成'上海'的字节）
      此时事务还未正式提交
      这个时候把整个redo log buffer全部刷到磁盘redo log文件。
      buffer 里既包含**undo 页改动的 redo，也包含业务数据页改动的redo，一并落盘，同时打上 prepare 标记。
④ binlog buffer写入磁盘（MySQL Server 层逻辑日志）
      记录 before='北京' / after='上海' 的行级变更
---------两阶段-Commit阶段-------------------
⑤ redo log 标记 commit 状态
        向磁盘 redo log 追加写入`commit`标记
        ← 这一刻事务才算真正提交完成
⑥ Buffer Pool 脏页异步刷盘（后台线程，不在事务提交的关键路径上）
      '上海' 真正写入磁盘数据文件（.ibd）
```

**为什么 redo log 要分 prepare / commit 两步（两阶段提交）**：
保证同一个事务，redo log 与 binlog 状态完全一致。要么两边都认为事务提交，要么两边都认为事务未提交，避免主从数据不一致。**
崩溃恢复判断逻辑：重启扫描 redo log，看到`prepare`标记但没有`commit`标记时，去检查 binlog 是否完整存在。

| 崩溃时机         | redo log | binlog | 恢复结果                                       |
|--------------|---------|--------|--------------------------------------------|
| ③prepara刷盘之前 | 无 | 无 | 全部改动只在内存，宕机内存清空，磁盘什么都没变。无需执行 undo 回滚，事务直接消失，天然一致，两边一致 |
| ③④ 之间        | prepare | 无 | 回滚（没有 binlog 视为未提交），redo 已经落盘。重启，依靠 redo 把【业务脏页 + undo 脏页】重建回内存 BP，**调用内存里重建出来的 undo log 执行真正回滚**，撤销修改 |
| ④⑤ 之间        | prepare | 有 | redo 把业务页、undo 页重建回内存。binlog 存在，代表事务应当生效，不需要 undo 回滚；直接在 redo log 补写 commit 标记，事务生效。                                           |
| ⑤ 之后         | commit | 有 | 正常，无需处理                                    |

**生产环境标准配置**：
```sql
innodb_flush_log_at_trx_commit = 1   -- redo log 每次提交都落盘
sync_binlog = 1                       -- binlog 每次提交都落盘
```

### 6.8 MVCC（多版本并发控制）

**作用**：不加锁让读操作看到一致历史快照，实现**读写不阻塞**。

**每一行数据的隐藏字段**：每行数据有 `DB_TRX_ID`（最近修改的事务ID）和 `DB_ROLL_PTR`（指向 undo log 上一版本）。

**ReadView**：快照读时生成，包含四个字段：

| 字段 | 说明 |
|------|------|
| `m_ids` | 生成时所有活跃事务（已开启未提交）的 ID 列表 |
| `min_trx_id` | m_ids 中最小值 |
| `max_trx_id` | 下一个将分配的事务 ID（历史最大+1） |
| `creator_trx_id` | 创建该 ReadView 的事务 ID |

**可见性判断**（对版本链中每个版本的 `trx_id`）：
```
db_trx_id == creator_trx_id          → 自己修改的，可见
db_trx_id < min_trx_id               → 已提交的旧事务，可见
db_trx_id >= max_trx_id              → ReadView 生成后才开启，不可见
min_trx_id <= db_trx_id < max_trx_id → 在 m_ids 中 → 未提交，不可见；不在 → 已提交，可见
```

沿版本链从最新往旧遍历，**返回第一个可见版本**。

### 6.9 四种隔离级别详解

隔离级别本质是两件事的预设组合：**ReadView 生成时机**（MVCC 行为）+ **当前读自动加什么锁**。

| 隔离级别 | 快照读行为 | 当前读加锁 | 能解决 |
|---------|----------|----------|-------|
| READ UNCOMMITTED | 不用 MVCC，直接读最新（含未提交） | 只加行锁 | 无 |
| **READ COMMITTED（RC）** | 每次快照读**重新生成** ReadView | 只加行锁 | 脏读 |
| **REPEATABLE READ（RR，默认）** | 第一次生成 ReadView，**后续复用** | 行锁 + 间隙锁（临键锁） | 脏读、不可重复读、幻读（基本） |
| SERIALIZABLE | SELECT 全升级为当前读 | 全部加锁，完全串行 | 三者全解决 |
**只要是 InnoDB 的行锁、X 锁、S 锁、间隙锁，锁的持有周期统一：从加锁成功开始，一直保持到整个事务 `commit / rollback`，和隔离级别无关。**
隔离级别**不改变锁什么时候释放**，隔离级别只改变：**会不会生成间隙锁 / 临键锁**。
---

**READ UNCOMMITTED**：直接读内存最新数据页，不管对方是否提交。

```sql
-- 事务 B 未提交，事务 A（READ UNCOMMITTED）能读到 B 的修改
-- B 若回滚，A 读到的就是假数据 → 脏读
```

几乎不用，脏读在生产中不可接受。

---

**READ COMMITTED（RC）**：

每次快照读重新生成 ReadView，能看到其他事务最新提交的数据 → **无法解决不可重复读**。

```sql
-- 初始：name='张三'
-- 事务 A（RC）
SELECT name FROM t WHERE id = 1;   -- 读到 '张三'

    -- 事务 B 提交：UPDATE t SET name='李四' WHERE id = 1;

SELECT name FROM t WHERE id = 1;   -- 重新生成 ReadView，读到 '李四' ← 不可重复读！
```

当前读只加行锁，不加间隙锁 → **无法解决幻读**（其他事务可以在范围内插入新行）。

适用场景：需要读到最新提交数据（如库存查询、抢购），接受不可重复读。

---

**REPEATABLE READ（RR，MySQL 默认）**：

第一次快照读生成 ReadView，整个事务内复用 → **解决不可重复读**。

```sql
-- 初始：name='张三'
-- 事务 A（RR）
SELECT name FROM t WHERE id = 1;   -- 生成 ReadView，读到 '张三'

    -- 事务 B 提交：UPDATE t SET name='李四'

SELECT name FROM t WHERE id = 1;   -- 复用 ReadView，仍读到 '张三' ✅
```

快照读下幻读也被 MVCC 解决（B 插入的新行对 A 的 ReadView 不可见）。

当前读下，自动加临键锁（行锁 + 间隙锁）→ **解决幻读**：
在select 语句后面加 for update，将这条语句从快照读变成当前读，会从现在开始到事务结束都加上临键锁

```sql
-- 事务 A（RR）
SELECT * FROM t WHERE id > 5 FOR UPDATE;  -- 加临键锁，锁住 id>5 的所有间隙

    -- 事务 B：INSERT INTO t VALUES (7, ...); ← 被阻塞，间隙锁阻止插入

SELECT * FROM t WHERE id > 5 FOR UPDATE;  -- 结果不变 ✅
```

**一个特殊场景——快照读和当前读混用时出现幻读**：

```sql
-- 事务 A（RR）
SELECT * FROM t WHERE id = 7;           -- 快照读，空（id=7不存在）

    -- 事务 B：INSERT INTO t VALUES(7,'xxx'); COMMIT;

UPDATE t SET name='yyy' WHERE id = 7;   -- 当前读！读到了 B 插入的行并修改成功
SELECT * FROM t WHERE id = 7;           -- 此时能看到 id=7 ← 类幻读
```

原因：UPDATE 是当前读，读到 B 的行后修改，该行 trx_id 变成当前事务 ID，ReadView 判断为自己修改的，变为可见。**避免方法：全程用 `FOR UPDATE` 当前读，不要混用。**

适用场景：大多数业务的默认选择。

---

**SERIALIZABLE**：所有普通 SELECT 自动升级为 `LOCK IN SHARE MODE`，完全串行，没有并发。性能最差，适用于金融对账等极致一致性场景。

---

**选型建议**：

| 场景 | 推荐级别 | 原因 |
|------|---------|------|
| 绝大多数业务 | **REPEATABLE READ** | MySQL 默认，平衡一致性和性能 |
| 需要读到最新提交（库存、抢购） | **READ COMMITTED** | 避免长事务 ReadView 看不到最新数据 |
| 报表、统计类长事务 | **REPEATABLE READ** | 整个统计过程看到一致快照 |
| 金融对账、极致一致性 | **SERIALIZABLE** | 完全串行，承受性能损失 |

```sql
SELECT @@transaction_isolation;                                       -- 查看当前
SET SESSION TRANSACTION ISOLATION LEVEL REPEATABLE READ;              -- 设置会话
SET GLOBAL TRANSACTION ISOLATION LEVEL READ COMMITTED;                -- 设置全局
```

### 6.10 一条 DML 语句的完整生命周期

以 `UPDATE merchant SET city='上海' WHERE id=1`（原 city='北京'）为主线，串联 Server 层与 InnoDB 层所有组件。

**Server 层（SQL 处理）**

```
① 连接器
      验证用户名/密码，检查库级权限，建立连接

② 解析器（Parser）
      词法分析：把 SQL 字符串切成 Token（UPDATE / merchant / SET / city ...）
      语法分析：构造语法树，检查语法是否合法

③ 预处理器
      检查表名、列名是否存在，验证用户对该表有无操作权限

④ 优化器（Optimizer）
      生成执行计划：选择索引，决定 JOIN 顺序
      本例：id 是主键，等值查询，直接走聚集索引

⑤ 执行器（Executor）
      调用 InnoDB 存储引擎接口，按执行计划驱动数据读写
```

**InnoDB 层（存储引擎）**

```
⑥ 当前读 —— 找到目标行
      通过聚集索引定位 id=1 的数据页
        命中 Buffer Pool → 直接使用
        未命中         → 从磁盘 .ibd 文件读入 Buffer Pool

⑦ 加锁
      对 id=1 这行加行级 X 锁
      （RR 级别默认临键锁；唯一索引等值命中存在行 → 退化为纯行锁）
      在表上加意向排他锁（IX）

⑧ 写 undo log
      记录逆操作：UPDATE merchant SET city='北京' WHERE id=1
      供事务回滚和 MVCC 版本链使用

⑨ 修改 Buffer Pool
      将 id=1 数据页中 city 字段改为'上海'，该页标记为脏页
      此时磁盘上数据仍是'北京'

⑩ redo log 写入 Log Buffer → 刷盘，标记 prepare
      记录物理变更（第 N 页偏移 M 字节改为'上海'的字节）
      prepare 状态：事务尚未正式提交

⑪ binlog 写入磁盘
      ROW 格式记录 before='北京' / after='上海' 行级变更

⑫ redo log 标记 commit
      ← 这一刻事务正式提交，客户端收到成功响应，行锁释放

⑬ 后台 Page Cleaner 线程异步刷脏页（不在提交关键路径上）
      将 Buffer Pool 中脏页写入 .ibd 磁盘文件
      刷盘完成后 redo log 对应位置的 checkpoint 推进，该段日志可被覆盖
```

**崩溃后如何恢复**

```
重启 → 扫描 redo log
  ├─ redo log 有 commit                 → 重放 redo log 补刷未落盘的脏页
  ├─ redo log 有 prepare + binlog 有记录 → 视为已提交，同上
  └─ redo log 只有 prepare，binlog 无记录 → 视为未提交，用 undo log 回滚
```

**SELECT 快照读路径（对比）**

```
① ~ ⑤ Server 层相同

⑥ 生成 ReadView
      RR：整个事务内第一次快照读时生成，后续复用
      RC：每次快照读重新生成

⑦ 在 Buffer Pool 中找到目标行（或从磁盘加载）

⑧ 沿 undo log 版本链从最新往旧遍历
      返回第一个对当前 ReadView 可见的版本
      全程不加任何锁，读写互不阻塞
```

**各层职责一览**

| 组件 | 层级 | 职责                                 |
|------|------|------------------------------------|
| 连接器 / 解析器 / 优化器 | Server | SQL 解析、权限校验、执行计划                   |
| 执行器 | Server | 驱动存储引擎接口，汇聚结果集返回客户端                |
| Buffer Pool | InnoDB 内存 | 缓存数据页，读写都先过这里                      |
| undo log | InnoDB | 记录旧值，支持回滚和 MVCC 版本链                |
| redo log | InnoDB | 记录物理新值，WAL + 两阶段提交保证持久性            |
| binlog | Server | 记录行级 before/after，主从 / CDC / 时间点恢复 |
| 锁（行锁 / 间隙锁） | InnoDB | 并发写隔Fi离，防止脏写和幻读                    |
| MVCC / ReadView | InnoDB | 并发读隔离，读不阻塞写                        |

---

## 七、锁

### 7.1 快照读 vs 当前读（理解锁的前提）

| | 快照读 | 当前读 |
|--|--|--|
| 触发方式 | 普通 `SELECT` | `SELECT FOR UPDATE` / `LOCK IN SHARE MODE` / `UPDATE` / `DELETE` / `INSERT` |
| 读的是什么 | MVCC 历史快照，**不加锁** | 最新已提交数据，**必须加锁** |

**UPDATE / DELETE 也涉及读**：WHERE 条件查找目标行用的是当前读，找到的同时就加了锁，然后再做修改。锁只跟当前读有关，普通 SELECT 不加任何行锁。

### 7.2 全局锁（手动）

对整个数据库实例加锁，所有表只读，DML/DDL 全部阻塞。

```sql
FLUSH TABLES WITH READ LOCK;   -- 加锁
UNLOCK TABLES;                 -- 释放
```

用途：全库逻辑备份。InnoDB 推荐用 `mysqldump --single-transaction`，利用 MVCC 快照备份，不需要全局锁。

### 7.3 表级锁

#### 表锁（READ / WRITE）—— 手动

```sql
LOCK TABLES student READ;    -- 自己和其他连接都只能读
LOCK TABLES student WRITE;   -- 只有自己能读写，其他连接全阻塞
UNLOCK TABLES;
```

#### MDL（元数据锁）—— 自动，感知不到

访问表时系统自动加，不能手动控制：
- DML → 自动加 MDL **读锁**，多个 DML 可并发
- DDL → 自动加 MDL **写锁**，与所有 DML 互斥

作用：防止改数据时别人修改了表结构，导致数据错乱。

#### 意向锁（IS / IX）—— 自动，感知不到

加行锁前系统自动在表上加意向锁：
- 准备加行 S 锁 → 自动加 **IS**（意向共享锁）
- 准备加行 X 锁 → 自动加 **IX**（意向排他锁）

意向锁之间**互相兼容**；只跟表锁互斥（表写锁和 IS/IX 都互斥，表读锁和 IX 互斥）。

**为什么需要意向锁**：没有它，加表锁前需逐行检查是否有行锁，O(n)；有了它，O(1) 直接看表上有无意向锁。

### 7.4 行级锁（全部自动加）

InnoDB 行锁作用在**索引**上，没有索引时退化为锁全表。分 S 锁（共享）和 X 锁（排他）。

#### 行锁（Record Lock）

锁住**一条已存在的索引记录**，防止别人修改这行。

```sql
UPDATE t SET name='x' WHERE id=5;              -- 自动加 X 行锁
SELECT * FROM t WHERE id=5 FOR UPDATE;         -- X 行锁
SELECT * FROM t WHERE id=5 LOCK IN SHARE MODE; -- S 行锁
```

解决**不可重复读**：A 持有行锁，B 无法修改该行，A 两次读结果一致。

#### 间隙锁（Gap Lock）

锁住**两条索引记录之间的空隙**，只防 INSERT，不影响已有行的任何读写。只在 RR 级别存在。

```
表中 id：1    5    10
间隙：(-∞,1)  (1,5)  (5,10)  (10,+∞)

锁住 (5,10) 间隙：
  ✅ UPDATE id=5 或 id=10（已有行，行锁管）
  ✅ INSERT id=11（不在间隙）
  ❌ INSERT id=6/7/8/9（在间隙内，被阻塞）
```

解决**幻读**：锁住范围内的空位，别人插不进来，A 两次查行数一致。

间隙锁之间**互相兼容**（两个事务可同时持有同一间隙锁，因为都只是防 INSERT）。

#### 临键锁（Next-Key Lock）= 行锁 + 左侧间隙锁

InnoDB 在 RR 级别的**默认行锁算法**，同时锁住一条记录和其左侧间隙。

```
临键锁 (1,5] = 间隙锁(1,5) + 行锁[5]
→ 别人不能修改 id=5（行锁）
→ 别人不能在 (1,5) 区间插入新行（间隙锁）
```

#### 插入意向锁（Insert Intention Lock）

INSERT 操作在真正插入前加的特殊间隙锁，表示"我要往这个间隙的某位置插入"。多个事务可同时持有同一间隙的插入意向锁（位置不同时）。插入意向锁与间隙锁**互斥**，这正是间隙锁能阻塞 INSERT 的原因。

### 7.5 哪些锁是自动加的

| 锁 | 自动 / 手动 | 触发时机 |
|---|---|---|
| 全局锁 | **手动** | `FLUSH TABLES WITH READ LOCK` |
| 表读锁 / 写锁 | **手动** | `LOCK TABLES` |
| MDL | **自动** | 访问表时系统自动加 |
| 意向锁 IS / IX | **自动** | 加行锁前系统自动加 |
| 行锁（Record Lock） | **自动** | UPDATE / DELETE / INSERT / SELECT FOR UPDATE |
| 间隙锁（Gap Lock） | **自动** | RR 级别当前读命中不存在的记录范围 |
| 临键锁（Next-Key Lock） | **自动** | RR 级别当前读的默认算法 |
| 插入意向锁 | **自动** | INSERT 执行时 |

### 7.6 锁的退化

InnoDB 默认用临键锁，以下情况自动退化：

**退化为纯行锁（去掉间隙锁）**：查询命中**唯一索引**的**精确等值**且记录存在。

```sql
-- id 是主键，id=5 存在 → 只锁这行，不锁间隙
SELECT * FROM t WHERE id = 5 FOR UPDATE;
```

唯一索引保证不可能再插入另一个 id=5，间隙锁没有意义。

**退化为纯间隙锁（去掉行锁）**：等值查询但记录**不存在**。

```sql
-- id=7 不存在，落在 (5,10) 间隙 → 没有行可锁，只锁间隙
SELECT * FROM t WHERE id = 7 FOR UPDATE;
```

**退化为锁全表**：查询列**没有索引**，全表扫描每行都加行锁。

```sql
UPDATE t SET age=20 WHERE name='张三';  -- name 无索引 → 锁全表
```

### 7.7 并发问题完整解决方案

| 并发问题 | 靠什么解决 | 说明 |
|---------|----------|------|
| 脏读 | 隔离级别 >= RC | ReadView 只看已提交版本 |
| 不可重复读 | 行锁（当前读）/ MVCC（快照读） | 行锁阻止对方修改；快照读复用 ReadView |
| 幻读（快照读） | MVCC（RR 复用 ReadView） | B 插入的新行对 A 的 ReadView 不可见 |
| 幻读（当前读） | 间隙锁 / 临键锁 | 锁住间隙，B 无法插入新行 |

**隔离级别 = 加锁策略的预设组合，不同级别下数据库自动加的锁种类和范围不同，并发行为也不同。**

---

## 八、MySQL 8.0 新特性速览

```sql
-- CTE（公用表表达式，替代嵌套子查询，可读性更好）
WITH cte AS (
    SELECT s_id, AVG(s_score) AS avg_score FROM score GROUP BY s_id
)
SELECT s.s_name, cte.avg_score FROM student s
JOIN cte ON s.s_id = cte.s_id WHERE cte.avg_score > 80;

-- 窗口函数（见 3.12 节）

-- JSON 函数
SELECT JSON_EXTRACT('{"name":"张三"}', '$.name');   -- → '张三'

-- CHECK 约束（8.0.16+）
CREATE TABLE product (
    price DECIMAL(10,2),
    CHECK (price > 0)
);
```
