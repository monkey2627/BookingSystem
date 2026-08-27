# MySQL 经典 50 题（含答案解析与现代写法）

---

## 建表与初始化数据

```sql
-- 建库
DROP DATABASE IF EXISTS mysql_test;
CREATE DATABASE mysql_test;
USE mysql_test;

-- 学生表
CREATE TABLE student (
    s_id   INT,
    s_name VARCHAR(8),
    s_birth DATE,
    s_sex  VARCHAR(4)
);

INSERT INTO student VALUES
(1,'赵雷','1990-01-01','男'),
(2,'钱电','1990-12-21','男'),
(3,'孙风','1990-05-20','男'),
(4,'李云','1990-08-06','男'),
(5,'周梅','1991-12-01','女'),
(6,'吴兰','1992-03-01','女'),
(7,'郑竹','1989-07-01','女'),
(8,'王菊','1990-01-20','女');

-- 课程表
CREATE TABLE course (
    c_id   INT,
    c_name VARCHAR(8),
    t_id   INT
);

INSERT INTO course VALUES
(1,'语文',2),
(2,'数学',1),
(3,'英语',3);

-- 教师表
CREATE TABLE teacher (
    t_id   INT,
    t_name VARCHAR(8)
);

INSERT INTO teacher VALUES
(1,'张三'),
(2,'李四'),
(3,'王五');

-- 成绩表
CREATE TABLE score (
    s_id    INT,
    c_id    INT,
    s_score INT
);

INSERT INTO score VALUES
(1,1,80),(1,2,90),(1,3,99),
(2,1,70),(2,2,60),(2,3,65),
(3,1,80),(3,2,80),(3,3,80),
(4,1,50),(4,2,30),(4,3,40),
(5,1,76),(5,2,87),
(6,1,31),(6,3,34),
(7,2,89),(7,3,98);
```

### 表关系说明

```
student(s_id) ←── score(s_id, c_id) ──→ course(c_id) ──→ teacher(t_id)
```

- 一个学生可以选多门课（score 是关系表）
- 每门课由一位老师授课
- 王菊（s_id=8）无成绩记录，用于测试 LEFT JOIN

---

## 经典 50 题

---

### 1. 查询"01"课程比"02"课程成绩高的学生信息及课程分数

**思路**：分别筛出选修 c_id=1 和 c_id=2 的成绩，INNER JOIN 后 WHERE 过滤。
INNER JOIN 自动排除了只选一门课的学生（7号郑竹无语文成绩，不会出现）。

```sql
SELECT s.*, sc1.s_score AS score_01, sc2.s_score AS score_02
FROM student s
INNER JOIN (SELECT s_id, s_score FROM score WHERE c_id = 1) sc1 ON s.s_id = sc1.s_id
INNER JOIN (SELECT s_id, s_score FROM score WHERE c_id = 2) sc2 ON s.s_id = sc2.s_id
WHERE sc1.s_score > sc2.s_score;
```

| s_id | s_name | s_birth    | s_sex | score_01 | score_02 |
|------|--------|------------|-------|----------|----------|
| 2    | 钱电   | 1990-12-21 | 男    | 70       | 60       |
| 4    | 李云   | 1990-08-06 | 男    | 50       | 30       |

---

### 2. 查询"01"课程比"02"课程成绩低的学生信息及课程分数

```sql
SELECT s.*, sc1.s_score AS score_01, sc2.s_score AS score_02
FROM student s
INNER JOIN (SELECT s_id, s_score FROM score WHERE c_id = 1) sc1 ON s.s_id = sc1.s_id
INNER JOIN (SELECT s_id, s_score FROM score WHERE c_id = 2) sc2 ON s.s_id = sc2.s_id
WHERE sc1.s_score < sc2.s_score;
```

| s_id | s_name | score_01 | score_02 |
|------|--------|----------|----------|
| 1    | 赵雷   | 80       | 90       |
| 5    | 周梅   | 76       | 87       |

---

### 3. 查询平均成绩 ≥ 60 分的学生编号、姓名和平均成绩

**思路**：先在 score 表分组算平均，HAVING 过滤 ≥60，再 JOIN student 取姓名。

```sql
SELECT s.s_id, s.s_name, ROUND(t.avg_score, 2) AS avg_score
FROM student s
INNER JOIN (
    SELECT s_id, AVG(s_score) AS avg_score
    FROM score
    GROUP BY s_id
    HAVING AVG(s_score) >= 60
) t ON s.s_id = t.s_id;
```

