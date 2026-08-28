# 索引
DML：DML

DML：Data Manipulation Language 数据操纵语言
用来对表里面的数据行做增删改查。

DML包含4类：

1.  SELECT  查询数据
2.  INSERT  插入新增行
3.  UPDATE  修改已有行
4.  DELETE  删除行

sql


-- DML示例
select * from user;
insert into user(name) values('张三');
update user set name='李四' where id=1;
delete from user where id=1;




容易混淆的其他SQL分类（面试常考）

- DDL 数据定义语言：操作库、表结构，不操作行数据
  CREATE  /  ALTER  /  DROP  /  TRUNCATE

truncate是DDL！不是DML，删除整张表全部数据，不记行日志，不能回滚。

- DCL 数据控制语言：权限
  GRANT 授权、 REVOKE 回收权限
- TCL 事务控制语言：管理事务
  COMMIT 提交、 ROLLBACK 回滚、 SAVEPOINT 保存点

关键区分点

1. DML操作表中的行记录，会产生undo日志，可以事务回滚。
2. DML执行完需要 commit 才真正持久化（InnoDB）。
3. DDL是改结构，执行自动隐式提交事务，不能回滚。

易错： truncate table  删除全部数据，属于DDL，不是DML；delete才是DML。

快速记忆口诀

- DML：增删查改行数据
- DDL：建删改表和库
- DCL：授权回收权限
- TCL：提交回滚事务

补充小坑：有些教材会把select单独分出DQL（查询语言），考试如果问到：

- 广义：select属于DML
- 狭义：DQL=select，DML只包含 insert/update/delete。
  面试两种说法都见过，看题目语境。
  ACID:

innoDB引擎：
[图片 22.jpg]
索引：
什么是索引？
一种能够高效获取数据的有序数据结构，也就是说数据库在维护数据的同时，还维护着一个为了方便查询的数据结构。索引中存储着指向原表数据（即完整数据）的指针
索引的优点和缺点
[图片 23.jpg]
但是缺点在现在基本是可以忽略
索引的结构？
索引是由存储引擎实现的，所以不同的存储引擎对索引的实现不同
大部分是b+树索引
还有hash索引 空间索引和全文索引，默认是b+树
b+树：有序的，一个节点有多个子节点，解决二叉树大数据量层级深的问题。
首先b树：多路平衡查找树，每个节点有n个key和n+1个指针，每个key作为分割，每个节点都存值
b+树：每个节点都存值，但是是为了构造树，但只有叶子节点存的是全部的值，中间都是导航，即叶子节点中也会保存向上分裂的值，插入过程中如果违背n个中间元素向上分裂，称为n+1阶，并且，不同的叶子节点之间形成一个链表
为什么要选择b+，而不是b或者二叉或者其它
索引的分类：
* 按照..分类：常规索引，全文索引，唯一索引，主键索引
* 按照存储形式分类
* 聚集索引：数据存储与索引放在一起，索引结构的叶子节点保存了行数据
* 二级索引：顾名思义，需要两次查询，先查主键，再通过主键查聚集索引，将数据和索引分开存储，索引结构的叶子节点关联的是对应的主键
* 必须有聚集索引,且只能有一个
* ![img.png](img.png)
![img_2.png](img_2.png)
* 存储是按照页为单位，页的大小固定为16k
* ![img_3.png](img_3.png)三层大概可以两千多万个记录，所以查找速度特别快
## 索引的语法
* 创建索引
create 
* 单列 复合
* 查看索引
show index from table_name
* 删除索引
Drop name on table_name
## SQL性能分析
* 执行频率：show global status
* 可以查看数据库增删改查的sql的次数，如果不是查询为主，就没太必要优化索引
* 慢查询日志：记录了所有执行时间超过指定参数的sql语句，默认没有开启，需要配置show_query_log=1
* profile详情：show profile 能够在做优化时候帮我们查看时间都花费到哪里了，可以看到每一条sql语句的耗时情况
* show profile for query {}查看详细的每一条语句的各个阶段的耗时
* explain执行计划：可以在任意的查询语句前加上EXPLAIN/DESC 可以看到其查询的过程的信息，包括查询过程中表如何连接
  * 各字段含义：id：表结构的连接顺序、
  * select_type:查询类型
  * type:标识连接类型，性能由好到差分别为：
  * possible_key：可能用到的索引
  * key：实际上使用的
  * key_len
  * rows
