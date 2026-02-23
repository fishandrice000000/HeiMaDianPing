package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
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

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_KEY;
import static com.hmdp.utils.RedisConstants.CACHE_SHOP_TTL;

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

    /**
     * 根据id查询商店信息, 利用Redis缓存
     *
     * @param id 商店id
     * @return 若不存在返回404, 若存在返回商店信息
     */
    @Override
    public Result queryShopById(Long id) {
        // 1.试图去Redis缓存中获取信息
        String shopKey = CACHE_SHOP_KEY + id;
        Map<Object, Object> cacheShopMap = stringRedisTemplate.opsForHash().entries(shopKey);

        // 2. 若已获取到, 则返回
        if (!cacheShopMap.isEmpty()) {
            Shop cacheShop = BeanUtil.fillBeanWithMap(cacheShopMap, new Shop(), false);
            return Result.ok(cacheShop);
        }

        // 3. 若未获取到, 则去MySQL中获取
        Shop shop = shopMapper.selectById(id);

        // 4. 若仍未获取到, 则用户不存在.
        if (shop == null) {
            return Result.fail("商铺不存在");
        }

        // 5. 若获取到, 将获取到的信息写入Redis
        Map<String, Object> shopMap = BeanUtil.beanToMap(shop, new HashMap<>(),
                CopyOptions.create().
                        setIgnoreNullValue(true).
                        setFieldValueEditor((fieldName, fieldValue) -> fieldValue == null ? null : fieldValue.toString()));
        stringRedisTemplate.opsForHash().putAll(shopKey, shopMap);
        stringRedisTemplate.expire(shopKey, CACHE_SHOP_TTL, TimeUnit.MINUTES);
        // 6. 结束
        return Result.ok(shop);
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
