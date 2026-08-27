# MySQL 系统笔记

---

## 一、数据库操作

```sql
CREATE DATABASE db_name;            -- 创建
DROP DATABASE db_name;              -- 删除
USE db_name;                        -- 切换
SHOW DATABASES;                     -- 查看所有数据库
SHOW TABLES;                        -- 查看当前库的所有表
```

---

## 二、数据类型

| 类型 | 说明 | 使用场景 |
|------|------|---------|
| `INT` | 整数（-21亿~21亿） | 普通整数字段 |
| `BIGINT` | 大整数 | 主键（推荐） |
| `VARCHAR(n)` | 可变长字符串，最多 n 字符 | 姓名、标题 |
| `CHAR(n)` | 定长字符串，不足补空格 | 固定长度如性别'男'/'女' |
| `TEXT` | 长文本（最大 65535 字节） | 文章内容 |
| `DATE` | 日期 `YYYY-MM-DD` | 生日 |
| `DATETIME` | 日期时间 `YYYY-MM-DD HH:MM:SS` | 创建时间 |
| `DECIMAL(p, s)` | 精确小数，p 总位数，s 小数位 | 价格 `DECIMAL(10,2)` |
| `TINYINT(1)` | 布尔值（0/1） | 是否标志 |

---

## 三、表操作

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

常用约束：
- `NOT NULL`：不允许为空
- `DEFAULT value`：默认值
- `UNIQUE`：唯一约束
- `AUTO_INCREMENT`：自增（整数主键）
- `PRIMARY KEY`：主键（自动 NOT NULL + UNIQUE）
- `FOREIGN KEY`：外键

### 修改表结构（ALTER TABLE）

```sql
ALTER TABLE student ADD COLUMN phone VARCHAR(20);          -- 加列
ALTER TABLE student MODIFY COLUMN s_name VARCHAR(100);     -- 改列类型
ALTER TABLE student CHANGE COLUMN email contact_email VARCHAR(100); -- 改列名+类型
ALTER TABLE student DROP COLUMN phone;                     -- 删列
ALTER TABLE student ADD INDEX idx_name (s_name);           -- 加索引
ALTER TABLE student ADD UNIQUE INDEX idx_email (email);    -- 加唯一索引
```

### 删除/清空表

```sql
DROP TABLE student;           -- 删表（结构+数据全删）
TRUNCATE TABLE student;       -- 清空数据，保留结构，自增归零
```

---

## 四、CRUD 增删改查

### INSERT 插入

```sql
-- 单行
INSERT INTO student (s_name, s_birth, s_sex) VALUES ('赵雷', '1990-01-01', '男');

-- 多行（推荐批量插入，效率更高）
INSERT INTO student (s_name, s_birth, s_sex) VALUES
    ('钱电', '1990-12-21', '男'),
    ('孙风', '1990-05-20', '男');

-- 主键冲突时更新（UPSERT）
INSERT INTO student (s_id, s_name) VALUES (1, '赵雷')
ON DUPLICATE KEY UPDATE s_name = '赵雷';
```

### DELETE 删除

```sql
-- ⚠️ 必须加 WHERE，否则删除全表数据！
DELETE FROM student WHERE s_id = 1;
DELETE FROM student WHERE s_sex = '男' AND s_birth < '1990-01-01';
```

### UPDATE 修改

```sql
-- ⚠️ 必须加 WHERE，否则更新全表！
UPDATE student SET s_name = '赵七', s_birth = '1991-01-01' WHERE s_id = 1;

-- 批量更新（对成绩加5分）
UPDATE score SET s_score = s_score + 5 WHERE c_id = 1;
```

### SELECT 完整语法 + 执行顺序

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

> **执行顺序**（非常重要！）：FROM → JOIN → WHERE → GROUP BY → HAVING → SELECT → ORDER BY → LIMIT

---

## 五、WHERE 条件过滤