| s_id | s_name | avg_score |
|------|--------|-----------|
| 1    | 赵雷   | 89.67     |
| 2    | 钱电   | 65.00     |
| 3    | 孙风   | 80.00     |
| 5    | 周梅   | 81.50     |
| 7    | 郑竹   | 93.50     |

---

### 4. 查询平均成绩 < 60 分的学生信息（包括无成绩的学生）

**思路**：LEFT JOIN 保留无成绩的学生（王菊），IFNULL 把 NULL 平均分显示为 0。

```sql
SELECT s.s_id, s.s_name, IFNULL(ROUND(t.avg_score, 2), 0) AS avg_score
FROM student s
LEFT JOIN (
    SELECT s_id, AVG(s_score) AS avg_score
    FROM score
    GROUP BY s_id
) t ON s.s_id = t.s_id
WHERE t.avg_score IS NULL OR t.avg_score < 60;
```

| s_id | s_name | avg_score |
|------|--------|-----------|
| 4    | 李云   | 40.00     |
| 6    | 吴兰   | 32.50     |
| 8    | 王菊   | 0.00      |

---

### 5. 查询所有学生的编号、姓名、选课总数、总成绩

**思路**：LEFT JOIN 保留王菊（无成绩），IFNULL 处理 NULL。

```sql
SELECT s.s_id, s.s_name,
       IFNULL(t.cnt_course, 0) AS cnt_course,
       IFNULL(t.sum_score, 0)  AS sum_score
FROM student s
LEFT JOIN (
    SELECT s_id, COUNT(*) AS cnt_course, SUM(s_score) AS sum_score
    FROM score
    GROUP BY s_id
) t ON s.s_id = t.s_id;
```

| s_id | s_name | cnt_course | sum_score |
|------|--------|------------|-----------|
| 1    | 赵雷   | 3          | 269       |
| 8    | 王菊   | 0          | 0         |

---

### 6. 查询"李"姓老师的数量

```sql
SELECT COUNT(*) AS cnt_li FROM teacher WHERE t_name LIKE '李%';
```

| cnt_li |
|--------|
| 1      |

---

### 7. 查询学过"张三"老师授课的学生信息

**思路**：teacher → course 找到张三的课 → score 找到选了这门课的学生 → student 取信息。

```sql
SELECT s.*
FROM student s
WHERE s.s_id IN (
    SELECT DISTINCT sc.s_id
    FROM score sc
    INNER JOIN course c ON sc.c_id = c.c_id
    INNER JOIN teacher t ON c.t_id = t.t_id
    WHERE t.t_name = '张三'
);
```

张三教数学（c_id=2），选了数学的学生：1,2,3,4,5,7 → 共6人。

---

### 8. 查询没学过"张三"老师授课的学生信息

```sql
SELECT s.*
FROM student s
WHERE s.s_id NOT IN (
    SELECT DISTINCT sc.s_id
    FROM score sc
    INNER JOIN course c ON sc.c_id = c.c_id
    INNER JOIN teacher t ON c.t_id = t.t_id
    WHERE t.t_name = '张三'
);
```

| s_id | s_name |
|------|--------|
| 6    | 吴兰   |
| 8    | 王菊   |

---

### 9. 查询学过"01"且学过"02"课程的学生信息

**思路**：c_id IN (1,2) 后 HAVING count=2，保证两门都选了。

```sql
SELECT s.*
FROM student s
WHERE s.s_id IN (
    SELECT s_id
    FROM score
    WHERE c_id IN (1, 2)
    GROUP BY s_id
    HAVING COUNT(*) = 2
);
```

---

### 10. 查询学过"01"但没学过"02"课程的学生信息

```sql
SELECT s.*
FROM student s
WHERE s.s_id IN (
    SELECT s_id FROM score WHERE c_id = 1
    AND s_id NOT IN (SELECT s_id FROM score WHERE c_id = 2)
);
```

| s_id | s_name |
|------|--------|
| 6    | 吴兰   |

> 吴兰有语文(c_id=1)成绩31分，但没有数学(c_id=2)记录。

---

### 11. 查询没有学全所有课程的学生信息

**思路**：学全 = 成绩记录数 = 课程总数(3)，用 NOT IN 排除。

```sql
SELECT *
FROM student
WHERE s_id NOT IN (
    SELECT s_id
    FROM score
    GROUP BY s_id
    HAVING COUNT(*) = (SELECT COUNT(*) FROM course)
);
```

| s_id | s_name |
|------|--------|
| 5    | 周梅   |
| 6    | 吴兰   |
| 7    | 郑竹   |
| 8    | 王菊   |

