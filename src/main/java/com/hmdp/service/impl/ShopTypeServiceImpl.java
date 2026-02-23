package com.hmdp.service.impl;

import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_TYPE_KEY;
import static com.hmdp.utils.RedisConstants.CACHE_SHOP_TYPE_TTL;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 查询商店类型列表, 利用了Redis缓存
     */
    @Override
    public Result queryTypeList() {
        // 1. 从Redis中查询列表
        String cacheTypeKey = CACHE_SHOP_TYPE_KEY;
        String cacheTypeListString = stringRedisTemplate.opsForValue().get(cacheTypeKey);

        // 2. 若查询到, 将其转化为List, 返回
        if (cacheTypeListString != null) {
            List<ShopType> cacheTypeList = JSONUtil.toList(cacheTypeListString, ShopType.class);
            return Result.ok(cacheTypeList);
        }

        // 3. 若未查询到, 去MySQL中查询列表
        List<ShopType> typeList = query().orderByAsc("sort").list();

        // 4. 若仍未查询到, 则返回错误
        if (typeList.isEmpty()) {
            return Result.fail("商店类型不存在");
        }

        // 5. 若查询到, 将列表写入Redis, 再返回
        String typeJsonString = JSONUtil.toJsonStr(typeList);
        stringRedisTemplate.opsForValue().set(cacheTypeKey, typeJsonString, CACHE_SHOP_TYPE_TTL, TimeUnit.MINUTES);
        return Result.ok(typeList);
    }
}