```sql
-- 比较
WHERE age = 18
WHERE age != 18           -- 或 <>
WHERE age > 18
WHERE age BETWEEN 18 AND 30   -- 含两端，等价 age >= 18 AND age <= 30
WHERE name IN ('张三', '李四')
WHERE name NOT IN ('张三', '李四')

-- 模糊查询 LIKE
WHERE name LIKE '张%'    -- 以张开头（% 匹配任意多字符）
WHERE name LIKE '%三'    -- 以三结尾
WHERE name LIKE '%风%'   -- 包含风
WHERE name LIKE '张_'    -- 张+一个任意字符（_ 匹配一个字符）

-- NULL 判断（不能用 =，只能用 IS）
WHERE email IS NULL
WHERE email IS NOT NULL

-- 逻辑组合
WHERE age > 18 AND city = '北京'
WHERE age < 18 OR age > 60
WHERE NOT (age BETWEEN 18 AND 30)
```

---

## 六、排序（ORDER BY）

```sql
-- 升序（默认）
SELECT * FROM student ORDER BY s_birth;

-- 降序
SELECT * FROM student ORDER BY s_birth DESC;

-- 多列排序：先按平均分降序，平均分相同再按课程编号升序
SELECT c_id, AVG(s_score) AS avg_score
FROM score
GROUP BY c_id
ORDER BY avg_score DESC, c_id ASC;
```

---

## 七、分页（LIMIT）

```sql
-- 前 10 条
SELECT * FROM student LIMIT 10;

-- 第 2 页（每页 10 条）：offset = (页码-1) × 每页数
SELECT * FROM student LIMIT 10 OFFSET 10;
-- 等价简写：LIMIT offset, count
SELECT * FROM student LIMIT 10, 10;
```

---

## 八、聚合函数

| 函数 | 作用 | 无匹配行返回 |
|------|------|------------|
| `COUNT(*)` | 统计行数（含 NULL 行） | 0 |
| `COUNT(列)` | 统计非 NULL 行数 | 0 |
| `SUM(列)` | 求和（忽略 NULL） | NULL |
| `AVG(列)` | 平均值（忽略 NULL） | NULL |
| `MAX(列)` | 最大值 | NULL |
| `MIN(列)` | 最小值 | NULL |

```sql
-- 整张表：无 GROUP BY → 全部行作为1组，输出1行
SELECT COUNT(*), SUM(s_score), AVG(s_score), MAX(s_score), MIN(s_score)
FROM score;

-- 分组：有 GROUP BY → 每组输出1行
SELECT c_id, COUNT(*), AVG(s_score)
FROM score
GROUP BY c_id;
```

---

## 九、GROUP BY + HAVING

### 核心规则

> SELECT 后出现的**普通列**（非聚合函数列），**必须**写在 GROUP BY 里。

```sql
-- ❌ 错误：name 不在 GROUP BY 里
SELECT dept, name, COUNT(*) FROM employee GROUP BY dept;

-- ✅ 正确写法1：name 加入分组
SELECT dept, name, COUNT(*) FROM employee GROUP BY dept, name;

-- ✅ 正确写法2：name 包进聚合函数
SELECT dept, MAX(name), COUNT(*) FROM employee GROUP BY dept;
```

### WHERE vs HAVING

| 对比项 | WHERE | HAVING |
|--------|-------|--------|
| 过滤时机 | GROUP BY **之前**（过滤行） | GROUP BY **之后**（过滤组） |
| 能否用聚合函数 | ❌ 不能 | ✅ 可以 |
| 性能 | 先过滤再聚合，性能好 | 先聚合再过滤，开销大 |

```sql
-- 查询平均成绩 >= 60 的学生（HAVING 对聚合结果过滤）
SELECT s_id, AVG(s_score) AS avg_score
FROM score
GROUP BY s_id
HAVING AVG(s_score) >= 60;

-- 查询上了语文课且平均分 >= 60 的学生（WHERE 先过滤行，HAVING 再过滤组）
SELECT s_id, AVG(s_score) AS avg_score
FROM score
WHERE c_id = 1            -- 先过滤只保留语文成绩
GROUP BY s_id
HAVING AVG(s_score) >= 60;
```