---

### 12. 查询至少有一门课与学号"01"同学相同的学生信息（含01本人）

```sql
SELECT *
FROM student
WHERE s_id IN (
    SELECT DISTINCT sc.s_id
    FROM score sc
    INNER JOIN (SELECT c_id FROM score WHERE s_id = 1) t1 ON sc.c_id = t1.c_id
);
```

---

### 13. 查询与"01"号同学课程完全相同的其他同学信息

**思路**：先找与01有共同课的学生，再 HAVING 确认共同课数量=01的选课数。

```sql
SELECT *
FROM student
WHERE s_id IN (
    SELECT sc.s_id
    FROM score sc
    INNER JOIN (SELECT c_id FROM score WHERE s_id = 1) t1 ON sc.c_id = t1.c_id
    WHERE sc.s_id != 1
    GROUP BY sc.s_id
    HAVING COUNT(*) = (SELECT COUNT(*) FROM score WHERE s_id = 1)
);
```

| s_id | s_name |
|------|--------|
| 2    | 钱电   |
| 3    | 孙风   |
| 4    | 李云   |

---

### 14. 查询没学过"张三"老师任一课程的学生姓名

与第8题类似，只取 s_name：

```sql
SELECT s_name
FROM student
WHERE s_id NOT IN (
    SELECT DISTINCT sc.s_id
    FROM score sc
    INNER JOIN course c ON sc.c_id = c.c_id
    INNER JOIN teacher t ON c.t_id = t.t_id
    WHERE t.t_name = '张三'
);
```

---

### 15. 查询两门及以上不及格课程的学生学号、姓名及其平均成绩

```sql
SELECT s.s_id, s.s_name, ROUND(AVG(sc2.s_score), 2) AS avg_score
FROM student s
INNER JOIN (
    SELECT s_id
    FROM score
    WHERE s_score < 60
    GROUP BY s_id
    HAVING COUNT(*) >= 2
) t1 ON s.s_id = t1.s_id
INNER JOIN score sc2 ON s.s_id = sc2.s_id
GROUP BY s.s_id;
```

| s_id | s_name | avg_score |
|------|--------|-----------|
| 4    | 李云   | 40.00     |
| 6    | 吴兰   | 32.50     |

---

### 16. 检索"01"课程分数 < 60 的学生信息，按分数降序

```sql
SELECT s.*, t.s_score AS score_01
FROM student s
INNER JOIN (
    SELECT s_id, s_score FROM score WHERE c_id = 1 AND s_score < 60
) t ON s.s_id = t.s_id
ORDER BY score_01 DESC;
```

| s_id | s_name | score_01 |
|------|--------|----------|
| 4    | 李云   | 50       |
| 6    | 吴兰   | 31       |

---

### 17. 按平均成绩从高到低显示所有学生的所有课程成绩及平均成绩（行转列）

**思路**：CASE WHEN 把课程成绩分列，LEFT JOIN 保留王菊（0分）。

```sql
SELECT
    s.s_id AS 学号, s.s_name AS 姓名,
    SUM(CASE c_id WHEN 1 THEN s_score ELSE 0 END) AS 语文,
    SUM(CASE c_id WHEN 2 THEN s_score ELSE 0 END) AS 数学,
    SUM(CASE c_id WHEN 3 THEN s_score ELSE 0 END) AS 英语,
    IFNULL(ROUND(AVG(s_score), 2), 0) AS 平均成绩
FROM student s
LEFT JOIN score sc ON s.s_id = sc.s_id
GROUP BY s.s_id
ORDER BY 平均成绩 DESC;
```

| 学号 | 姓名 | 语文 | 数学 | 英语 | 平均成绩 |
|------|------|------|------|------|---------|
| 7    | 郑竹 | 0    | 89   | 98   | 93.50   |
| 1    | 赵雷 | 80   | 90   | 99   | 89.67   |
| 8    | 王菊 | 0    | 0    | 0    | 0.00    |

---

### 18. 各科成绩最高分、最低分、平均分及各分段占比

