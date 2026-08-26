# 定义
* 一个基于内存的的key-value结构数据库
* 基于内存存储，其读写性能高
# 数据类型
* key都是字符串类型
* value有五种类型
## 字符串
## 哈希
散列，类似java中的HashMap，数据中有filed--value 对
## 列表
按照插入顺序排序，可以有重复元素
## 集合
无序集合，没有重复元素
## 有序集合
有序集合，没有重复元素，每个元素关联一个分数，按照分数升序排序,其中元素是String类型
# 常用命令
* mysql的sql命令与数据类型无关，但redis对于不同的数据类型有不同的操作命令
* 命令大小写不敏感
## 字符串
* SET key value
* GET key
* setex key value 只有key不存在的时候设置key的值
* SETEX key seconds value 设置指定key的值，并将过期时间设置为seconds （短信验证码）
## 哈希
* hset key field value 将哈希表key中field的值设置为value
* hget key field 获取哈希表key中field的值
* hdel key field 删除哈希表key中field字段
* hkeys key  获取哈希表key的所有field字段
* hvalues key 获取哈希表key的所有value字段
## 列表-只能支持先进先出
* lpush key value1 value2 将一个或者多个值插入到列表头部
* lrange key start stop 获取列表指定范围内元素
* rpop key 移除并获取列表右边的第一个元素
* llen key 获取列表长度
## 集合
* sadd key m1 m2 添加一个或多个成员
* smembers key 返回所有成员（无序返回
* scard key 获取集合成员数
* sinter key1 key2 返回给定所有集合的交集
* sunion key1 key2 返回给定所有集合的并集
* srem key m1 m2 移除集合中一个或多个成员
## 有序集合
* zadd key score m1 score2 m2 插入
* zrange key start stop 返回指定区间的
* zincrby key increment member 对指定成员的分数加上increment
* zrem key member 移除一个或多个成员
## 通用命令
* keys pattern 查找所有符合给定pattern的key
* exists key 检查给定是否存在
* type key 返回key所对应的value类型
* del key 删除key