---

## 十、多表连接（JOIN）

```
INNER JOIN（内连接）：两边都有才出现
LEFT JOIN（左连接）：左表全部输出，右边无匹配则补 NULL
RIGHT JOIN（右连接）：右表全部输出，左边无匹配则补 NULL
```

```sql
-- INNER JOIN：只保留两表都匹配的行
SELECT s.s_name, c.c_name, sc.s_score
FROM score sc
INNER JOIN student s ON sc.s_id = s.s_id
INNER JOIN course c ON sc.c_id = c.c_id;

-- LEFT JOIN：左表全部保留，右表无匹配补 NULL
-- 应用：查询所有学生（含无成绩记录的）
SELECT s.s_name, sc.s_score
FROM student s
LEFT JOIN score sc ON s.s_id = sc.s_id;

-- LEFT JOIN + IS NULL 反连接：查询在 A 不在 B 的记录
-- 等价于 NOT IN，但更安全（不受子查询 NULL 影响）
SELECT s.*
FROM student s
LEFT JOIN score sc ON s.s_id = sc.s_id AND sc.c_id = 2
WHERE sc.s_id IS NULL;   -- 右表 NULL = 没有匹配 = 未学过课程2
```

---

## 十一、子查询

```sql
-- IN 子查询：平均分 > 80 的学生信息
SELECT * FROM student
WHERE s_id IN (
    SELECT s_id FROM score GROUP BY s_id HAVING AVG(s_score) > 80
);

-- NOT IN 子查询（⚠️ 子查询返回 NULL 时结果会全为空，生产慎用）
SELECT * FROM student
WHERE s_id NOT IN (SELECT s_id FROM score WHERE c_id = 2);

-- EXISTS 子查询（推荐：语义清晰，大数据量性能更好）
SELECT * FROM student s
WHERE EXISTS (
    SELECT 1 FROM score sc WHERE sc.s_id = s.s_id AND sc.s_score > 80
);

-- NOT EXISTS（比 NOT IN 安全，子查询有 NULL 也不影响）
SELECT * FROM student s
WHERE NOT EXISTS (
    SELECT 1 FROM score sc WHERE sc.s_id = s.s_id AND sc.c_id = 2
);

-- 标量子查询（返回单值）：查成绩最高的学生
SELECT s_name, s_score
FROM score sc
JOIN student s ON sc.s_id = s.s_id
WHERE s_score = (SELECT MAX(s_score) FROM score);

-- FROM 子查询（派生表）：先聚合后筛选
SELECT s.s_name, t.avg_score
FROM student s
JOIN (
    SELECT s_id, ROUND(AVG(s_score), 2) AS avg_score
    FROM score
    GROUP BY s_id
) t ON s.s_id = t.s_id
WHERE t.avg_score > 70;
```

---

## 十二、NULL 处理函数

```sql
-- IFNULL(值, 默认值)：为 NULL 时返回默认值
SELECT s_name, IFNULL(s_score, 0) AS score FROM student LEFT JOIN score USING(s_id);

-- COALESCE(v1, v2, ...)：返回第一个非 NULL 值（标准 SQL，更通用）
SELECT COALESCE(phone, email, '无联系方式') FROM users;

-- NULLIF(v1, v2)：v1 = v2 时返回 NULL，否则返回 v1（常用于防除零）
SELECT total / NULLIF(count, 0) FROM stat;   -- count=0 时返回 NULL，不报错
```

---

## 十三、CASE WHEN 条件表达式