```sql
SELECT
    c.c_id AS 课程ID, c.c_name AS 课程name,
    MAX(s_score) AS 最高分,
    MIN(s_score) AS 最低分,
    ROUND(AVG(s_score), 2) AS 平均分,
    CONCAT(ROUND(SUM(CASE WHEN s_score >= 60 THEN 1 ELSE 0 END) / COUNT(*) * 100, 2), '%') AS 及格率,
    CONCAT(ROUND(SUM(CASE WHEN s_score BETWEEN 70 AND 80 THEN 1 ELSE 0 END) / COUNT(*) * 100, 2), '%') AS 中等率,
    CONCAT(ROUND(SUM(CASE WHEN s_score BETWEEN 80 AND 90 THEN 1 ELSE 0 END) / COUNT(*) * 100, 2), '%') AS 优良率,
    CONCAT(ROUND(SUM(CASE WHEN s_score >= 90 THEN 1 ELSE 0 END) / COUNT(*) * 100, 2), '%') AS 优秀率
FROM course c
INNER JOIN score s ON c.c_id = s.c_id
GROUP BY c.c_id;
```

---

### 19. 按各科成绩进行排序并显示排名

**旧写法（MySQL 5.x 变量）**：

```sql
SELECT
    (@i := CASE WHEN @pre = c_id THEN @i + 1 ELSE 1 END) AS `rank`,
    (@pre := c_id) AS c_id,
    c_name, s_id, s_name, s_score
FROM (SELECT @i := 0, @pre := NULL) var
CROSS JOIN (
    SELECT c.c_id, c.c_name, s.s_id, s.s_name, sc.s_score
    FROM score sc
    INNER JOIN student s ON sc.s_id = s.s_id
    INNER JOIN course c ON sc.c_id = c.c_id
    ORDER BY c.c_id, sc.s_score DESC
) t;
```

**✅ 推荐写法（MySQL 8.0+ 窗口函数）**：

```sql
SELECT
    RANK() OVER (PARTITION BY c_id ORDER BY s_score DESC) AS `rank`,
    c.c_id, c.c_name, s.s_id, s.s_name, sc.s_score
FROM score sc
INNER JOIN student s ON sc.s_id = s.s_id
INNER JOIN course c ON sc.c_id = c.c_id;
```

> 注意：同分时 RANK() 会并列（如语文两个80分同列第1）。如果不想跳号用 DENSE_RANK()，如果强制连续用 ROW_NUMBER()。

---

### 20. 查询学生总成绩并排名

**旧写法**：

```sql
SELECT (@i := @i + 1) AS `rank`, t.*
FROM (SELECT @i := 0) var
CROSS JOIN (
    SELECT s.s_id, s.s_name, SUM(sc.s_score) AS sum_score
    FROM student s
    INNER JOIN score sc ON s.s_id = sc.s_id
    GROUP BY s.s_id
    ORDER BY sum_score DESC
) t;
```

**✅ 推荐写法（MySQL 8.0+）**：

```sql
SELECT
    RANK() OVER (ORDER BY SUM(sc.s_score) DESC) AS `rank`,
    s.s_id, s.s_name, SUM(sc.s_score) AS sum_score
FROM student s
INNER JOIN score sc ON s.s_id = sc.s_id
GROUP BY s.s_id
ORDER BY sum_score DESC;
```

| rank | s_id | s_name | sum_score |
|------|------|--------|-----------|
| 1    | 1    | 赵雷   | 269       |
| 2    | 3    | 孙风   | 240       |

---

### 21. 查询不同老师所教不同课程平均分从高到低

```sql
SELECT t.t_id, t.t_name, c.c_id, c.c_name, ROUND(AVG(sc.s_score), 2) AS avg_score
FROM score sc
INNER JOIN course c ON sc.c_id = c.c_id
INNER JOIN teacher t ON c.t_id = t.t_id
GROUP BY t.t_id, c.c_id
ORDER BY avg_score DESC;
```

---

### 22. 查询所有课程成绩第2名到第3名的学生信息

**✅ 推荐写法（MySQL 8.0+）**：

```sql
SELECT *
FROM (
    SELECT
        RANK() OVER (PARTITION BY sc.c_id ORDER BY sc.s_score DESC) AS `rank`,
        c.c_id, c.c_name, s.s_id, s.s_name, s.s_birth, s.s_sex, sc.s_score
    FROM score sc
    INNER JOIN student s ON sc.s_id = s.s_id
    INNER JOIN course c ON sc.c_id = c.c_id
) t
WHERE `rank` IN (2, 3);
```

---

### 23. 统计各科各分数段人数及百分比

