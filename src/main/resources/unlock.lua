-- KEYS[1] - 锁的key
-- ARGV[1] - 锁的value, 即线程对应的锁的标识

-- 如果锁的key对应的value与传入的value相同, 则删除锁, 否则不做任何操作
if (redis.call('get', KEYS[1]) == ARGV[1]) then
    return redis.call('del', KEYS[1])
end
return 0