```sql
-- 简单形式（等值匹配）
SELECT s_id,
    CASE s_sex WHEN '男' THEN 'M' WHEN '女' THEN 'F' ELSE '?' END AS gender
FROM student;

-- 搜索形式（范围/复杂条件）
SELECT s_id, s_score,
    CASE
        WHEN s_score >= 90 THEN '优秀'
        WHEN s_score >= 70 THEN '良好'
        WHEN s_score >= 60 THEN '及格'
        ELSE '不及格'
    END AS grade
FROM score;

-- 在聚合中使用（行转列，把多行课程成绩压成一行）
SELECT s_id,
    SUM(CASE c_id WHEN 1 THEN s_score ELSE 0 END) AS 语文,
    SUM(CASE c_id WHEN 2 THEN s_score ELSE 0 END) AS 数学,
    SUM(CASE c_id WHEN 3 THEN s_score ELSE 0 END) AS 英语
FROM score
GROUP BY s_id;
```

---

## 十四、常用内置函数

### 字符串函数

```sql
CHAR_LENGTH('abc')              -- 字符数 → 3
LENGTH('abc')                   -- 字节数（中文 UTF-8 占 3 字节）
CONCAT('Hello', ' ', 'World')   -- 拼接 → 'Hello World'
SUBSTRING('abcde', 2, 3)        -- 截取（从1开始）→ 'bcd'
UPPER('hello')                  -- → 'HELLO'
LOWER('HELLO')                  -- → 'hello'
TRIM('  abc  ')                 -- 去两端空格 → 'abc'
REPLACE('abcabc', 'a', 'x')     -- → 'xbcxbc'
LEFT('abcde', 3)                -- → 'abc'
RIGHT('abcde', 3)               -- → 'cde'
LPAD('5', 3, '0')               -- 左补零 → '005'
```

### 日期函数

```sql
NOW()                                       -- 当前日期时间
CURRENT_DATE()                              -- 当前日期
YEAR(s_birth)                               -- 提取年份
MONTH(s_birth)                              -- 提取月份
DAY(s_birth)                                -- 提取日
DATE_FORMAT(s_birth, '%Y年%m月%d日')         -- 格式化输出
DATEDIFF('2024-01-10', '2024-01-01')        -- 日期差（天数）→ 9
DATE_ADD(NOW(), INTERVAL 7 DAY)             -- 加7天
DATE_SUB(NOW(), INTERVAL 1 MONTH)           -- 减1个月
TIMESTAMPDIFF(YEAR, s_birth, NOW())         -- 精确年龄（周岁）
```

### 数学函数

```sql
ROUND(3.567, 2)   -- 四舍五入 → 3.57
CEIL(3.1)         -- 向上取整 → 4
FLOOR(3.9)        -- 向下取整 → 3
ABS(-5)           -- 绝对值 → 5
MOD(10, 3)        -- 取余 → 1
RAND()            -- 0~1 随机小数
```

---

## 十五、窗口函数（MySQL 8.0+）

聚合函数把多行**合并成1行**；窗口函数在每行上**单独计算**，行数不变。

```sql
函数名() OVER (
    PARTITION BY 分组列      -- 可选，类似 GROUP BY 但不折叠行
    ORDER BY 排序列          -- 可选，窗口内排序
)
```

### 排名函数

```sql
SELECT s_id, c_id, s_score,
    ROW_NUMBER() OVER (PARTITION BY c_id ORDER BY s_score DESC) AS row_num,   -- 1,2,3,4（连续，不并列）
    RANK()       OVER (PARTITION BY c_id ORDER BY s_score DESC) AS `rank`,    -- 1,1,3,4（并列跳号）
    DENSE_RANK() OVER (PARTITION BY c_id ORDER BY s_score DESC) AS dense_r    -- 1,1,2,3（并列不跳号）
FROM score;
```

| 同分时 | ROW_NUMBER | RANK | DENSE_RANK |
|--------|-----------|------|------------|
| 两人并列第1 | 1, 2（强制连续） | 1, 1（第3名跳到3） | 1, 1（第3名为2） |

### 偏移函数