```sql
SELECT
    c.c_id AS 课程编号, c.c_name AS 课程名称,
    SUM(CASE WHEN s_score BETWEEN 85 AND 100 THEN 1 ELSE 0 END) AS '[100-85]人数',
    CONCAT(ROUND(SUM(CASE WHEN s_score BETWEEN 85 AND 100 THEN 1 ELSE 0 END) / COUNT(*) * 100, 2), '%') AS '[100-85]占比',
    SUM(CASE WHEN s_score BETWEEN 70 AND 84  THEN 1 ELSE 0 END) AS '[85-70]人数',
    CONCAT(ROUND(SUM(CASE WHEN s_score BETWEEN 70 AND 84  THEN 1 ELSE 0 END) / COUNT(*) * 100, 2), '%') AS '[85-70]占比',
    SUM(CASE WHEN s_score BETWEEN 60 AND 69  THEN 1 ELSE 0 END) AS '[70-60]人数',
    CONCAT(ROUND(SUM(CASE WHEN s_score BETWEEN 60 AND 69  THEN 1 ELSE 0 END) / COUNT(*) * 100, 2), '%') AS '[70-60]占比',
    SUM(CASE WHEN s_score < 60               THEN 1 ELSE 0 END) AS '[0-60]人数',
    CONCAT(ROUND(SUM(CASE WHEN s_score < 60               THEN 1 ELSE 0 END) / COUNT(*) * 100, 2), '%') AS '[0-60]占比'
FROM course c
INNER JOIN score sc ON c.c_id = sc.c_id
GROUP BY c.c_id;
```

---

### 24. 查询学生平均成绩及其名次

**✅ 推荐写法（MySQL 8.0+）**：

```sql
SELECT
    RANK() OVER (ORDER BY AVG(sc.s_score) DESC) AS `rank`,
    s.s_id, s.s_name, ROUND(AVG(sc.s_score), 2) AS avg_score
FROM student s
INNER JOIN score sc ON s.s_id = sc.s_id
GROUP BY s.s_id
ORDER BY avg_score DESC;
```

---

### 25. 查询各科成绩前三名

**方法一：关联子查询**（同分时可能超过3条）

```sql
SELECT c.c_id, c.c_name, s.s_id, s.s_name, sc.s_score
FROM score sc
INNER JOIN student s ON sc.s_id = s.s_id
INNER JOIN course c ON sc.c_id = c.c_id
WHERE (
    SELECT COUNT(*) FROM score sc1
    WHERE sc1.c_id = sc.c_id AND sc1.s_score > sc.s_score
) < 3
ORDER BY c.c_id, sc.s_score DESC;
```

**✅ 方法二：窗口函数**（推荐，语义清晰）

```sql
SELECT *
FROM (
    SELECT
        DENSE_RANK() OVER (PARTITION BY c_id ORDER BY s_score DESC) AS `rank`,
        s_id, c_id, s_score
    FROM score
) t
WHERE `rank` <= 3;
```

---

### 26. 查询每门课程被选修的学生数

```sql
SELECT c.c_id, c.c_name, COUNT(sc.s_id) AS cnt_student
FROM course c
INNER JOIN score sc ON c.c_id = sc.c_id
GROUP BY c.c_id;
```

---

### 27. 查询只有两门课程的学生学号和姓名

```sql
SELECT s.s_id, s.s_name
FROM student s
WHERE s.s_id IN (
    SELECT s_id FROM score GROUP BY s_id HAVING COUNT(*) = 2
);
```

---

### 28. 查询男生、女生人数

```sql
SELECT s_sex, COUNT(*) AS cnt FROM student GROUP BY s_sex;
```

---

### 29. 查询名字含"风"字的学生信息

```sql
SELECT * FROM student WHERE s_name LIKE '%风%';
```

---

### 30. 查询同名同性学生名单，并统计同名人数

```sql
SELECT s_name, s_sex, COUNT(*) AS cnt
FROM student
GROUP BY s_name, s_sex
HAVING COUNT(*) > 1;
```

（当前数据无同名同性学生，结果为空）

---

### 31. 查询1990年出生的学生名单

```sql
SELECT * FROM student WHERE YEAR(s_birth) = 1990;
```

---

### 32. 各课程平均成绩降序，相同时按课程编号升序

```sql
SELECT c.c_id, c.c_name, ROUND(AVG(sc.s_score), 2) AS avg_score
FROM course c
INNER JOIN score sc ON c.c_id = sc.c_id
GROUP BY c.c_id
ORDER BY avg_score DESC, c.c_id ASC;
```

---

### 33. 查询平均成绩 ≥ 85 的学生信息

```sql
SELECT s.s_id, s.s_name, ROUND(AVG(sc.s_score), 2) AS avg_score
FROM student s
INNER JOIN score sc ON s.s_id = sc.s_id
GROUP BY s.s_id
HAVING AVG(sc.s_score) >= 85;
```

---

