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

### 4.1 是什么 + B+ 树底层

索引是帮助数据库高效查询的**有序数据结构**，使查找从 O(n) 全表扫描降到 O(log n)。**代价**：写入时维护索引树变慢；占用磁盘空间。

InnoDB 默认使用 **B+ 树**索引：
- **非叶子节点只存 key（导航键）**，同等页大小容纳更多 key，树更矮（通常 2~3 层覆盖千万级数据）
- **所有数据只存在叶子节点**，查询路径长度固定，性能稳定
- **叶子节点双向链表连接**，范围查询直接遍历链表，无需回溯

存储单位：页固定 **16KB**，区（Extent）= **1MB** = 64 页。

### 4.2 索引分类

**按功能**：主键索引 / 唯一索引 / 普通索引 / 全文索引

**按存储形式（核心）**：

| 类型 | 叶子节点存什么 | 说明 |
|------|------------|------|
| **聚集索引** | 完整行数据 | 每表有且只有一个；无主键时用第一个 NOT NULL UNIQUE 列，再没有则用隐藏 rowid |
| **二级索引** | 主键值 | 查到主键后还需回聚集索引取完整数据 —— **回表** |

### 4.3 创建与管理

```sql
CREATE INDEX idx_name ON student(s_name);
CREATE UNIQUE INDEX idx_email ON student(email);
CREATE INDEX idx_name_birth ON student(s_name, s_birth);  -- 联合索引，字段顺序重要
DROP INDEX idx_name ON student;
SHOW INDEX FROM student;
```

### 4.4 最左前缀原则

联合索引 `(a, b, c)` 等价于建了 `(a)` / `(a,b)` / `(a,b,c)` 三个索引：

```sql
-- ✅ 命中（WHERE 中字段顺序无关，优化器会调整）
WHERE a = 1
WHERE a = 1 AND b = 2

-- ❌ 未命中（跳过了最左列 a）
WHERE b = 2
WHERE b = 2 AND c = 3

-- ⚠️ 范围查询右边的列失效：a > 1 AND b = 2 中 b 的索引失效
-- 用 >= / <= 代替 > / < 可避免此问题
```

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

### 4.6 覆盖索引与回表

**覆盖索引**：查询所需字段全部在索引中，无需回表。

```sql
-- 联合索引 (name, age)
SELECT name, age FROM student WHERE name = '张三';   -- ✅ 覆盖，不回表
SELECT name, age, email FROM student WHERE name = '张三';  -- ❌ email 不在索引，需回表
```

`SELECT *` 几乎必然触发回表，高频查询应按需选字段或设计覆盖索引。

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

### 6.1 基本语法

```sql
START TRANSACTION;   -- 开启事务（也可用 BEGIN）

UPDATE account SET balance = balance - 100 WHERE id = 1;
UPDATE account SET balance = balance + 100 WHERE id = 2;

COMMIT;    -- 全部成功，提交永久生效
ROLLBACK;  -- 任意失败，回滚所有操作
```

### 6.2 ACID 特性

| 特性 | 含义 | 由什么保证 |
|------|------|----------|
| **原子性**（Atomicity） | 事务内所有操作要么全部成功，要么全部回滚 | undo log |
| **一致性**（Consistency） | 事务前后数据满足所有约束 | 原子性+隔离性+持久性共同保证 |
| **隔离性**（Isolation） | 并发事务互不干扰，每个事务看到一致快照 | MVCC + 锁 |
| **持久性**（Durability） | 提交后数据永久保存，崩溃不丢失 | redo log（WAL）|

### 6.3 三大并发问题

| 问题 | 具体场景 |
|------|---------|
| **脏读** | 读到其他事务**未提交**的数据（对方回滚后读到假数据） |
| **不可重复读** | 同一事务两次读同一行，值不同（对方 UPDATE 并提交了） |
| **幻读** | 同一事务两次查同一范围，**行数**不同（对方 INSERT 并提交了） |

