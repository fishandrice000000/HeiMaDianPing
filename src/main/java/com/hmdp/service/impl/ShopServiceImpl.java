package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.util.BooleanUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.util.HashMap;
import java.util.Map;
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
    private boolean isLocked;

    /**
     * 根据id查询商店信息
     *
     * @param id 商店id
     * @return 若不存在返回404, 若存在返回商店信息
     */
    @Override
    public Result queryShopById(Long id) {
        // 解决了缓存穿透
        Shop shop = queryWithPassThrough(id);

        // 解决了缓存穿透 + 缓存击穿
        // Shop shop = queryWithMutex(id);

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
        Shop shop;
        Map<Object, Object> cacheShopMap = stringRedisTemplate.opsForHash().entries(shopKey);

        // 2. 若已获取到且非空值, 则返回信息. 若获取到空值, 则返回错误
        if (!cacheShopMap.isEmpty()) {
            if (cacheShopMap.containsKey(NULL_HASH_FIELD)) {
                return null;
            }
            return BeanUtil.fillBeanWithMap(cacheShopMap, new Shop(), false);
        }

        // 3. 若未获取到, 则去MySQL中获取
        shop = shopMapper.selectById(id);

        // 4. 若仍未获取到, 则用户不存在. 并将空值写入Redis.
        if (shop == null) {
            // 将空值写入Redis
            Map<String, String> nullMap = new HashMap<>();
            nullMap.put(NULL_HASH_FIELD, "");
            stringRedisTemplate.opsForHash().putAll(shopKey, nullMap);
            stringRedisTemplate.expire(shopKey, CACHE_NULL_TTL, TimeUnit.MINUTES);
        } else {
            // 5. 若获取到, 将获取到的信息写入Redis
            Map<String, Object> shopMap = BeanUtil.beanToMap(shop, new HashMap<>(),
                    CopyOptions.create().
                            setIgnoreNullValue(true).
                            setFieldValueEditor((fieldName, fieldValue) -> fieldValue == null ? null : fieldValue.toString()));
            stringRedisTemplate.opsForHash().putAll(shopKey, shopMap);
            stringRedisTemplate.expire(shopKey, CACHE_SHOP_TTL, TimeUnit.MINUTES);
        }
        // 6. 结束
        return shop;
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
        // TODO
        return null;
    }

    /**
     * 尝试获取互斥锁
     *
     * @param lockKey 互斥锁在Redis中的Key
     * @return true, 获取成功; false, 获取失败
     */
    private boolean tryLock(String lockKey) {
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(lockKey, "locked", 10, TimeUnit.SECONDS);
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