### 34. 查询"数学"课分数 < 60 的学生姓名和分数

```sql
SELECT s.s_name, sc.s_score
FROM student s
INNER JOIN score sc ON s.s_id = sc.s_id
INNER JOIN course c ON sc.c_id = c.c_id
WHERE c.c_name = '数学' AND sc.s_score < 60;
```

---

### 35. 查询所有学生的课程及分数情况（行转列）

```sql
SELECT
    s.s_id, s.s_name,
    SUM(CASE c_id WHEN 1 THEN s_score ELSE 0 END) AS 语文,
    SUM(CASE c_id WHEN 2 THEN s_score ELSE 0 END) AS 数学,
    SUM(CASE c_id WHEN 3 THEN s_score ELSE 0 END) AS 英语
FROM student s
LEFT JOIN score sc ON s.s_id = sc.s_id
GROUP BY s.s_id;
```

---

### 36. 查询任何一门课程成绩在70分以上的学生姓名、课程名称和分数

**思路**：直接 WHERE s_score > 70，每行输出一条记录（每门>70分的课程单独一行）。

```sql
SELECT s.s_name, c.c_name, sc.s_score
FROM score sc
INNER JOIN student s ON sc.s_id = s.s_id
INNER JOIN course c ON sc.c_id = c.c_id
WHERE sc.s_score > 70;
```

| s_name | c_name | s_score |
|--------|--------|---------|
| 赵雷   | 语文   | 80      |
| 赵雷   | 数学   | 90      |
| 赵雷   | 英语   | 99      |
| 孙风   | 语文   | 80      |
| 孙风   | 数学   | 80      |
| 孙风   | 英语   | 80      |
| 周梅   | 语文   | 76      |
| 周梅   | 数学   | 87      |
| 郑竹   | 数学   | 89      |
| 郑竹   | 英语   | 98      |

> ⚠️ 原题答案错误：加了 `GROUP BY s.s_id` 会把每个学生压成一行，导致只显示4条记录，且显示哪门课是随机的（ONLY_FULL_GROUP_BY 下直接报错）。

---

### 37. 查询课程不及格的学生

```sql
SELECT s.s_id, s.s_name, c.c_id, c.c_name, sc.s_score
FROM score sc
INNER JOIN student s ON sc.s_id = s.s_id
INNER JOIN course c ON sc.c_id = c.c_id
WHERE sc.s_score < 60;
```

---

### 38. 查询课程编号为01且成绩在80分以上的学生学号和姓名

```sql
SELECT s.s_id, s.s_name
FROM student s
INNER JOIN score sc ON s.s_id = sc.s_id
WHERE sc.c_id = 1 AND sc.s_score >= 80;
```

---

### 39. 求每门课程的学生人数

与第26题相同，是重复题。

```sql
SELECT c.c_id, c.c_name, COUNT(sc.s_id) AS cnt_student
FROM course c
INNER JOIN score sc ON c.c_id = sc.c_id
GROUP BY c.c_id;
```

---

### 40. 查询选修张三老师课程中成绩最高的学生信息及成绩

**思路**：找张三的课 → 这些课的成绩排序取第一。注意：用 LIMIT 可能漏掉同分并列第一的情况，用子查询 MAX 更安全。

```sql
-- 用 MAX 更严谨（可以处理并列第一）
SELECT s.*, sc.s_score AS max_score
FROM student s
INNER JOIN score sc ON s.s_id = sc.s_id
INNER JOIN course c ON sc.c_id = c.c_id
INNER JOIN teacher t ON c.t_id = t.t_id
WHERE t.t_name = '张三'
  AND sc.s_score = (
      SELECT MAX(sc2.s_score)
      FROM score sc2
      INNER JOIN course c2 ON sc2.c_id = c2.c_id
      INNER JOIN teacher t2 ON c2.t_id = t2.t_id
      WHERE t2.t_name = '张三'
  );
```

| s_id | s_name | s_birth    | s_sex | max_score |
|------|--------|------------|-------|-----------|
| 1    | 赵雷   | 1990-01-01 | 男    | 90        |

---

### 41. 查询不同课程成绩相同的学生编号、课程编号、学生成绩

**思路**：自连接找到 c_id 不同但 s_score 相同的记录对，取其中所有涉及的行。

```sql
-- 更语义化的自连接写法
SELECT DISTINCT s1.s_id, s1.c_id, s1.s_score
FROM score s1
INNER JOIN score s2 ON s1.s_score = s2.s_score AND s1.c_id != s2.c_id;
```

