package com.hmdp.utils;

import java.util.concurrent.TimeUnit;

import org.springframework.data.redis.core.StringRedisTemplate;

import cn.hutool.core.lang.UUID;

public class SimpleRedisLock implements ILock {

    private String name;
    private StringRedisTemplate stringRedisTemplate;

    public SimpleRedisLock(String name, StringRedisTemplate stringRedisTemplate) {
        this.name = name;
        this.stringRedisTemplate = stringRedisTemplate;
    }

    private static final String KEY_PREFIX = "lock:";
    // 随机ID, 加上线程ID, 保证每个线程创造的锁都是唯一的, 避免误删锁
    private static final String ID_PREFIX = UUID.randomUUID().toString() + "-";

    @Override
    public boolean tryLock(long timeoutSec) {
        // 获得全局线程唯一ID
        String threadId = ID_PREFIX + Thread.currentThread().getId();
        // 获取锁,
        Boolean success = stringRedisTemplate.opsForValue()
                .setIfAbsent(KEY_PREFIX + name, threadId, timeoutSec, TimeUnit.SECONDS);
        return Boolean.TRUE.equals(success);
    }

    @Override
    public void unlock() {
        // 首先获取线程的id
        String threadId = ID_PREFIX + Thread.currentThread().getId();

        /* ↓↓↓ 释放锁操作并非原子化 ↓↓↓ */

        // 获取锁中的id, 检查是否一致
        String lockId = stringRedisTemplate.opsForValue().get(KEY_PREFIX + name);
        // id一致才释放锁, 避免误删
        if (threadId.equals(lockId)) {
            stringRedisTemplate.delete(KEY_PREFIX + name);
        }
    }

}
