# 基础操作
## 创建/删除表格
### 创建
CREATE TABLE users (
    
    字段名 字段类型 额外描述

    id INT AUTO_INCREMENT,
    name VARCHAR(100) NOT NULL,
    email VARCHAR(100),
    PRIMARY KEY (id)
);
## 删除
DROP TABLE
## 排序
用order by字段表示依据什么排序，默认为升序，降序需要加上DESC
### 多字段排序
按照优先级一个一个说明即可
字段名 + DESC/ASC,用逗号分隔
对，但要补一个关键前提：**没有 group by 的时候，全部收拢合并成 1 行**。
一旦有 `group by`，就不是全部合并成1行，而是**按组，每组各自收拢合并成一行**。

# 聚合函数核心定义
`count / sum / max / min / avg` 都叫聚合函数，作用：**对一组多行数据，计算出一个单一结果值**。

## 情况1：没有 group by → 整个结果集就是**一大组**
把 WHERE 筛选出来的所有行，当成**同一个大组**，聚合函数算出来**1个值，输出1行**。
```sql
-- 全部符合where的行，合并成1行
SELECT COUNT(*),MAX(id),MIN(id),AVG(id) FROM user WHERE phone='138xxxx1234';
```
哪怕匹配0行，依旧输出1行：`0, null, null, null`
> count特殊：count输出0；sum/max/min/avg匹配不到数据会返回`null`。

## 情况2：有 group by → 拆成很多个小组，**每组合并成1行**
不是全局合并，先拆分小组，**每个小组内部做聚合，一组输出一行**。
```sql
SELECT phone,COUNT(*) FROM user GROUP BY phone;
```
- phone=138xxx 这一堆行 →收拢算出1个count，输出1行
- phone=139xxx 这一堆行 →收拢算出1个count，输出1行
  有多少不同分组，最终就多少行。

### 通俗比喻
- 无group by：全班所有人算一个总分，输出1个结果。
- group by phone：按班级分组，每个班各自算总分，一个班输出一条结果。

## 超级重要的易错点
> 写了group by，select后面普通列，必须是group by的列。
```sql
-- mysql宽松模式会跑，但逻辑错误！
SELECT phone,name,COUNT(*) FROM user GROUP BY phone;
```
按phone分组，同一个phone有多条不同name，数据库不知道拿哪个name，结果是随机的。

## 极简记忆
1. **聚合函数：对一批行，算出一个值**
2. **无group by：整张筛选后的表 = 1个大组，输出1行**
3. **有group by：拆成N个小组，每组输出1行，总共N行**

### 回到你最开始的SQL
```sql
SELECT COUNT(*) FROM user WHERE phone = '138xxxx1234'
```
WHERE筛出一批行，**没有group by →全部作为一个大组，count算出1个数字，返回1行**。
匹配不到数据，大组依旧存在，count返回0。

> 补充对比普通非聚合select：
`select phone from user where phone='xxx'`，没有聚合，**一行原始数据输出一行，不会合并**。匹配不到直接空结果集，没有行。

要不要我给你整理一张小对比表，把 count/sum/max/min 在有无group by、无匹配数据的返回值全部列出来？
# group by 规则：select 的普通列，必须出现在 group by 里
先分清两类列：
1. **聚合列**：`count(*)`、`sum()`、`max()`……聚合函数，是算出来的值，不受这个限制。
2. **普通列**：直接写字段名，比如 `phone`、`name`、`id`，拿数据库原始行的数据。

> 规则原话：**使用 GROUP BY 时，SELECT 后面出现的非聚合的普通字段，必须写在 GROUP BY 的后面**。

## 举反例（错误写法）
```sql
-- ❌错误示范
SELECT phone, name, COUNT(*) 
FROM user 
GROUP BY phone;
```
- `phone`：写在了 `GROUP BY phone`，没问题
- `name`：**普通列，既不是聚合函数，又不在group by里面** → 违规

### 为什么错？
假设表里数据：

| id | phone      | name |
|----|------------|------|
| 1  | 13800001111| 张三 |
| 2  | 13800001111| 李四 |

按 `phone` 分组，`13800001111` 这一组包含两行：张三、李四。
分组之后这一组合并成**一行输出**。
👉问题来了：**同一个phone，有两个不同name，数据库该输出张三？还是李四？没有标准答案！**

数据库陷入两难，标准SQL直接拒绝执行这条SQL。

> MySQL有个坑：如果开启了 `ONLY_FULL_GROUP_BY` **关闭**（老版本默认关闭），MySQL不会报错，它会**随便挑一个name返回**，时而是张三，时而是李四，数据随机，bug很难排查。
> 现在新版本MySQL默认开启`ONLY_FULL_GROUP_BY`，这条SQL直接报错。

## ✅正确写法1：把name加到group by
```sql
SELECT phone, name, COUNT(*) 
FROM user 
GROUP BY phone, name;
```
现在分组规则变成：**phone+name一起作为分组条件**。
上面例子会分成两组：
1. phone=13800001111，name=张三 → count=1
2. phone=13800001111，name=李四 → count=1

## ✅正确写法2：不要select这个普通列，或者包进聚合函数
如果你就想要phone这一组，不想要区分name：那就不要写name。
```sql
SELECT phone, COUNT(*) 
FROM user 
GROUP BY phone;
```

如果你非要拿到组内的某个name，可以套聚合函数：
```sql
SELECT phone, MAX(name), COUNT(*) 
FROM user 
GROUP BY phone;
```
`MAX(name)`：取分组里字典序最大的name，明确告诉数据库选哪一个。

## 一句话通俗总结
> group by把多行压成一行。
> 你select一个普通字段，如果这个分组里有多条不同的值，数据库不知道该拿哪个值给你，语法就不允许。
> 要么你把这个字段也加入分组条件；要么就不要查这个字段；要么用max/min聚合选一个。

## 快速做题检验
表：user(id,dept,name)
1. `select dept,count(*) from user group by dept;` ✔
   dept在group by；count聚合，没问题。

2. `select dept,name,count(*) from user group by dept;` ❌
   name普通列，不在group by，也没有聚合，报错。

3. `select dept,max(name),count(*) from user group by dept;` ✔
   name被max()包裹成聚合函数，可以。

---

### 面试小考点
MySQL `ONLY_FULL_GROUP_BY` 这个sql_mode就是控制这个检查开关。
- 开启：严格遵守标准SQL，非法group by直接报错（推荐）
- 关闭：允许非法group by，随机返回数据，业务严禁。

需要我给你演示一下，如何查看当前数据库这个开关吗？