| s_id | c_id | s_score |
|------|------|---------|
| 1    | 1    | 80      |
| 3    | 1    | 80      |
| 3    | 2    | 80      |
| 3    | 3    | 80      |

> 分数80在(s_id=1,c_id=1)和(s_id=3,c_id=1/2/3)中出现，满足"不同课程同分"。

---

### 42. 查询每门课程成绩最好的前三名

与第25题相同，用窗口函数更简洁：

```sql
SELECT *
FROM (
    SELECT
        DENSE_RANK() OVER (PARTITION BY c_id ORDER BY s_score DESC) AS `rank`,
        s_id, c_id, s_score
    FROM score
) t
WHERE `rank` <= 3;
```

---

### 43. 统计每门课程选修人数（超过5人才统计），按人数降序、课程编号升序

```sql
SELECT c.c_id, COUNT(sc.s_id) AS cnt_student
FROM course c
INNER JOIN score sc ON c.c_id = sc.c_id
GROUP BY c.c_id
HAVING COUNT(sc.s_id) > 5
ORDER BY cnt_student DESC, c.c_id ASC;
```

---

### 44. 检索至少选修两门课程的学生学号

```sql
SELECT s_id
FROM score
GROUP BY s_id
HAVING COUNT(*) >= 2;
```

---

### 45. 查询选修了全部课程的学生信息

```sql
SELECT *
FROM student
WHERE s_id IN (
    SELECT s_id
    FROM score
    GROUP BY s_id
    HAVING COUNT(*) = (SELECT COUNT(*) FROM course)
);
```

---

### 46. 查询各学生年龄（按出生日期计算周岁）

```sql
-- 推荐：TIMESTAMPDIFF 自动计算周岁，考虑了是否过了生日
SELECT s_id, s_name, s_birth,
    TIMESTAMPDIFF(YEAR, s_birth, CURRENT_DATE()) AS s_age
FROM student;
```

---

### 47. 查询本周过生日的学生

```sql
SELECT *
FROM student
WHERE WEEK(s_birth, 1) = WEEK(CURRENT_DATE(), 1);
```

---

### 48. 查询下周过生日的学生

```sql
SELECT *
FROM student
WHERE WEEK(s_birth, 1) = WEEK(DATE_ADD(CURRENT_DATE(), INTERVAL 7 DAY), 1);
```

---

### 49. 查询本月过生日的学生

```sql
SELECT * FROM student WHERE MONTH(s_birth) = MONTH(CURRENT_DATE());
```

---

### 50. 查询12月份过生日的学生

```sql
SELECT * FROM student WHERE MONTH(s_birth) = 12;
```

| s_id | s_name | s_birth    |
|------|--------|------------|
| 2    | 钱电   | 1990-12-21 |
| 5    | 周梅   | 1991-12-01 |

---

## 补充：50题未考察的重要知识点

### A. DML 操作（INSERT / UPDATE / DELETE）

```sql
-- 1. 给所有语文不及格的学生加5分
UPDATE score SET s_score = s_score + 5
WHERE c_id = 1 AND s_score < 60;

-- 2. 删除没有选修任何课程的学生（王菊）
DELETE FROM student
WHERE s_id NOT IN (SELECT DISTINCT s_id FROM score);

-- 3. 将赵雷的所有成绩复制给一个新学生（s_id=9）
INSERT INTO score (s_id, c_id, s_score)
SELECT 9, c_id, s_score FROM score WHERE s_id = 1;
```

---

### B. 事务（Transaction）

```sql
-- 转账场景：两个操作要么同时成功，要么同时回滚
START TRANSACTION;

UPDATE account SET balance = balance - 100 WHERE user_id = 1;
UPDATE account SET balance = balance + 100 WHERE user_id = 2;

-- 检查余额是否为负
SELECT balance INTO @bal FROM account WHERE user_id = 1;
IF @bal < 0 THEN
    ROLLBACK;   -- 余额不足，回滚
ELSE
    COMMIT;     -- 成功提交
END IF;
```

---

### C. UNION 合并查询

```sql
-- 查询语文或数学不及格的学生（UNION 去重）
SELECT s_id, '语文' AS 科目, s_score FROM score WHERE c_id = 1 AND s_score < 60
UNION
SELECT s_id, '数学' AS 科目, s_score FROM score WHERE c_id = 2 AND s_score < 60;

-- UNION ALL：保留重复（如果一个学生两门都不及格会出现两次）
SELECT s_id FROM score WHERE c_id = 1 AND s_score < 60
UNION ALL
SELECT s_id FROM score WHERE c_id = 2 AND s_score < 60;
```

