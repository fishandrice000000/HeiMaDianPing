package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.hmdp.utils.RedisConstants.*;

@Slf4j
@Component
public class CacheClient {

    private final StringRedisTemplate stringRedisTemplate;

    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    public CacheClient(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    /**
     * 写入普通缓存并设置过期时间
     */
    public void set(String key, Object value, Long time, TimeUnit unit) {
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value), time, unit);
    }

    /**
     * 写入逻辑过期缓存
     */
    public void setWithLogicalExpire(String key, Object value, Long time, TimeUnit unit) {
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }

    /**
     * 解决缓存穿透：缓存空值
     *
     * @param keyPrefix  Redis Key 前缀
     * @param id         查询的ID
     * @param type       返回值的Class类型
     * @param dbFallback 数据库查询回调函数 (如果缓存没命中，执行此函数)
     * @param time       缓存过期时间
     * @param unit       时间单位
     * @return 泛型对象
     */
    public <R, ID> R queryWithPassThrough(
            String keyPrefix, ID id, Class<R> type, Function<ID, R> dbFallback, Long time, TimeUnit unit) {
        String key = keyPrefix + id;
        // 1. 从Redis查询缓存
        String json = stringRedisTemplate.opsForValue().get(key);

        // 2. 判断是否存在
        if (StrUtil.isNotBlank(json)) {
            // 存在，直接返回
            return JSONUtil.toBean(json, type);
        }
        // 判断命中的是否是空值 (缓存穿透的空对象)
        if (json != null) {
            return null;
        }

        // 3. 没查到，调用传入的函数去数据库查询
        R r = dbFallback.apply(id);

        // 4. 不存在，返回错误并写入空值到Redis
        if (r == null) {
            stringRedisTemplate.opsForValue().set(key, CACHE_NULL_VALUE, CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }

        // 5. 存在，写入Redis
        this.set(key, r, time, unit);
        return r;
    }

    /**
     * 解决缓存击穿：逻辑过期
     */
    public <R, ID> R queryWithLogicalExpire(
            String keyPrefix, ID id, Class<R> type, String lockKeyPrefix, Function<ID, R> dbFallback, Long time, TimeUnit unit) {
        String key = keyPrefix + id;
        // 1. 从redis查询缓存
        String json = stringRedisTemplate.opsForValue().get(key);

        // 2. 判断是否存在
        if (StrUtil.isBlank(json)) {
            // 逻辑过期通常配合缓存预热，如果没查到说明不是热点key，直接返回null或者退化成普通查询
            return null;
        }

        // 3. 命中，需要先把json反序列化为对象
        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        R r = JSONUtil.toBean((JSONObject) redisData.getData(), type);

        // 4. 判断是否过期
        if (redisData.getExpireTime().isAfter(LocalDateTime.now())) {
            // 5. 未过期，直接返回信息
            return r;
        }

        // 6. 已过期，需要缓存重建
        String lockKey = lockKeyPrefix + id;
        boolean isLock = tryLock(lockKey);
        
        // 7. 判断是否获取锁成功
        if (isLock) {
            // 7.1 Double Check缓存
            String checkJson = stringRedisTemplate.opsForValue().get(key);
            RedisData checkRedisData = JSONUtil.toBean(checkJson, RedisData.class);

            // 7.2 如果查到了, 直接解锁再走人
            if (checkRedisData != null && checkRedisData.getExpireTime().isAfter(LocalDateTime.now())) {
                unlock(lockKey);
                return JSONUtil.toBean((JSONObject) checkRedisData.getData(), type);
            }

            // 7.3 如果不行, 再开启独立线程，实现缓存重建
            CACHE_REBUILD_EXECUTOR.execute(() -> {
                try {
                    // 查询数据库
                    R newR = dbFallback.apply(id);
                    // 重建缓存
                    this.setWithLogicalExpire(key, newR, time, unit);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    unlock(lockKey);
                }
            });
        }
        // 8. 返回过期的信息 (无论是否拿到锁，都返回旧数据)
        return r;
    }

    /**
     * 解决缓存击穿：互斥锁
     */
    public <R, ID> R queryWithMutex(
            String keyPrefix, ID id, Class<R> type, String lockKeyPrefix, Function<ID, R> dbFallback, Long time, TimeUnit unit) {
        String key = keyPrefix + id;
        String lockKey = lockKeyPrefix + id;
        int maxRetries = MAX_RETRY_COUNT;

        while (maxRetries > 0) {
            // 1. 从redis查询缓存
            String json = stringRedisTemplate.opsForValue().get(key);
            // 2. 判断是否存在
            if (StrUtil.isNotBlank(json)) {
                return JSONUtil.toBean(json, type);
            }
            // 判断命中的是否是空值
            if (json != null) {
                return null;
            }

            // 3. 实现缓存重建
            // 3.1 获取互斥锁
            boolean isLock = tryLock(lockKey);
            // 3.2 判断是否获取成功
            if (isLock) {
                try {
                    // 3.3 成功，根据id查询数据库
                    R r = dbFallback.apply(id);
                    // 3.4 模拟重建延时
                    Thread.sleep(200);

                    if (r == null) {
                        // 将空值写入redis
                        stringRedisTemplate.opsForValue().set(key, CACHE_NULL_VALUE, CACHE_NULL_TTL, TimeUnit.MINUTES);
                        return null;
                    }
                    // 写入redis
                    this.set(key, r, time, unit);
                    return r;
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                } finally {
                    // 3.5 释放互斥锁
                    unlock(lockKey);
                }
            } else {
                // 3.6 失败，休眠并重试
                try {
                    Thread.sleep(50);
                    maxRetries--;
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
            }
        }
        return null; // 重试次数耗尽
    }

    private boolean tryLock(String key) {
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", LOCK_SHOP_TTL, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    private void unlock(String key) {
        stringRedisTemplate.delete(key);
    }
}