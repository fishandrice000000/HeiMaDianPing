-- 秒杀业务需要的Lua脚本

-- !!! 将Key在脚本内组装而非直接传入, 
-- !!! 会使得脚本在集群下运行时, 
-- !!! 因客户端无法得知脚本会使用的key
-- !!! 而不能将请求路由到正确节点, 所以报错

-- 1. 参数列表
-- 1.1 优惠券id
local voucherId = ARGV[1]
-- 1.2 用户id
local userId = ARGV[2]

-- 2. 需要的key
-- 2.1 库存key
local stockKey = 'seckill:stock:' .. voucherId
-- 2.2 订单key
local orderKey = 'seckill:order:' .. voucherId 

-- 3. 脚本业务
-- 3.1 判断库存是否充足
if (tonumber(redis.call('GET', stockKey)) <= 0) then
    -- 3.2 库存不足, 返回1
    return 1
end

-- 3.3 判断用户是否重复下单
if (redis.call('SISMEMBER', orderKey, userId)) then 
    -- 3.4 用户重复下单, 返回2
    return 2
end

-- 3.5 扣减库存
redis.call('INCRBY', stockKey, -1)
-- 3.6 加入订单信息
redis.call('SADD', orderKey, userId)

return 0