---

### D. EXISTS vs NOT EXISTS（比 IN/NOT IN 更安全）

```sql
-- 查询有成绩记录的学生（EXISTS）
SELECT s.*
FROM student s
WHERE EXISTS (
    SELECT 1 FROM score sc WHERE sc.s_id = s.s_id
);

-- 查询没有成绩记录的学生（NOT EXISTS，比 NOT IN 安全）
-- NOT IN 若子查询有 NULL 会导致结果全为空；NOT EXISTS 不受此影响
SELECT s.*
FROM student s
WHERE NOT EXISTS (
    SELECT 1 FROM score sc WHERE sc.s_id = s.s_id
);
```

---

### E. LEFT JOIN + IS NULL 反连接

```sql
-- 等价于 NOT EXISTS，但有时更直观
-- 查询没有成绩记录的学生
SELECT s.*
FROM student s
LEFT JOIN score sc ON s.s_id = sc.s_id
WHERE sc.s_id IS NULL;
```

---

### F. 窗口函数专项练习

```sql
-- 1. 每门课程内，显示每个学生的成绩及其与该课程最高分的差值
SELECT s_id, c_id, s_score,
    MAX(s_score) OVER (PARTITION BY c_id) AS course_max,
    MAX(s_score) OVER (PARTITION BY c_id) - s_score AS gap_to_max
FROM score;

-- 2. 每个学生各门课成绩的累计总分（按课程 id 顺序）
SELECT s_id, c_id, s_score,
    SUM(s_score) OVER (PARTITION BY s_id ORDER BY c_id) AS running_total
FROM score;

-- 3. 每门课成绩与上一名的分差（LAG）
SELECT c_id, s_id, s_score,
    LAG(s_score, 1) OVER (PARTITION BY c_id ORDER BY s_score DESC) AS prev_score,
    LAG(s_score, 1) OVER (PARTITION BY c_id ORDER BY s_score DESC) - s_score AS gap
FROM score;

-- 4. 用 DENSE_RANK 代替 RANK（并列时不跳号）
-- 查询每门课成绩第1名（DENSE_RANK 处理并列更合理）
SELECT *
FROM (
    SELECT s_id, c_id, s_score,
        DENSE_RANK() OVER (PARTITION BY c_id ORDER BY s_score DESC) AS dr
    FROM score
) t
WHERE dr = 1;
```

---

### G. CTE（公用表表达式，MySQL 8.0+）

CTE 是用 `WITH` 定义的命名临时查询，替代嵌套子查询，可读性更好。

```sql
-- 查询平均分高于全体学生总平均分的课程
WITH course_avg AS (
    SELECT c_id, AVG(s_score) AS avg_score
    FROM score
    GROUP BY c_id
),
total_avg AS (
    SELECT AVG(s_score) AS total_avg FROM score
)
SELECT ca.c_id, c.c_name, ca.avg_score
FROM course_avg ca
JOIN course c ON ca.c_id = c.c_id
CROSS JOIN total_avg ta
WHERE ca.avg_score > ta.total_avg;
```

---

### H. 考察点补充总结

| 考点 | 本题集覆盖情况 | 补充位置 |
|------|--------------|---------|
| SELECT / WHERE / ORDER BY / LIMIT | ✅ 大量覆盖 | — |
| GROUP BY / HAVING | ✅ 大量覆盖 | — |
| JOIN（内/外连接） | ✅ 覆盖 | — |
| 子查询（IN / NOT IN / 关联子查询） | ✅ 覆盖 | — |
| 聚合函数 | ✅ 覆盖 | — |
| CASE WHEN 行转列 | ✅ 覆盖（Q17/Q18/Q23/Q35） | — |
| 日期函数 | ✅ 覆盖（Q31/Q46-50） | — |
| 排名/窗口函数 | ⚠️ 旧写法 | **Q19/Q20/Q22/Q24/Q25/Q42 已给出窗口函数版** |
| INSERT / UPDATE / DELETE | ❌ 未覆盖 | **补充 A** |
| 事务 | ❌ 未覆盖 | **补充 B** |
| UNION / UNION ALL | ❌ 未覆盖 | **补充 C** |
| EXISTS / NOT EXISTS | ❌ 未覆盖 | **补充 D** |
| LEFT JOIN 反连接 | ❌ 未覆盖 | **补充 E** |
| 窗口函数（LAG/LEAD/累计）| ❌ 未覆盖 | **补充 F** |
| CTE（WITH）| ❌ 未覆盖 | **补充 G** |
