package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.hmdp.utils.RedisData;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private ShopMapper shopMapper;

    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(POOL_SIZE);

    /**
     * 根据id查询商店信息
     *
     * @param id 商店id
     * @return 若不存在返回404, 若存在返回商店信息
     */
    @Override
    public Result queryShopById(Long id) {
        // 解决了缓存穿透
//        Shop shop = queryWithPassThrough(id);

        // 解决了缓存穿透 + 缓存击穿
        Shop shop = queryWithMutex(id);

        if (shop == null) {
            return Result.fail("商店不存在");
        }

        return Result.ok(shop);
    }

    /**
     * 根据id查询商店信息, 利用Redis缓存
     * 利用新建空缓存, 解决了缓存穿透问题
     *
     * @param id 商店id
     * @return 若不存在返回null, 若存在返回商店信息
     */
    @Override
    public Shop queryWithPassThrough(Long id) {
        // 1.试图去Redis缓存中获取信息
        String shopKey = CACHE_SHOP_KEY + id;
        String cacheShopJSON = stringRedisTemplate.opsForValue().get(shopKey);

        // 2.1 若命中有效缓存, 返回之
        if (StrUtil.isNotBlank(cacheShopJSON)) {
            return JSONUtil.toBean(cacheShopJSON, Shop.class);
        }

        // 2.1 若命中空缓存, 返回之
        if (cacheShopJSON != null) {
            return null;
        }

        // 3. 若缓存未命中, 则去MySQL中获取
        Shop shop = shopMapper.selectById(id);

        // 4. 若仍未获取到, 则用户不存在. 并将空值写入Redis.
        if (shop == null) {
            // 将空值写入Redis
            stringRedisTemplate.opsForValue().set(shopKey, CACHE_NULL_VALUE, CACHE_NULL_TTL, TimeUnit.MINUTES);
        } else {
            // 5. 若获取到, 将获取到的信息写入Redis
            stringRedisTemplate.opsForValue().set(shopKey, JSONUtil.toJsonStr(shop), CACHE_SHOP_TTL, TimeUnit.MINUTES);
        }
        // 6. 结束
        return shop;
    }

    /**
     * 根据id查询商店信息, 利用Redis缓存
     * 利用逻辑过期, 解决了缓存击穿问题
     * 默认相关缓存已经经过预热, 即Redis中已经预先写入了热点Key
     * 所以不考虑空缓存的问题
     *
     * @param id 商店id
     * @return 若不存在返回null, 若存在返回商店信息
     */
    public Shop queryWithLogicalExpire(Long id) {
        // 1. 尝试从Redis处查询商铺缓存
        String shopKey = CACHE_SHOP_KEY + id;
        String lockKey = LOCK_SHOP_KEY + id;
        String cacheRedisDataJSON = stringRedisTemplate.opsForValue().get(shopKey);

        // 2.1 若未命中缓存, 返回之
        if (cacheRedisDataJSON == null) {
            return null;
        }

        // 2.2 若命中缓存, 判断缓存是否过期
        RedisData redisData = JSONUtil.toBean(cacheRedisDataJSON, RedisData.class);
        Shop shop = JSONUtil.toBean((JSONObject) redisData.getData(), Shop.class);

        // 2.3 未过期, 返回之
        if (redisData.getExpireTime().isAfter(LocalDateTime.now())) {
            return shop;
        }

        // 2.4 已过期, 尝试获取互斥锁
        boolean isLock = tryLock(lockKey);

        // 3.1 成功获取锁, 新建线程重建缓存
        if (isLock) {
            // 3.1.1 Double Check缓存
            String checkJson = stringRedisTemplate.opsForValue().get(shopKey);
            RedisData checkRedisData = JSONUtil.toBean(checkJson, RedisData.class);

            // 3.1.2 如果查到了, 直接解锁再走人
            if (checkRedisData != null && checkRedisData.getExpireTime().isAfter(LocalDateTime.now())) {
                unlock(lockKey);
                return JSONUtil.toBean((JSONObject) checkRedisData.getData(), Shop.class);
            }

            // 3.1.3 如果没查到, 那么新建线程再重建缓存
            CACHE_REBUILD_EXECUTOR.execute(() -> {
                try {
                    this.saveShopToRedisCache(id, CACHE_SHOP_TTL * 60);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    unlock(lockKey);
                }
            });
        }

        // 3.2 获取锁不论成功与否, 返回之
        return shop;
    }

    public void saveShopToRedisCache(Long id, Long expireSeconds) {
        // 1. 查询商店信息
        Shop shop = shopMapper.selectById(id);
        // 使用sleep模拟查询延时
        try {
            Thread.sleep(200L);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }

        // 2. 封装到RedisData类中
        RedisData redisData = new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));

        // 3. 写入Redis
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(redisData));
    }

    /**
     * 根据id查询商店信息, 利用Redis缓存
     * 利用新建空缓存, 解决了缓存穿透问题
     * 利用互斥锁, 解决了缓存击穿问题
     *
     * @param id 商店id
     * @return 若不存在返回null, 若存在返回商店信息
     */
    @Override
    public Shop queryWithMutex(Long id) {
        // 0. 准备Redis缓存Key互斥锁Key, 以及重试次数上限
        String shopKey = CACHE_SHOP_KEY + id;
        String lockKey = LOCK_SHOP_KEY + id;
        int maxRetries = MAX_RETRY_COUNT;

        // 1. 开始自旋
        while (maxRetries > 0) {
            // 2. 检查Redis缓存是否命中
            String cacheShopJSON = stringRedisTemplate.opsForValue().get(shopKey);
            // 2.1 命中非空值, 返回之
            if (StrUtil.isNotBlank(cacheShopJSON)) {
                return JSONUtil.toBean(cacheShopJSON, Shop.class);
            }
            // 2.2 命中空值, 返回之
            if (cacheShopJSON != null) {
                return null;
            }

            // 3. 未命中, 获取互斥锁
            boolean isLock = tryLock(lockKey);

            if (!isLock) {
                // 3.1 获取失败, 抛出异常, 线程休眠50ms
                try {
                    Thread.sleep(50L);
                    maxRetries--;
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
            } else {
                try {
                    // 3.2 获取成功, 尝试去MySQL查询数据
                    Shop shop = shopMapper.selectById(id);
                    // 使用sleep模拟查询延时
                    Thread.sleep(200L);

                    // 4.1 查询失败, 将空写入缓存
                    if (shop == null) {
                        stringRedisTemplate.opsForValue().set(shopKey, CACHE_NULL_VALUE, CACHE_NULL_TTL, TimeUnit.MINUTES);
                    }
                    // 4.2 查询成功, 并将Shop写入缓存
                    if (shop != null) {
                        stringRedisTemplate.opsForValue().set(shopKey, JSONUtil.toJsonStr(shop), CACHE_SHOP_TTL, TimeUnit.MINUTES);
                    }
                    // 5. 返回之
                    return shop;
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                } finally {
                    // 6. 不管怎样最后释放锁, 负责兜底来避免死锁
                    unlock(lockKey);
                }
            }
        }
        return null;
    }

    /**
     * 尝试获取互斥锁
     *
     * @param lockKey 互斥锁在Redis中的Key
     * @return true, 获取成功; false, 获取失败
     */
    private boolean tryLock(String lockKey) {
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(lockKey, "locked", LOCK_SHOP_TTL, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    /**
     * 解锁互斥锁
     *
     * @param lockKey 互斥锁在Redis中的Key
     */
    private void unlock(String lockKey) {
        stringRedisTemplate.delete(lockKey);
    }

    /**
     * 更新店铺信息
     * (先更新数据库, 再删除缓存)
     *
     * @param shop
     * @return
     */
    @Override
    @Transactional
    public Result updateShop(Shop shop) {
        Long shopId = shop.getId();
        if (shopId == null) {
            return Result.fail("店铺id不可为空");
        }
        // 1. 更新数据库中数据
        updateById(shop);

        // 2. 删除缓存
        stringRedisTemplate.delete(CACHE_SHOP_KEY + shopId);

        return Result.ok();
    }
}
