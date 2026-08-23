-- KEYS[1] = "schedule:{scheduleId}"  (Redis ZSET key)
-- ARGV[1] = userId (string)
-- ARGV[2] = timestamp (毫秒，用作 ZSET score)
-- ARGV[3] = maxQueueSize (string)
-- 返回：-1=已在队列，0=队列已满，正整数=排队名次（从 1 开始）

local existScore = redis.call('ZSCORE', KEYS[1], ARGV[1])
if existScore then
    return -1
end

if redis.call('ZCARD', KEYS[1]) >= tonumber(ARGV[3]) then
    return 0
end

redis.call('ZADD', KEYS[1], tonumber(ARGV[2]), ARGV[1])
return redis.call('ZRANK', KEYS[1], ARGV[1]) + 1
