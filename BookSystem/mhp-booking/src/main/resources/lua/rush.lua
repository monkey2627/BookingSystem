-- 抢档期原子操作 Lua 脚本
--
-- 为什么用 Lua？
--   Redis 单线程执行 Lua 脚本，ZSCORE + ZCARD + ZADD 三步作为原子整体执行，
--   不会被其他命令插入，天然防止并发下的重复入队和超额入队。
--
-- 数据结构：Redis ZSET（有序集合）
--   key   = "schedule:{scheduleId}"
--   member = userId（字符串）
--   score  = 时间戳毫秒（决定排队顺序，先来先得）
--
-- 参数：
--   KEYS[1] = "schedule:{scheduleId}"
--   ARGV[1] = userId（字符串）
--   ARGV[2] = 当前时间戳毫秒（作为 ZSET score，保证先来先得的顺序）
--   ARGV[3] = maxQueueSize（队列容量上限）
--
-- 返回值：
--   -1 = 用户已在队列中（重复入队）
--    0 = 队列已满
--   正整数 = 排队名次（从 1 开始，ZRANK 返回 0-based，加 1 转换）

-- 检查该用户是否已在队列中（ZSCORE 有值即存在）
local existScore = redis.call('ZSCORE', KEYS[1], ARGV[1])
if existScore then
    return -1
end

-- 检查队列是否已满（ZCARD 返回当前成员数）
if redis.call('ZCARD', KEYS[1]) >= tonumber(ARGV[3]) then
    return 0
end

-- 入队：以时间戳为 score，保证先来的 score 小，ZRANK 排名靠前
redis.call('ZADD', KEYS[1], tonumber(ARGV[2]), ARGV[1])

-- ZRANK 返回 0-based 排名，+1 转为 1-based 展示给用户
return redis.call('ZRANK', KEYS[1], ARGV[1]) + 1