不可重复读 = 已有行的**值**变了；幻读 = 凭空**多出了新行**。两种问题由不同机制解决。

### 6.4 redo log（持久性保证）

**WAL（Write-Ahead Logging）**：事务提交时先把修改写到 redo log（顺序 IO，极快），再异步刷脏页到磁盘（随机 IO）。崩溃重启时从 redo log 恢复未刷盘的修改。

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
① 写 undo log
      记录逆操作：UPDATE merchant SET city='北京' WHERE id=1
      供回滚和 MVCC 使用

② 修改 Buffer Pool（内存）
      找到 id=1 所在的数据页，city 改成'上海'，该页变为脏页

③ redo log 写入磁盘，标记 prepare 状态
      记录物理变更（第38页偏移1024改成'上海'的字节）
      此时事务还未正式提交

④ binlog 写入磁盘
      记录 before='北京' / after='上海' 的行级变更

⑤ redo log 标记 commit 状态
      ← 这一刻事务才算真正提交完成

⑥ Buffer Pool 脏页异步刷盘（后台线程，不在事务提交的关键路径上）
      '上海' 真正写入磁盘数据文件（.ibd）
```

**为什么 redo log 要分 prepare / commit 两步（两阶段提交）**：

| 崩溃时机 | redo log | binlog | 恢复结果 |
|----------|---------|--------|---------|
| ③ 之前 | 无 | 无 | 回滚，两边一致 |
| ③④ 之间 | prepare | 无 | 回滚（没有 binlog 视为未提交） |
| ④⑤ 之间 | prepare | 有 | **提交**，补写 redo log commit |
| ⑤ 之后 | commit | 有 | 正常，无需处理 |

**生产环境标准配置**：
```sql
innodb_flush_log_at_trx_commit = 1   -- redo log 每次提交都落盘
sync_binlog = 1                       -- binlog 每次提交都落盘
```

### 6.8 MVCC（多版本并发控制）

**作用**：不加锁让读操作看到一致历史快照，实现**读写不阻塞**。

**隐藏字段**：每行数据有 `DB_TRX_ID`（最近修改的事务ID）和 `DB_ROLL_PTR`（指向 undo log 上一版本）。

**ReadView**：快照读时生成，包含四个字段：

| 字段 | 说明 |
|------|------|
| `m_ids` | 生成时所有活跃事务（已开启未提交）的 ID 列表 |
| `min_trx_id` | m_ids 中最小值 |
| `max_trx_id` | 下一个将分配的事务 ID（历史最大+1） |
| `creator_trx_id` | 创建该 ReadView 的事务 ID |

**可见性判断**（对版本链中每个版本的 `trx_id`）：
```
trx_id == creator_trx_id          → 自己修改的，可见
trx_id < min_trx_id               → 已提交的旧事务，可见
trx_id >= max_trx_id              → ReadView 生成后才开启，不可见
min_trx_id <= trx_id < max_trx_id → 在 m_ids 中 → 未提交，不可见；不在 → 已提交，可见
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

| 组件 | 层级 | 职责 |
|------|------|------|
| 连接器 / 解析器 / 优化器 | Server | SQL 解析、权限校验、执行计划 |
| 执行器 | Server | 驱动存储引擎接口，汇聚结果集返回客户端 |
| Buffer Pool | InnoDB 内存 | 缓存数据页，读写都先过这里 |
| undo log | InnoDB | 记录旧值，支持回滚和 MVCC 版本链 |
| redo log | InnoDB | 记录物理新值，WAL + 两阶段提交保证持久性 |
| binlog | Server | 记录行级 before/after，主从 / CDC / 时间点恢复 |
| 锁（行锁 / 间隙锁） | InnoDB | 并发写隔离，防止脏写和幻读 |
| MVCC / ReadView | InnoDB | 并发读隔离，读不阻塞写 |

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
