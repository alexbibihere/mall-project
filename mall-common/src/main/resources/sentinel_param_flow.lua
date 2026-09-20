-- M2.2: Sentinel 参数级滑动窗口限流（对齐 RateLimitAspect 的 ZSET+Lua 语义）
-- KEYS[1] = flow:{resource}__{paramHash}
-- ARGV[1] = now(ms)   ARGV[2] = window(s)   ARGV[3] = threshold   ARGV[4] = 事件唯一值
-- 返回 1=通过 0=拒绝
local key = KEYS[1]
local now = tonumber(ARGV[1])
local window = tonumber(ARGV[2])
local threshold = tonumber(ARGV[3])
local member = ARGV[4]

redis.call('ZREMRANGEBYSCORE', key, 0, now - window * 1000)
local cnt = redis.call('ZCARD', key)
if cnt >= threshold then
  return 0
end
redis.call('ZADD', key, now, member)
redis.call('PEXPIRE', key, window * 1000 + 1000)
return 1