```sql
-- LAG(列, n, 默认值)：取前 n 行的值（常用于环比计算）
-- LEAD(列, n, 默认值)：取后 n 行的值
SELECT s_id, c_id, s_score,
    LAG(s_score, 1, 0) OVER (PARTITION BY c_id ORDER BY s_id) AS prev_score
FROM score;
```

### 聚合窗口函数（不折叠行）

```sql
-- 每行附上所在课程的平均分
SELECT s_id, c_id, s_score,
    AVG(s_score) OVER (PARTITION BY c_id) AS course_avg,
    SUM(s_score) OVER (ORDER BY s_id)     AS running_total  -- 累计求和
FROM score;
```

### 取每门课前 3 名（经典用法）

```sql
SELECT *
FROM (
    SELECT s_id, c_id, s_score,
        RANK() OVER (PARTITION BY c_id ORDER BY s_score DESC) AS rk
    FROM score
) t
WHERE rk <= 3;
```

---

## 十六、索引

### 作用原理

B+ 树索引把无序数据组织成有序树，查找复杂度从 O(n) 降到 O(log n)。
代价：写入时需维护索引树（INSERT/UPDATE/DELETE 变慢）；占用磁盘空间。

### 创建与管理

```sql
-- 创建普通索引
CREATE INDEX idx_name ON student(s_name);

-- 创建唯一索引
CREATE UNIQUE INDEX idx_email ON student(email);

-- 创建联合索引（字段顺序很重要！）
CREATE INDEX idx_name_birth ON student(s_name, s_birth);

-- 删除
DROP INDEX idx_name ON student;

-- 查看
SHOW INDEX FROM student;
```

### 最左前缀原则（联合索引核心规则）

联合索引 `(a, b, c)` 等价于建了 `(a)`, `(a,b)`, `(a,b,c)` 三个索引：

```sql
-- ✅ 命中索引
WHERE a = 1
WHERE a = 1 AND b = 2
WHERE a = 1 AND b = 2 AND c = 3

-- ❌ 未命中（跳过了最左列 a）
WHERE b = 2
WHERE c = 3
WHERE b = 2 AND c = 3
```

### 索引失效场景

```sql
-- ❌ 列上做函数运算
WHERE YEAR(created_at) = 2023
-- ✅ 改写为范围查询
WHERE created_at BETWEEN '2023-01-01' AND '2023-12-31 23:59:59'

-- ❌ LIKE 左侧通配符
WHERE name LIKE '%张'
-- ✅ 右侧通配符可以命中
WHERE name LIKE '张%'

-- ❌ 隐式类型转换（phone 是 VARCHAR，传数字触发转换）
WHERE phone = 13800001111
-- ✅ 加引号
WHERE phone = '13800001111'

-- ❌ != / NOT IN / NOT EXISTS（一般导致全表扫描）
-- ❌ OR 两侧不都有索引
-- ❌ 数据量少的列（选择性差的列，如性别），建索引意义不大
```

### EXPLAIN 分析查询

```sql
EXPLAIN SELECT * FROM student WHERE s_name = '赵雷';
```

重点关注 `type` 列：
- `const`：主键/唯一索引等值查询，最快
- `ref`：普通索引等值查询
- `range`：索引范围查询
- `index`：全索引扫描（比全表扫描快一点）
- `ALL`：全表扫描，需要优化

---

## 十七、事务（Transaction）

### 基本语法

```sql
START TRANSACTION;   -- 开启事务（也可用 BEGIN）

UPDATE account SET balance = balance - 100 WHERE id = 1;
UPDATE account SET balance = balance + 100 WHERE id = 2;

COMMIT;              -- 全部成功，提交永久生效
-- 或
ROLLBACK;            -- 任意失败，回滚所有操作
```

### ACID 特性

