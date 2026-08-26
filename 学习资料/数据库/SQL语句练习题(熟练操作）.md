MySQL 经典练习 50 题（完美解答版）
创建数据库和表
数据库
学生表 student
课程表 course
教师表 teacher
成绩表 score
表关系
经典练习 50 题
1、查询"01"课程比"02"课程成绩高的学生的信息及课程分数
2、查询"01"课程比"02"课程成绩低的学生的信息及课程分数
3、查询平均成绩大于等于60分的同学的学生编号和学生姓名和平均成绩
4、查询平均成绩小于60分的同学的学生编号和学生姓名和平均成绩(包括有成绩的和无成绩的)
5、查询所有同学的学生编号、学生姓名、选课总数、所有课程的总成绩
6、查询"李"姓老师的数量
7、询学过"张三"老师授课的同学的信息
8、查询没学过"张三"老师授课的同学的信息
9、查询学过编号为"01"并且也学过编号为"02"的课程的同学的信息
10、查询学过编号为"01"但是没有学过编号为"02"的课程的同学的信息
11、查询没有学全所有课程的同学的信息
12、查询至少有一门课与学号为"01"的同学所学相同的同学的信息
13、查询和"01"号的同学学习的课程完全相同的其他同学的信息
14、查询没学过"张三"老师讲授的任一门课程的学生姓名
15、查询两门及其以上不及格课程的同学的学号，姓名及其平均成绩
16、检索"01"课程分数小于60，按分数降序排列的学生信息
17、按平均成绩从高到低显示所有学生的所有课程的成绩以及平均成绩
18、查询各科成绩最高分、最低分和平均分，以如下形式显示：
19、按各科成绩进行排序，并显示排名
20、查询学生的总成绩并进行排名
21、查询不同老师所教不同课程平均分从高到低显示
22、查询所有课程的成绩第2名到第3名的学生信息及该课程成绩
23、统计各科成绩各分数段人数：课程编号,课程名称,[100-85],[85-70],[70-60],[0-60]及所占百分比
24、查询学生平均成绩及其名次
25、查询各科成绩前三名的记录
26、查询每门课程被选修的学生数
27、查询出只有两门课程的全部学生的学号和姓名
28、查询男生、女生人数
29、查询名字中含有"风"字的学生信息
30、查询同名同性学生名单，并统计同名人数
31、查询1990年出生的学生名单
32、查询每门课程的平均成绩，结果按平均成绩降序排列，平均成绩相同时，按课程编号升序排列
33、查询平均成绩大于等于85的所有学生的学号、姓名和平均成绩
34、查询课程名称为"数学"，且分数低于60的学生姓名和分数
35、查询所有学生的课程及分数情况
36、查询任何一门课程成绩在70分以上的学生姓名、课程名称和分数
37、查询课程不及格的学生
38、查询课程编号为01且课程成绩在80分以上的学生的学号和姓名
39、求每门课程的学生人数
40、查询选修"张三"老师所授课程的学生中，成绩最高的学生信息及其成绩
41、查询不同课程成绩相同的学生的学生编号、课程编号、学生成绩
42、查询每门课程成绩最好的前三名
43、统计每门课程的学生选修人数（超过5人的课程才统计）
44、检索至少选修两门课程的学生学号
45、查询选修了全部课程的学生信息
46、查询各学生的年龄(周岁)
47、查询本周过生日的学生
48、查询下周过生日的学生
49、查询本月过生日的学生
50、查询12月份过生日的学生
创建数据库和表


数据库
drop database if exists mysql_test cascade;
create database mysql_test;
use mysql_test;
用码道免费领 1 个月 Token
sql
1
2
3


学生表 student
创建学生表 student

create table student (
s_id int,
s_name varchar(8),
s_birth date,
s_sex varchar(4)
);
用码道免费领 1 个月 Token
sql
1
2
3
4
5
6
插入学生数据

insert into student values
(1,'赵雷','1990-01-01','男'),
(2,'钱电','1990-12-21','男'),
(3,'孙风','1990-05-20','男'),
(4,'李云','1990-08-06','男'),
(5,'周梅','1991-12-01','女'),
(6,'吴兰','1992-03-01','女'),
(7,'郑竹','1989-07-01','女'),
(8,'王菊','1990-01-20','女');
用码道免费领 1 个月 Token
sql
1
2
3
4
5
6
7
8
9


课程表 course
创建课程表 course

create table course (
c_id int,
c_name varchar(8),
t_id int
);
用码道免费领 1 个月 Token
sql
1
2
3
4
5
插入课程数据

insert into course values
(1,'语文',2),
(2,'数学',1),
(3,'英语',3);
用码道免费领 1 个月 Token
sql
1
2
3
4


教师表 teacher
创建教师表 teacher

create table teacher (
t_id int,
t_name varchar(8)
);
用码道免费领 1 个月 Token
sql
1
2
3
4
插入教师数据

insert into teacher values
(1,'张三'),
(2,'李四'),
(3,'王五');
用码道免费领 1 个月 Token
sql
1
2
3
4


成绩表 score
创建成绩表 score

create table score (
s_id int,
c_id int,
s_score int
);
用码道免费领 1 个月 Token
sql
1
2
3
4
5
插入成绩数据

insert into score values
(1,1,80),
(1,2,90),
(1,3,99),
(2,1,70),
(2,2,60),
(2,3,65),
(3,1,80),
(3,2,80),
(3,3,80),
(4,1,50),
(4,2,30),
(4,3,40),
(5,1,76),
(5,2,87),
(6,1,31),
(6,3,34),
(7,2,89),
(7,3,98);
用码道免费领 1 个月 Token
sql

1
2
3
4
5
6
7
8
9
10
11
12
13
14
15
16
17
18
19


表关系



经典练习 50 题


1、查询"01"课程比"02"课程成绩高的学生的信息及课程分数
select s.*,sc1.s_score as score_01,sc2.s_score as score_02
from student s
inner join (
select * from score where c_id = 1
) sc1
on s.s_id = sc1.s_id
inner join (
select * from score where c_id = 2
) sc2
on s.s_id = sc2.s_id
where sc1.s_score > sc2.s_score;
用码道免费领 1 个月 Token
sql

1
2
3
4
5
6
7
8
9
10
11
+------+--------+------------+-------+----------+----------+
| s_id | s_name | s_birth    | s_sex | score_01 | score_02 |
+------+--------+------------+-------+----------+----------+
|    2 | 钱电   | 1990-12-21 | 男    |       70 |       60 |
|    4 | 李云   | 1990-08-06 | 男    |       50 |       30 |
+------+--------+------------+-------+----------+----------+
用码道免费领 1 个月 Token
sql
1
2
3
4
5
6


2、查询"01"课程比"02"课程成绩低的学生的信息及课程分数
select s.*,sc1.s_score as score_01,sc2.s_score as score_02
from student s
inner join (
select * from score where c_id = 1
) sc1
on s.s_id = sc1.s_id
inner join (
select * from score where c_id = 2
) sc2
on s.s_id = sc2.s_id
where sc1.s_score < sc2.s_score;
用码道免费领 1 个月 Token
sql

1
2
3
4
5
6
7
8
9
10
11
+------+--------+------------+-------+----------+----------+
| s_id | s_name | s_birth    | s_sex | score_01 | score_02 |
+------+--------+------------+-------+----------+----------+
|    1 | 赵雷   | 1990-01-01 | 男    |       80 |       90 |
|    5 | 周梅   | 1991-12-01 | 女    |       76 |       87 |
+------+--------+------------+-------+----------+----------+
用码道免费领 1 个月 Token
sql
1
2
3
4
5
6


3、查询平均成绩大于等于60分的同学的学生编号和学生姓名和平均成绩
select s.s_id,s.s_name,round(avg_score, 2) as avg_score
from student s
inner join (
select s_id,avg(s_score) as avg_score
from score
group by s_id
having avg_score >= 60
) t1
on s.s_id = t1.s_id;
用码道免费领 1 个月 Token
sql
1
2
3
4
5
6
7
8
9
+------+--------+-----------+
| s_id | s_name | avg_score |
+------+--------+-----------+
|    1 | 赵雷   | 89.67     |
|    2 | 钱电   | 65.00     |
|    3 | 孙风   | 80.00     |
|    5 | 周梅   | 81.50     |
|    7 | 郑竹   | 93.50     |
+------+--------+-----------+
用码道免费领 1 个月 Token
sql
1
2
3
4
5
6
7
8
9


4、查询平均成绩小于60分的同学的学生编号和学生姓名和平均成绩(包括有成绩的和无成绩的)
select s.s_id,s.s_name,ifnull(round(avg_score, 2), 0) as avg_score
from student s
left join (
select s_id,avg(s_score) as avg_score
from score
group by s_id
) t1
on s.s_id = t1.s_id
where avg_score is null or avg_score < 60;
用码道免费领 1 个月 Token
sql
1
2
3
4
5
6
7
8
9
+------+--------+-----------+
| s_id | s_name | avg_score |
+------+--------+-----------+
|    4 | 李云   | 40.00     |
|    6 | 吴兰   | 32.50     |
|    8 | 王菊   | 0.00      |
+------+--------+-----------+
用码道免费领 1 个月 Token
sql
1
2
3
4
5
6
7


5、查询所有同学的学生编号、学生姓名、选课总数、所有课程的总成绩
select s.s_id,s.s_name,ifnull(cnt_course, 0) as cnt_course,ifnull(sum_score, 0) as sum_score
from student s
left join (
select s_id,count(*) as cnt_course,sum(s_score) as sum_score
from score
group by s_id
) t1
on s.s_id = t1.s_id;
用码道免费领 1 个月 Token
sql
1
2
3
4
5
6
7
8
+------+--------+------------+-----------+
| s_id | s_name | cnt_course | sum_score |
+------+--------+------------+-----------+
|    1 | 赵雷   |          3 | 269       |
|    2 | 钱电   |          3 | 195       |
|    3 | 孙风   |          3 | 240       |
|    4 | 李云   |          3 | 120       |
|    5 | 周梅   |          2 | 163       |
|    6 | 吴兰   |          2 | 65        |
|    7 | 郑竹   |          2 | 187       |
|    8 | 王菊   |          0 | 0         |
+------+--------+------------+-----------+
用码道免费领 1 个月 Token
sql

1
2
3
4
5
6
7
8
9
10
11
12


6、查询"李"姓老师的数量
select count(*) as cnt_name_li
from teacher
where t_name like '李%';
用码道免费领 1 个月 Token
sql
1
2
3
+-------------+
| cnt_name_li |
+-------------+
|           1 |
+-------------+
用码道免费领 1 个月 Token
sql
1
2
3
4
5


7、询学过"张三"老师授课的同学的信息
select s.*
from student s
where s_id in (
select distinct s_id
from score sc
inner join (
select c_id
from course c
inner join teacher t on c.t_id = t.t_id
where t_name = '张三'
) t1
on sc.c_id = t1.c_id
);
用码道免费领 1 个月 Token
sql

1
2
3
4
5
6
7
8
9
10
11
12
13
+------+--------+------------+-------+
| s_id | s_name | s_birth    | s_sex |
+------+--------+------------+-------+
|    1 | 赵雷   | 1990-01-01 | 男    |
|    2 | 钱电   | 1990-12-21 | 男    |
|    3 | 孙风   | 1990-05-20 | 男    |
|    4 | 李云   | 1990-08-06 | 男    |
|    5 | 周梅   | 1991-12-01 | 女    |
|    7 | 郑竹   | 1989-07-01 | 女    |
+------+--------+------------+-------+
用码道免费领 1 个月 Token
sql

1
2
3
4
5
6
7
8
9
10


8、查询没学过"张三"老师授课的同学的信息
select s.*
from student s
where s_id not in (
select distinct s_id
from score sc
inner join (
select c_id
from course c
inner join teacher t on c.t_id = t.t_id
where t_name = '张三'
) t1
on sc.c_id = t1.c_id
);
用码道免费领 1 个月 Token
sql

1
2
3
4
5
6
7
8
9
10
11
12
13
+------+--------+------------+-------+
| s_id | s_name | s_birth    | s_sex |
+------+--------+------------+-------+
|    6 | 吴兰   | 1992-03-01 | 女    |
|    8 | 王菊   | 1990-01-20 | 女    |
+------+--------+------------+-------+
用码道免费领 1 个月 Token
sql
1
2
3
4
5
6


9、查询学过编号为"01"并且也学过编号为"02"的课程的同学的信息
select s.*
from student s
where s_id in (
select s_id
from score
where c_id in (1,2)
group by s_id
having count(*) = 2
);
用码道免费领 1 个月 Token
sql
1
2
3
4
5
6
7
8
9
+------+--------+------------+-------+
| s_id | s_name | s_birth    | s_sex |
+------+--------+------------+-------+
|    1 | 赵雷   | 1990-01-01 | 男    |
|    2 | 钱电   | 1990-12-21 | 男    |
|    3 | 孙风   | 1990-05-20 | 男    |
|    4 | 李云   | 1990-08-06 | 男    |
|    5 | 周梅   | 1991-12-01 | 女    |
+------+--------+------------+-------+
用码道免费领 1 个月 Token
sql
1
2
3
4
5
6
7
8
9


10、查询学过编号为"01"但是没有学过编号为"02"的课程的同学的信息
select s.*
from student s
where s_id in (
select s_id
from score
where c_id = 1
and s_id not in (
select s_id from score where c_id = 2
)
);
用码道免费领 1 个月 Token
sql

1
2
3
4
5
6
7
8
9
10
+------+--------+------------+-------+
| s_id | s_name | s_birth    | s_sex |
+------+--------+------------+-------+
|    6 | 吴兰   | 1992-03-01 | 女    |
+------+--------+------------+-------+
用码道免费领 1 个月 Token
sql
1
2
3
4
5


11、查询没有学全所有课程的同学的信息
select *
from student
where s_id not in (
select s_id
from score
group by s_id
having count(*) = (
select count(*) from course
)
);
用码道免费领 1 个月 Token
sql

1
2
3
4
5
6
7
8
9
10
+------+--------+------------+-------+
| s_id | s_name | s_birth    | s_sex |
+------+--------+------------+-------+
|    5 | 周梅   | 1991-12-01 | 女    |
|    6 | 吴兰   | 1992-03-01 | 女    |
|    7 | 郑竹   | 1989-07-01 | 女    |
|    8 | 王菊   | 1990-01-20 | 女    |
+------+--------+------------+-------+
用码道免费领 1 个月 Token
sql
1
2
3
4
5
6
7
8


12、查询至少有一门课与学号为"01"的同学所学相同的同学的信息
select *
from student
where s_id in (
select distinct s_id
from score s
inner join (
select c_id from score where s_id = 1
) t1
on s.c_id = t1.c_id
);
用码道免费领 1 个月 Token
sql

1
2
3
4
5
6
7
8
9
10
+------+--------+------------+-------+
| s_id | s_name | s_birth    | s_sex |
+------+--------+------------+-------+
|    1 | 赵雷   | 1990-01-01 | 男    |
|    2 | 钱电   | 1990-12-21 | 男    |
|    3 | 孙风   | 1990-05-20 | 男    |
|    4 | 李云   | 1990-08-06 | 男    |
|    5 | 周梅   | 1991-12-01 | 女    |
|    6 | 吴兰   | 1992-03-01 | 女    |
|    7 | 郑竹   | 1989-07-01 | 女    |
+------+--------+------------+-------+
用码道免费领 1 个月 Token
sql

1
2
3
4
5
6
7
8
9
10
11


13、查询和"01"号的同学学习的课程完全相同的其他同学的信息
select *
from student
where s_id in (
select s_id
from score s
inner join (
select c_id from score where s_id = 1
) t1
on s.c_id = t1.c_id
where s_id != 1
group by s_id having count(*) = (
select count(*) from score where s_id = 1
)
);
用码道免费领 1 个月 Token
sql

1
2
3
4
5
6
7
8
9
10
11
12
13
14
+------+--------+------------+-------+
| s_id | s_name | s_birth    | s_sex |
+------+--------+------------+-------+
|    2 | 钱电   | 1990-12-21 | 男    |
|    3 | 孙风   | 1990-05-20 | 男    |
|    4 | 李云   | 1990-08-06 | 男    |
+------+--------+------------+-------+
用码道免费领 1 个月 Token
sql
1
2
3
4
5
6
7


14、查询没学过"张三"老师讲授的任一门课程的学生姓名
select s_name
from student
where s_id not in (
select s_id
from score sc
inner join (
select c_id
from course c
inner join teacher t on c.t_id = t.t_id
where t_name = '张三'
) t1
on sc.c_id = t1.c_id
);
用码道免费领 1 个月 Token
sql

1
2
3
4
5
6
7
8
9
10
11
12
13
+--------+
| s_name |
+--------+
| 吴兰   |
| 王菊   |
+--------+
用码道免费领 1 个月 Token
sql
1
2
3
4
5
6


15、查询两门及其以上不及格课程的同学的学号，姓名及其平均成绩
select s.s_id,s.s_name,round(avg(s_score), 2) as avg_score
from student s
inner join (
select s_id
from score
where s_score < 60
group by s_id
having count(*) >= 2
) t1
on s.s_id = t1.s_id
inner join score sc on s.s_id = sc.s_id
group by s.s_id;
用码道免费领 1 个月 Token
sql

1
2
3
4
5
6
7
8
9
10
11
12
+------+--------+-----------+
| s_id | s_name | avg_score |
+------+--------+-----------+
|    4 | 李云   | 40.00     |
|    6 | 吴兰   | 32.50     |
+------+--------+-----------+
用码道免费领 1 个月 Token
sql
1
2
3
4
5
6


16、检索"01"课程分数小于60，按分数降序排列的学生信息
select s.*,s_score as score_01
from student s
inner join (
select s_id,s_score
from score
where c_id = 1
and s_score < 60
) t1
on s.s_id = t1.s_id
order by score_01 desc;
用码道免费领 1 个月 Token
sql

1
2
3
4
5
6
7
8
9
10
+------+--------+------------+-------+----------+
| s_id | s_name | s_birth    | s_sex | score_01 |
+------+--------+------------+-------+----------+
|    4 | 李云   | 1990-08-06 | 男    |       50 |
|    6 | 吴兰   | 1992-03-01 | 女    |       31 |
+------+--------+------------+-------+----------+
用码道免费领 1 个月 Token
sql
1
2
3
4
5
6


17、按平均成绩从高到低显示所有学生的所有课程的成绩以及平均成绩
select
s.s_id as 学号,s.s_name as 姓名,
sum(case c_id when 1 then s_score else 0 end) as 语文,
sum(case c_id when 2 then s_score else 0 end) as 数学,
sum(case c_id when 3 then s_score else 0 end) as 英语,
ifnull(round(avg(s_score), 2), 0) as 平均成绩
from student s
left join score sc on s.s_id = sc.s_id
group by s.s_id
order by 平均成绩 desc;
用码道免费领 1 个月 Token
sql

1
2
3
4
5
6
7
8
9
10
+------+------+------+------+------+----------+
| 学号 | 姓名 | 语文 | 数学 | 英语 | 平均成绩 |
+------+------+------+------+------+----------+
|    7 | 郑竹 | 0    | 89   | 98   | 93.50    |
|    1 | 赵雷 | 80   | 90   | 99   | 89.67    |
|    5 | 周梅 | 76   | 87   | 0    | 81.50    |
|    3 | 孙风 | 80   | 80   | 80   | 80.00    |
|    2 | 钱电 | 70   | 60   | 65   | 65.00    |
|    4 | 李云 | 50   | 30   | 40   | 40.00    |
|    6 | 吴兰 | 31   | 0    | 34   | 32.50    |
|    8 | 王菊 | 0    | 0    | 0    | 0.00     |
+------+------+------+------+------+----------+
用码道免费领 1 个月 Token
sql

1
2
3
4
5
6
7
8
9
10
11
12


18、查询各科成绩最高分、最低分和平均分，以如下形式显示：
课程ID，课程name，最高分，最低分，平均分，及格率，中等率，优良率，优秀率
– 及格为>=60，中等为：70-80，优良为：80-90，优秀为：>=90

select
c.c_id as 课程ID,c.c_name as 课程name,
max(s_score) as 最高分,
min(s_score) as 最低分,
round(avg(s_score), 2) as 平均分,
concat(round(sum(case when s_score >= 60 then 1 else 0 end) / count(*) * 100, 2), '%') as 及格率,
concat(round(sum(case when s_score between 70 and 80 then 1 else 0 end) / count(*) * 100, 2), '%') as 中等率,
concat(round(sum(case when s_score between 80 and 90 then 1 else 0 end) / count(*) * 100, 2), '%') as 优良率,
concat(round(sum(case when s_score >= 90 then 1 else 0 end) / count(*) * 100, 2), '%') as 优秀率
from course c
inner join score s on c.c_id = s.c_id
group by c.c_id;
用码道免费领 1 个月 Token
sql

1
2
3
4
5
6
7
8
9
10
11
12
+--------+----------+--------+--------+--------+--------+--------+--------+--------+
| 课程ID | 课程name | 最高分 | 最低分 | 平均分 | 及格率 | 中等率 | 优良率 | 优秀率 |
+--------+----------+--------+--------+--------+--------+--------+--------+--------+
|      1 | 语文     |     80 |     31 | 64.50  | 66.67% | 66.67% | 33.33% | 0.00%  |
|      2 | 数学     |     90 |     30 | 72.67  | 83.33% | 16.67% | 66.67% | 16.67% |
|      3 | 英语     |     99 |     34 | 69.33  | 66.67% | 16.67% | 16.67% | 33.33% |
+--------+----------+--------+--------+--------+--------+--------+--------+--------+
用码道免费领 1 个月 Token
sql
1
2
3
4
5
6
7


19、按各科成绩进行排序，并显示排名
select
(@i := case when @pre_group_id = c_id then @i + 1 else 1 end) as rank,
(@pre_group_id := c_id) as c_id,
c_name,s_id,s_name,s_score
from (select @i := 0, @pre_group_id := 1) var
cross join (
select c.c_id,c.c_name,s.s_id,s.s_name,s_score
from score sc
inner join student s on sc.s_id = s.s_id
inner join course c on sc.c_id = c.c_id
group by c.c_id,s.s_id
order by c.c_id,s_score desc
) t1;
用码道免费领 1 个月 Token
sql

1
2
3
4
5
6
7
8
9
10
11
12
13
+------+------+--------+------+--------+---------+
| rank | c_id | c_name | s_id | s_name | s_score |
+------+------+--------+------+--------+---------+
|    1 |    1 | 语文   |    1 | 赵雷   |      80 |
|    2 |    1 | 语文   |    3 | 孙风   |      80 |
|    3 |    1 | 语文   |    5 | 周梅   |      76 |
|    4 |    1 | 语文   |    2 | 钱电   |      70 |
|    5 |    1 | 语文   |    4 | 李云   |      50 |
|    6 |    1 | 语文   |    6 | 吴兰   |      31 |
|    1 |    2 | 数学   |    1 | 赵雷   |      90 |
|    2 |    2 | 数学   |    7 | 郑竹   |      89 |
|    3 |    2 | 数学   |    5 | 周梅   |      87 |
|    4 |    2 | 数学   |    3 | 孙风   |      80 |
|    5 |    2 | 数学   |    2 | 钱电   |      60 |
|    6 |    2 | 数学   |    4 | 李云   |      30 |
|    1 |    3 | 英语   |    1 | 赵雷   |      99 |
|    2 |    3 | 英语   |    7 | 郑竹   |      98 |
|    3 |    3 | 英语   |    3 | 孙风   |      80 |
|    4 |    3 | 英语   |    2 | 钱电   |      65 |
|    5 |    3 | 英语   |    4 | 李云   |      40 |
|    6 |    3 | 英语   |    6 | 吴兰   |      34 |
+------+------+--------+------+--------+---------+
用码道免费领 1 个月 Token
sql

1
2
3
4
5
6
7
8
9
10
11
12
13
14
15
16
17
18
19
20
21
22


20、查询学生的总成绩并进行排名
select (@i := @i + 1) as rank,t2.*
from (select  @i := 0) var
cross join (
select s.s_id,s.s_name,sum_score
from student s
inner join (
select s_id,sum(s_score) as sum_score
from score
group by s_id
) t1
on s.s_id = t1.s_id
order by sum_score desc
) t2;
用码道免费领 1 个月 Token
sql

1
2
3
4
5
6
7
8
9
10
11
12
13
+------+------+--------+-----------+
| rank | s_id | s_name | sum_score |
+------+------+--------+-----------+
|    1 |    1 | 赵雷   | 269       |
|    2 |    3 | 孙风   | 240       |
|    3 |    2 | 钱电   | 195       |
|    4 |    7 | 郑竹   | 187       |
|    5 |    5 | 周梅   | 163       |
|    6 |    4 | 李云   | 120       |
|    7 |    6 | 吴兰   | 65        |
+------+------+--------+-----------+
用码道免费领 1 个月 Token
sql

1
2
3
4
5
6
7
8
9
10
11


21、查询不同老师所教不同课程平均分从高到低显示
select t.t_id,t.t_name,c.c_id,c.c_name,round(avg(s_score), 2) as avg_score
from score sc
inner join course c on sc.c_id = c.c_id
inner join teacher t on t.t_id = c.t_id
group by t.t_id,c.c_id
order by t.t_id,avg_score desc;
用码道免费领 1 个月 Token
sql
1
2
3
4
5
6
+------+--------+------+--------+-----------+
| t_id | t_name | c_id | c_name | avg_score |
+------+--------+------+--------+-----------+
|    1 | 张三   |    2 | 数学   | 72.67     |
|    2 | 李四   |    1 | 语文   | 64.50     |
|    3 | 王五   |    3 | 英语   | 69.33     |
+------+--------+------+--------+-----------+
用码道免费领 1 个月 Token
sql
1
2
3
4
5
6
7


22、查询所有课程的成绩第2名到第3名的学生信息及该课程成绩
select *
from (
select
(@i := case when @pre_group_id = c_id then @i + 1 else 1 end) as rank,
(@pre_group_id := c_id) as c_id,
c_name,s_id,s_name,s_birth,s_sex,s_score
from (select @i := 0, @pre_group_id := 1) var
cross join (
select c.c_id,c.c_name,s.*,s_score
from score sc
inner join student s on sc.s_id = s.s_id
inner join course c on sc.c_id = c.c_id
group by c.c_id,s.s_id
order by c.c_id,s_score desc
) t1
) t2
where rank in (2,3);
用码道免费领 1 个月 Token
sql

1
2
3
4
5
6
7
8
9
10
11
12
13
14
15
16
17
+------+------+--------+------+--------+------------+-------+---------+
| rank | c_id | c_name | s_id | s_name | s_birth    | s_sex | s_score |
+------+------+--------+------+--------+------------+-------+---------+
|    2 |    1 | 语文   |    3 | 孙风   | 1990-05-20 | 男    |      80 |
|    3 |    1 | 语文   |    5 | 周梅   | 1991-12-01 | 女    |      76 |
|    2 |    2 | 数学   |    7 | 郑竹   | 1989-07-01 | 女    |      89 |
|    3 |    2 | 数学   |    5 | 周梅   | 1991-12-01 | 女    |      87 |
|    2 |    3 | 英语   |    7 | 郑竹   | 1989-07-01 | 女    |      98 |
|    3 |    3 | 英语   |    3 | 孙风   | 1990-05-20 | 男    |      80 |
+------+------+--------+------+--------+------------+-------+---------+
用码道免费领 1 个月 Token
sql

1
2
3
4
5
6
7
8
9
10


23、统计各科成绩各分数段人数：课程编号,课程名称,[100-85],[85-70],[70-60],[0-60]及所占百分比
select
c.c_id as 课程编号,c.c_name as 课程名称,
sum(case when s_score between 85 and 100 then 1 else 0 end) as '[100-85]',
concat(round(sum(case when s_score between 85 and 100 then 1 else 0 end) / count(*) * 100, 2), '%') as 百分比,
sum(case when s_score between 70 and 85 then 1 else 0 end) as '[85-70]',
concat(round(sum(case when s_score between 70 and 85 then 1 else 0 end) / count(*) * 100, 2), '%') as 百分比,
sum(case when s_score between 60 and 70 then 1 else 0 end) as '[70-60]',
concat(round(sum(case when s_score between 60 and 70 then 1 else 0 end) / count(*) * 100, 2), '%') as 百分比,
sum(case when s_score between 0 and 60 then 1 else 0 end) as '[0-60]',
concat(round(sum(case when s_score between 0 and 60 then 1 else 0 end) / count(*) * 100, 2), '%') as 百分比
from course c
inner join score sc
on c.c_id = sc.c_id
group by c.c_id;
用码道免费领 1 个月 Token
sql

1
2
3
4
5
6
7
8
9
10
11
12
13
14
+----------+----------+----------+--------+---------+--------+---------+--------+--------+--------+
| 课程编号 | 课程名称 | [100-85] | 百分比 | [85-70] | 百分比 | [70-60] | 百分比 | [0-60] | 百分比 |
+----------+----------+----------+--------+---------+--------+---------+--------+--------+--------+
|        1 | 语文     | 0        | 0.00%  | 4       | 66.67% | 1       | 16.67% | 2      | 33.33% |
|        2 | 数学     | 3        | 50.00% | 1       | 16.67% | 1       | 16.67% | 2      | 33.33% |
|        3 | 英语     | 2        | 33.33% | 1       | 16.67% | 1       | 16.67% | 2      | 33.33% |
+----------+----------+----------+--------+---------+--------+---------+--------+--------+--------+
用码道免费领 1 个月 Token
sql
1
2
3
4
5
6
7


24、查询学生平均成绩及其名次
select (@i := @i + 1) as rank,t2.*
from (select  @i := 0) var
cross join (
select s.s_id,s.s_name,avg_score
from student s
inner join (
select s_id,round(avg(s_score), 2) as avg_score
from score
group by s_id
) t1
on s.s_id = t1.s_id
order by avg_score desc
) t2;
用码道免费领 1 个月 Token
sql

1
2
3
4
5
6
7
8
9
10
11
12
13
+------+------+--------+-----------+
| rank | s_id | s_name | avg_score |
+------+------+--------+-----------+
|    1 |    7 | 郑竹   | 93.50     |
|    2 |    1 | 赵雷   | 89.67     |
|    3 |    5 | 周梅   | 81.50     |
|    4 |    3 | 孙风   | 80.00     |
|    5 |    2 | 钱电   | 65.00     |
|    6 |    4 | 李云   | 40.00     |
|    7 |    6 | 吴兰   | 32.50     |
+------+------+--------+-----------+
用码道免费领 1 个月 Token
sql

1
2
3
4
5
6
7
8
9
10
11


25、查询各科成绩前三名的记录
方法 1：内外关联

select c.c_id,c.c_name,s.s_id,s.s_name,s_score
from (
select *
from score sc
where (
select count(*)
from score sc1
where sc.c_id = sc1.c_id
and sc.s_score < sc1.s_score
) < 3
)
t1
inner join student s on t1.s_id = s.s_id
inner join course c on t1.c_id = c.c_id
order by c.c_id,s_score desc;
用码道免费领 1 个月 Token
sql

1
2
3
4
5
6
7
8
9
10
11
12
13
14
15
+------+--------+------+--------+---------+
| c_id | c_name | s_id | s_name | s_score |
+------+--------+------+--------+---------+
|    1 | 语文   |    1 | 赵雷   |      80 |
|    1 | 语文   |    3 | 孙风   |      80 |
|    1 | 语文   |    5 | 周梅   |      76 |
|    2 | 数学   |    1 | 赵雷   |      90 |
|    2 | 数学   |    7 | 郑竹   |      89 |
|    2 | 数学   |    5 | 周梅   |      87 |
|    3 | 英语   |    1 | 赵雷   |      99 |
|    3 | 英语   |    7 | 郑竹   |      98 |
|    3 | 英语   |    3 | 孙风   |      80 |
+------+--------+------+--------+---------+
用码道免费领 1 个月 Token
sql

1
2
3
4
5
6
7
8
9
10
11
12
13
方法 2：序号筛选

select *
from (
select
(@i := case when @pre_group_id = c_id then @i + 1 else 1 end) as rank,
(@pre_group_id := c_id) as c_id,
c_name,s_id,s_name,s_score
from (select @i := 0, @pre_group_id := 1) var
cross join (
select c.c_id,c.c_name,s.s_id,s.s_name,s_score
from score sc
inner join student s on sc.s_id = s.s_id
inner join course c on sc.c_id = c.c_id
group by c.c_id,s.s_id
order by c.c_id,s_score desc
) t1
) t2
where rank <= 3;
用码道免费领 1 个月 Token
sql

1
2
3
4
5
6
7
8
9
10
11
12
13
14
15
16
17
+------+------+--------+------+--------+---------+
| rank | c_id | c_name | s_id | s_name | s_score |
+------+------+--------+------+--------+---------+
|    1 |    1 | 语文   |    1 | 赵雷   |      80 |
|    2 |    1 | 语文   |    3 | 孙风   |      80 |
|    3 |    1 | 语文   |    5 | 周梅   |      76 |
|    1 |    2 | 数学   |    1 | 赵雷   |      90 |
|    2 |    2 | 数学   |    7 | 郑竹   |      89 |
|    3 |    2 | 数学   |    5 | 周梅   |      87 |
|    1 |    3 | 英语   |    1 | 赵雷   |      99 |
|    2 |    3 | 英语   |    7 | 郑竹   |      98 |
|    3 |    3 | 英语   |    3 | 孙风   |      80 |
+------+------+--------+------+--------+---------+
用码道免费领 1 个月 Token
sql

1
2
3
4
5
6
7
8
9
10
11
12
13


26、查询每门课程被选修的学生数
select c.c_id,c.c_name,cnt_student
from course c
inner join (
select c_id,count(*) as cnt_student
from score
group by c_id
) t1
on c.c_id = t1.c_id;
用码道免费领 1 个月 Token
sql
1
2
3
4
5
6
7
8
+------+--------+-------------+
| c_id | c_name | cnt_student |
+------+--------+-------------+
|    1 | 语文   |           6 |
|    2 | 数学   |           6 |
|    3 | 英语   |           6 |
+------+--------+-------------+
用码道免费领 1 个月 Token
sql
1
2
3
4
5
6
7


27、查询出只有两门课程的全部学生的学号和姓名
select s_id,s_name
from student
where s_id in (
select s_id
from score
group by s_id
having count(*) = 2
);
用码道免费领 1 个月 Token
sql
1
2
3
4
5
6
7
8
+------+--------+
| s_id | s_name |
+------+--------+
|    5 | 周梅   |
|    6 | 吴兰   |
|    7 | 郑竹   |
+------+--------+
用码道免费领 1 个月 Token
sql
1
2
3
4
5
6
7


28、查询男生、女生人数
select s_sex,count(*) as cnt_sex
from student
group by s_sex;
用码道免费领 1 个月 Token
sql
1
2
3
+-------+---------+
| s_sex | cnt_sex |
+-------+---------+
| 女    |       4 |
| 男    |       4 |
+-------+---------+
用码道免费领 1 个月 Token
sql
1
2
3
4
5
6


29、查询名字中含有"风"字的学生信息
select *
from student
where s_name like '%风%';
用码道免费领 1 个月 Token
sql
1
2
3
+------+--------+------------+-------+
| s_id | s_name | s_birth    | s_sex |
+------+--------+------------+-------+
|    3 | 孙风   | 1990-05-20 | 男    |
+------+--------+------------+-------+
用码道免费领 1 个月 Token
sql
1
2
3
4
5


30、查询同名同性学生名单，并统计同名人数
select *,count(*) as cnt_student
from student
group by s_name,s_sex
having cnt_student > 1;
用码道免费领 1 个月 Token
sql
1
2
3
4
+-----------+
| Empty set |
+-----------+
用码道免费领 1 个月 Token
sql
1
2
3


31、查询1990年出生的学生名单
select *
from student
where year(s_birth) = '1990';
用码道免费领 1 个月 Token
sql
1
2
3
+------+--------+------------+-------+
| s_id | s_name | s_birth    | s_sex |
+------+--------+------------+-------+
|    1 | 赵雷   | 1990-01-01 | 男    |
|    2 | 钱电   | 1990-12-21 | 男    |
|    3 | 孙风   | 1990-05-20 | 男    |
|    4 | 李云   | 1990-08-06 | 男    |
|    8 | 王菊   | 1990-01-20 | 女    |
+------+--------+------------+-------+
用码道免费领 1 个月 Token
sql
1
2
3
4
5
6
7
8
9


32、查询每门课程的平均成绩，结果按平均成绩降序排列，平均成绩相同时，按课程编号升序排列
select c.c_id,c.c_name,avg_score
from course c
inner join (
select c_id,round(avg(s_score), 2) as avg_score
from score
group by c_id
) t1
on c.c_id = t1.c_id
order by avg_score desc,c.c_id;
用码道免费领 1 个月 Token
sql
1
2
3
4
5
6
7
8
9
+------+--------+-----------+
| c_id | c_name | avg_score |
+------+--------+-----------+
|    2 | 数学   | 72.67     |
|    3 | 英语   | 69.33     |
|    1 | 语文   | 64.50     |
+------+--------+-----------+
用码道免费领 1 个月 Token
sql
1
2
3
4
5
6
7


33、查询平均成绩大于等于85的所有学生的学号、姓名和平均成绩
select s.s_id,s.s_name,avg_score
from student s
inner join (
select s_id,round(avg(s_score), 2) as avg_score
from score
group by s_id
having avg_score >= 85
) t1
on s.s_id = t1.s_id;
用码道免费领 1 个月 Token
sql
1
2
3
4
5
6
7
8
9
+------+--------+-----------+
| s_id | s_name | avg_score |
+------+--------+-----------+
|    1 | 赵雷   | 89.67     |
|    7 | 郑竹   | 93.50     |
+------+--------+-----------+
用码道免费领 1 个月 Token
sql
1
2
3
4
5
6


34、查询课程名称为"数学"，且分数低于60的学生姓名和分数
select s_name,s_score
from student s
inner join (
select s_id,s_score
from score sc
inner join course c on sc.c_id = c.c_id
where c_name = '数学'
and s_score < 60
) t1
on s.s_id = t1.s_id;
用码道免费领 1 个月 Token
sql

1
2
3
4
5
6
7
8
9
10
+--------+---------+
| s_name | s_score |
+--------+---------+
| 李云   |      30 |
+--------+---------+
用码道免费领 1 个月 Token
sql
1
2
3
4
5


35、查询所有学生的课程及分数情况
select
s.s_id,s.s_name,
sum(case c_id when 1 then s_score else 0 end) as 语文,
sum(case c_id when 2 then s_score else 0 end) as 数学,
sum(case c_id when 3 then s_score else 0 end) as 英语
from student s
left join score sc on s.s_id = sc.s_id
group by s.s_id;
用码道免费领 1 个月 Token
sql
1
2
3
4
5
6
7
8
+------+--------+------+------+------+
| s_id | s_name | 语文 | 数学 | 英语 |
+------+--------+------+------+------+
|    1 | 赵雷   | 80   | 90   | 99   |
|    2 | 钱电   | 70   | 60   | 65   |
|    3 | 孙风   | 80   | 80   | 80   |
|    4 | 李云   | 50   | 30   | 40   |
|    5 | 周梅   | 76   | 87   | 0    |
|    6 | 吴兰   | 31   | 0    | 34   |
|    7 | 郑竹   | 0    | 89   | 98   |
|    8 | 王菊   | 0    | 0    | 0    |
+------+--------+------+------+------+
用码道免费领 1 个月 Token
sql

1
2
3
4
5
6
7
8
9
10
11
12


36、查询任何一门课程成绩在70分以上的学生姓名、课程名称和分数
select s_name,c_name,s_score
from score sc
inner join student s on sc.s_id =  s.s_id
inner join course c on sc.c_id = c.c_id
where s_score > 70
group by s.s_id;
用码道免费领 1 个月 Token
sql
1
2
3
4
5
6
+--------+--------+---------+
| s_name | c_name | s_score |
+--------+--------+---------+
| 赵雷   | 语文   |      80 |
| 孙风   | 语文   |      80 |
| 周梅   | 语文   |      76 |
| 郑竹   | 数学   |      89 |
+--------+--------+---------+
用码道免费领 1 个月 Token
sql
1
2
3
4
5
6
7
8


37、查询课程不及格的学生
select s.s_id,s.s_name,c.c_id,c.c_name,s_score
from score sc
inner join student s on sc.s_id =  s.s_id
inner join course c on sc.c_id = c.c_id
where s_score < 60;
用码道免费领 1 个月 Token
sql
1
2
3
4
5
+------+--------+------+--------+---------+
| s_id | s_name | c_id | c_name | s_score |
+------+--------+------+--------+---------+
|    4 | 李云   |    1 | 语文   |      50 |
|    4 | 李云   |    2 | 数学   |      30 |
|    4 | 李云   |    3 | 英语   |      40 |
|    6 | 吴兰   |    1 | 语文   |      31 |
|    6 | 吴兰   |    3 | 英语   |      34 |
+------+--------+------+--------+---------+
用码道免费领 1 个月 Token
sql
1
2
3
4
5
6
7
8
9


38、查询课程编号为01且课程成绩在80分以上的学生的学号和姓名
select s.s_id,s.s_name
from student s
where s_id in (
select s_id
from score
where c_id = 1
and s_score >= 80
);
用码道免费领 1 个月 Token
sql
1
2
3
4
5
6
7
8
+------+--------+
| s_id | s_name |
+------+--------+
|    1 | 赵雷   |
|    3 | 孙风   |
+------+--------+
用码道免费领 1 个月 Token
sql
1
2
3
4
5
6


39、求每门课程的学生人数
select c.c_id,c.c_name,cnt_student
from course c
inner join (
select c_id,count(*) as cnt_student
from score
group by c_id
) t1
on c.c_id = t1.c_id;
用码道免费领 1 个月 Token
sql
1
2
3
4
5
6
7
8
+------+--------+-------------+
| c_id | c_name | cnt_student |
+------+--------+-------------+
|    1 | 语文   |           6 |
|    2 | 数学   |           6 |
|    3 | 英语   |           6 |
+------+--------+-------------+
用码道免费领 1 个月 Token
sql
1
2
3
4
5
6
7


40、查询选修"张三"老师所授课程的学生中，成绩最高的学生信息及其成绩
select s.*,s_score as max_score
from student s
inner join (
select s_id,s_score
from score sc
inner join (
select c_id
from course c
inner join teacher t on c.t_id = t.t_id
where t_name = '张三'
) t1
on sc.c_id = t1.c_id
order by s_score desc
limit 1
) t2
on s.s_id = t2.s_id;
用码道免费领 1 个月 Token
sql

1
2
3
4
5
6
7
8
9
10
11
12
13
14
15
16
+------+--------+------------+-------+-----------+
| s_id | s_name | s_birth    | s_sex | max_score |
+------+--------+------------+-------+-----------+
|    1 | 赵雷   | 1990-01-01 | 男    |        90 |
+------+--------+------------+-------+-----------+
用码道免费领 1 个月 Token
sql
1
2
3
4
5


41、查询不同课程成绩相同的学生的学生编号、课程编号、学生成绩
select *
from score
where s_score in (
select s_score
from score
group by s_score
having count(*) > 1
);
用码道免费领 1 个月 Token
sql
1
2
3
4
5
6
7
8
+------+------+---------+
| s_id | c_id | s_score |
+------+------+---------+
|    1 |    1 |      80 |
|    3 |    1 |      80 |
|    3 |    2 |      80 |
|    3 |    3 |      80 |
+------+------+---------+
用码道免费领 1 个月 Token
sql
1
2
3
4
5
6
7
8


42、查询每门课程成绩最好的前三名
方法 1：内外关联

select c.c_id,c.c_name,s.s_id,s.s_name,s_score
from (
select *
from score sc
where (
select count(*)
from score sc1
where sc.c_id = sc1.c_id
and sc.s_score < sc1.s_score
) < 3
) t1
inner join student s on t1.s_id = s.s_id
inner join course c on t1.c_id = c.c_id
order by c.c_id,s_score desc;
用码道免费领 1 个月 Token
sql

1
2
3
4
5
6
7
8
9
10
11
12
13
14
+------+--------+------+--------+---------+
| c_id | c_name | s_id | s_name | s_score |
+------+--------+------+--------+---------+
|    1 | 语文   |    1 | 赵雷   |      80 |
|    1 | 语文   |    3 | 孙风   |      80 |
|    1 | 语文   |    5 | 周梅   |      76 |
|    2 | 数学   |    1 | 赵雷   |      90 |
|    2 | 数学   |    7 | 郑竹   |      89 |
|    2 | 数学   |    5 | 周梅   |      87 |
|    3 | 英语   |    1 | 赵雷   |      99 |
|    3 | 英语   |    7 | 郑竹   |      98 |
|    3 | 英语   |    3 | 孙风   |      80 |
+------+--------+------+--------+---------+
用码道免费领 1 个月 Token
sql

1
2
3
4
5
6
7
8
9
10
11
12
13
方法 2：序号筛选

select *
from (
select
(@i := case when @pre_group_id = c_id then @i + 1 else 1 end) as rank,
(@pre_group_id := c_id) as c_id,
c_name,s_id,s_name,s_score
from (select @i := 0, @pre_group_id := 1) var
cross join (
select c.c_id,c.c_name,s.s_id,s.s_name,s_score
from score sc
inner join student s on sc.s_id = s.s_id
inner join course c on sc.c_id = c.c_id
group by c.c_id,s.s_id
order by c.c_id,s_score desc
) t1
) t2
where rank <= 3;
用码道免费领 1 个月 Token
sql

1
2
3
4
5
6
7
8
9
10
11
12
13
14
15
16
17
+------+------+--------+------+--------+---------+
| rank | c_id | c_name | s_id | s_name | s_score |
+------+------+--------+------+--------+---------+
|    1 |    1 | 语文   |    1 | 赵雷   |      80 |
|    2 |    1 | 语文   |    3 | 孙风   |      80 |
|    3 |    1 | 语文   |    5 | 周梅   |      76 |
|    1 |    2 | 数学   |    1 | 赵雷   |      90 |
|    2 |    2 | 数学   |    7 | 郑竹   |      89 |
|    3 |    2 | 数学   |    5 | 周梅   |      87 |
|    1 |    3 | 英语   |    1 | 赵雷   |      99 |
|    2 |    3 | 英语   |    7 | 郑竹   |      98 |
|    3 |    3 | 英语   |    3 | 孙风   |      80 |
+------+------+--------+------+--------+---------+
用码道免费领 1 个月 Token
sql

1
2
3
4
5
6
7
8
9
10
11
12
13


43、统计每门课程的学生选修人数（超过5人的课程才统计）
– 要求输出课程号和选修人数，查询结果按人数降序排列，若人数相同，按课程号升序排列

select c.c_id,cnt_student
from course c
inner join (
select c_id,count(*) as cnt_student
from score
group by c_id
having cnt_student > 5
) t1
on c.c_id = t1.c_id
order by cnt_student desc,c.c_id;
用码道免费领 1 个月 Token
sql

1
2
3
4
5
6
7
8
9
10
+------+-------------+
| c_id | cnt_student |
+------+-------------+
|    1 |           6 |
|    2 |           6 |
|    3 |           6 |
+------+-------------+
用码道免费领 1 个月 Token
sql
1
2
3
4
5
6
7


44、检索至少选修两门课程的学生学号
select s_id
from score
group by s_id
having count(*) >= 2;
用码道免费领 1 个月 Token
sql
1
2
3
4
+------+
| s_id |
+------+
|    1 |
|    2 |
|    3 |
|    4 |
|    5 |
|    6 |
|    7 |
+------+
用码道免费领 1 个月 Token
sql

1
2
3
4
5
6
7
8
9
10
11


45、查询选修了全部课程的学生信息
select *
from student
where s_id in (
select s_id
from score
group by s_id
having count(*) = (
select count(*) from course
)
);
用码道免费领 1 个月 Token
sql

1
2
3
4
5
6
7
8
9
10
+------+--------+------------+-------+
| s_id | s_name | s_birth    | s_sex |
+------+--------+------------+-------+
|    1 | 赵雷   | 1990-01-01 | 男    |
|    2 | 钱电   | 1990-12-21 | 男    |
|    3 | 孙风   | 1990-05-20 | 男    |
|    4 | 李云   | 1990-08-06 | 男    |
+------+--------+------------+-------+
用码道免费领 1 个月 Token
sql
1
2
3
4
5
6
7
8


46、查询各学生的年龄(周岁)
– 按照出生日期来算，当前月日 < 出生年月的月日则，年龄减一

select
s_id,s_name,s_birth,
if (
month(current_date()) < month(s_birth) or (month(current_date()) = month(s_birth) and day(current_date()) < day(s_birth)),
year(current_date()) - year(s_birth) -1,
year(current_date()) - year(s_birth)
)
as s_age
from student;
用码道免费领 1 个月 Token
sql

1
2
3
4
5
6
7
8
9


47、查询本周过生日的学生
select *
from student
where
datediff(concat(year(current_date()), date_format(s_birth, '-%m-%d')), current_date())
between 0 and 7
or
datediff(concat(year(current_date()) + 1, date_format(s_birth, '-%m-%d')), current_date())
between 0 and 7;
用码道免费领 1 个月 Token
sql
1
2
3
4
5
6
7
8


48、查询下周过生日的学生
select *
from student
where
datediff(concat(year(current_date()), date_format(s_birth, '-%m-%d')), current_date())
between 7 and 14
or
datediff(concat(year(current_date()) + 1, date_format(s_birth, '-%m-%d')), current_date())
between 7 and 14;
用码道免费领 1 个月 Token
sql
1
2
3
4
5
6
7
8


49、查询本月过生日的学生
select *
from student
where month(s_birth) = month(current_date());
用码道免费领 1 个月 Token
sql
1
2
3


50、查询12月份过生日的学生
select *
from student
where month(s_birth) = 12;
用码道免费领 1 个月 Token
sql
1
2
3
+------+--------+------------+-------+
| s_id | s_name | s_birth    | s_sex |
+------+--------+------------+-------+
|    2 | 钱电   | 1990-12-21 | 男    |
|    5 | 周梅   | 1991-12-01 | 女    |
+------+--------+------------+-------+
用码道免费领 1 个月 Token
sql
1
2
3
4
5
6
标签
#mysql
————————————————
版权声明：本文为CSDN博主「GodSuzzZ」的原创文章，遵循CC 4.0 BY-SA版权协议，转载请附上原文出处链接及本声明。
原文链接：https://blog.csdn.net/GodSuzzZ/article/details/106930311