## 索引的使用原则
* 最左前缀法则，对于联合索引，最左边的列必须存在才会走索引，如果中间有列被跳过，其之后的索引都会失效，注意和其在sql语句中放的位置没有区别
* 范围查询右边的列索引将会失效，注意使用≥或者≤不会
* 如果在索引列上进行运算，那么索引会失效
* 字符串不写‘’，索引会失效
* 模糊查询：如果头部模糊那么索引将会失效
* or连接的条件，如果有一列没有索引，那么所有的索引都不会被用到
* isnull/is not null 是否走索引取决于数据分布
* sql提示：在sql语句中写 use index(index_name) 来建议mysql，但是mysql有可能不接受
* ignore index() 建议忽略
* force index() 强制，必须用
* 覆盖索引：select后面尽量避免使用 *，原因是避免回表查询，即查完2级索引之后还要查，一旦索引中有不存在的字段，就要二次查询
* 前缀索引：将字符串内容的一部分抽取出来作为索引的内容，语法： 在字段名后面加(n)即可
* 单列索引和联合索引：联合索引即索引中包含多个字段![img_4.png](img_4.png)
![img_5.png](img_5.png)
## 索引的设计原则
![img_6.png](img_6.png)
# innerDB引擎
## 逻辑存储结构
![img_7.png](img_7.png)
表空间就是ibd文件，一个mysql实例可以对应多个表空间，用于存储记录、索引等
![img_8.png](img_8.png)
一个区1M，一个页16KB;
## 架构
* 内存+磁盘
### 内存架构
![img_12.png](img_12.png)
![img_13.png](img_13.png)
![img_9.png](img_9.png)
![img_11.png](img_11.png)
### 磁盘架构
![img_14.png](img_14.png)
![img_15.png](img_15.png)
![img_16.png](img_16.png)
![img_17.png](img_17.png)
![img_18.png](img_18.png)
### 内存结构通过后台线程刷新到磁盘文件
![img_19.png](img_19.png)
## 事务的隔离性
## 分类四种
* 串行
* 重复读
* 不可重复读
*
## 事务原理
* ACID分别代表什么？
* ACD是由redo log和undo log
* I通过锁机制和MVCC
### redo log
* 保证事务持久性
* ![img_20.png](img_20.png)
* redo log里面记录的数据长什么样子？
### undo log
![img_21.png](img_21.png)
undo log中的记录数据长什么样？
## MVCC
* 多版本并发控制 
* ![img_22.png](img_22.png)
* 是什么？
### 实现原理
1.记录当中的隐藏字段：
![img_23.png](img_23.png)
2. undo log
3. ![img_24.png](img_24.png)
4. readview
   * 四个核心字段：![img_25.png](img_25.png)
   * ![img_26.png](img_26.png)
   * 不同的隔离级别，生成readview的时机不同
   * ![img_27.png](img_27.png)
   * 例子：RC级别![img_28.png](img_28.png)
   * ![img_29.png](img_29.png)
   * 快照读就是顺着mvcc的版本链从最新开始往后匹配，然后返回第一条符合的
   * 例子：RR级别，仅在第一次执行快照读的时候生成readview，后续这个事务中其他所有的读都会复用第一个readview
# 锁
1.什么是锁？

## 全局锁
* 对数据库实例加锁，锁定所有表，整个实例只能读，使用场景是做全库的逻辑备份
* ![img_30.png](img_30.png)
* mysqldump 用于备份一个数据库
* ![img_31.png](img_31.png)
### 全局锁是如何实现的？
## 表级锁
* 锁住整张表
* 分为表共享读锁和表独占写锁,读锁不会阻塞读操作，但写锁会阻塞读和其他客户端的写
* 元数据锁MDL：系统自动控制加锁，在访问一张表的时候会自动加上，维护表元数据的一致性，元数据对应的是表的结构
* ![img_32.png](img_32.png)
* 为了避免ddl和dml语句的冲突
* 语法：lock tables read/write
* 意向锁：意向共享锁和意向排他锁
* 为什么会出现意向锁？为啥要使用意向锁
* 
### 表级锁是如何实现的？
## 行级锁
* 锁住对应行数据
* 行锁：共享、排他
* 间隙锁
* 临界锁，为了避免幻读，为什么能避免？
* ![img_33.png](img_33.png)
* ![img_34.png](img_34.png)
* ![img_35.png](img_35.png)
* ![img_36.png](img_36.png)
* ![img_37.png](img_37.png)
* ![img_38.png](img_38.png)