| 特性 | 含义 |
|------|------|
| **原子性**（Atomicity） | 事务内所有操作要么全部成功，要么全部回滚 |
| **一致性**（Consistency） | 事务前后数据必须满足所有约束（如账户余额不能为负） |
| **隔离性**（Isolation） | 并发事务互不干扰，每个事务"看"到的是一致快照 |
| **持久性**（Durability） | 提交后数据永久保存，系统崩溃也不丢失（WAL 日志保证） |

### 并发问题与隔离级别

| 问题 | 说明 |
|------|------|
| **脏读** | 读到其他事务**未提交**的数据 |
| **不可重复读** | 同一事务两次读同一行，值不同（其他事务提交了 UPDATE） |
| **幻读** | 同一事务两次查询，行数不同（其他事务插入了新行） |

| 隔离级别 | 脏读 | 不可重复读 | 幻读 |
|---------|------|----------|------|
| `READ UNCOMMITTED` | 可能 | 可能 | 可能 |
| `READ COMMITTED` | 解决 | 可能 | 可能 |
| `REPEATABLE READ`（MySQL 默认） | 解决 | 解决 | 部分解决（MVCC） |
| `SERIALIZABLE` | 解决 | 解决 | 解决（性能最差） |

```sql
-- 查看当前隔离级别
SELECT @@transaction_isolation;

-- 设置会话级别
SET SESSION TRANSACTION ISOLATION LEVEL REPEATABLE READ;
```

---

## 十八、UNION / UNION ALL

```sql
-- UNION：合并两个查询，自动去重（有排序开销）
SELECT s_id FROM score WHERE c_id = 1
UNION
SELECT s_id FROM score WHERE s_score > 90;

-- UNION ALL：合并不去重，性能更好（能用 UNION ALL 不用 UNION）
SELECT s_id FROM score WHERE c_id = 1
UNION ALL
SELECT s_id FROM score WHERE s_score > 90;
```

注意：两个 SELECT 的**列数和类型**必须一致。

---

## 十九、视图（View）

视图是**保存的 SELECT 语句**，使用时像普通表一样查询，本身不存储数据。

```sql
-- 创建视图（高分成绩单）
CREATE VIEW v_high_score AS
SELECT s.s_name, c.c_name, sc.s_score
FROM score sc
JOIN student s ON sc.s_id = s.s_id
JOIN course c ON sc.c_id = c.c_id
WHERE sc.s_score >= 80;

-- 像普通表一样使用
SELECT * FROM v_high_score WHERE c_name = '数学';

-- 更新视图定义
CREATE OR REPLACE VIEW v_high_score AS ...;

-- 删除
DROP VIEW v_high_score;
```

---

## 二十、子查询 vs JOIN vs EXISTS 选型建议

| 场景 | 推荐写法 | 理由 |
|------|---------|------|
| 判断行是否存在于另一张表 | `EXISTS` | 找到即停，性能最好 |
| 判断行不在另一张表 | `NOT EXISTS` | 比 `NOT IN` 安全（子查询有 NULL 时 NOT IN 结果全为空） |
| 子查询结果集小（<几百行） | `IN` | 写法简单 |
| 子查询结果集大 | `JOIN` / `EXISTS` | 避免大列表扫描 |
| 需要用到子查询的其他列 | `JOIN` | IN/EXISTS 只能传递存在性 |

---

## 二十一、MySQL 8.0 常用新特性速览

```sql
-- 窗口函数（见第十五节）
-- CTE（公用表表达式，替代嵌套子查询，可读性更好）
WITH cte AS (
    SELECT s_id, AVG(s_score) AS avg_score
    FROM score
    GROUP BY s_id
)
SELECT s.s_name, cte.avg_score
FROM student s
JOIN cte ON s.s_id = cte.s_id
WHERE cte.avg_score > 80;

-- JSON 函数
SELECT JSON_EXTRACT('{"name":"张三"}', '$.name');   -- → '张三'

-- CHECK 约束（8.0.16+）
CREATE TABLE product (
    price DECIMAL(10,2),
    CHECK (price > 0)
);
```
