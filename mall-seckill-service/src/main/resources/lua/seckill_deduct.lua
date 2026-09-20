-- 秒杀原子扣减（M1.5 对标 miaosha 的 Redis+Lua 方案）
-- KEYS[1..N]  : 库存分桶 key（stock:seckill:{pid}:b0 ~ b{N-1}）
-- KEYS[N+1]   : 已购用户集合（stock:seckill:{pid}:users，SADD 实现一人一单）
-- ARGV[1]     : 购买数量 qty
-- ARGV[2]     : userId（判限购）
-- ARGV[3]     : 路由提示（userId 哈希，分散起始桶防集中热）
-- 返回: "OK|桶序号|扣减后余量" / "DUP"（重复购买）/ "SOLDOUT"
local qty = tonumber(ARGV[1])
local userId = ARGV[2]
local routeHash = tonumber(ARGV[3])
local userSet = KEYS[#KEYS]
local n = #KEYS - 1

if redis.call('SISMEMBER', userSet, userId) == 1 then
  return 'DUP'
end

local start = routeHash % n
for i = 0, n - 1 do
  local idx = (start + i) % n
  local remain = tonumber(redis.call('GET', KEYS[idx + 1]) or '0')
  if remain >= qty then
    redis.call('DECRBY', KEYS[idx + 1], qty)
    redis.call('SADD', userSet, userId)
    return 'OK|' .. idx .. '|' .. (remain - qty)
  end
end
return 'SOLDOUT'
