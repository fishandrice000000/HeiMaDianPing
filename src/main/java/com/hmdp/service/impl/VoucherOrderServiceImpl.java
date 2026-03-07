package com.hmdp.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;

import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder>
        implements IVoucherOrderService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private RedisIdWorker redisIdWorker;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private RedissonClient redissonClient;

    /**
     * 实现秒杀下单功能
     * 通过乐观锁CAS防止超卖
     * 通过synchronized悲观锁实现一人一单功能
     *
     * @param voucherId 优惠券Id
     * @return 若成功, 返回订单id; 若失败, 返回错误信息
     */
    @Override
    public Result seckillVoucher(Long voucherId) {
        // 0. 获取用户ID
        Long userId = UserHolder.getUser().getId();
        // 1. 查询优惠券
        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);

        // 2. 判断秒杀是否开始
        if (voucher.getBeginTime().isAfter(LocalDateTime.now())) {
            return Result.fail("秒杀未开始");
        }

        // 3. 判断秒杀是否结束
        if (voucher.getEndTime().isBefore(LocalDateTime.now())) {
            return Result.fail("秒杀已结束");
        }

        // 4. 判断库存是否充足
        if (voucher.getStock() < 1) {
            return Result.fail("库存不足");
        }

        // // 加入悲观锁, 确保一人一单
        // // 以userId为锁对象实现细粒度上锁提高运行效率
        // // 这里
        // synchronized (userId.toString().intern()) {
        // // 获取代理对象, 使得注解@Transactional
        // // 不会因为程序越过SpringBoot创建的代理对象, 去直接调用原生对象本身的方法而失效
        // IVoucherOrderService proxy = (IVoucherOrderService)
        // AopContext.currentProxy();
        // return proxy.createVoucherOrder(voucherId);
        // }

        // 使用分布式锁实现一人一单
        // 使用Redisson提供的锁
        RLock lock = redissonClient.getLock("order:" + userId);
        // 获取锁
        boolean isLock = lock.tryLock();
        // 获取锁失败, 返回错误信息
        if (!isLock) {
            return Result.fail("禁止重复下单");
        }
        // 获取锁成功, 创建订单
        try {
            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
            return proxy.createVoucherOrder(voucherId);
        } finally {
            // 兜底避免死锁
            lock.unlock();
        }

    }

    /**
     * 订单创建, 确保一人一单, 防止超卖
     *
     * @param voucherId 优惠券Id
     * @return 若成功, 返回订单id; 若失败, 返回错误信息
     */
    @Transactional
    @Override
    public Result createVoucherOrder(Long voucherId) {
        Long userId = UserHolder.getUser().getId();

        // 5. 判断是否重复下单, 确保一人一单
        int count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
        if (count > 0) {
            return Result.fail("不能重复购买");
        }

        // 6. 扣减库存
        boolean success = seckillVoucherService.update().setSql("stock = stock - 1 ").eq("voucher_id", voucherId).
        // CAS实现乐观锁, 因为数据库有行锁, 在进行增删改查时是原子性的
        // 所以使用"库存大于0"的判断, 而不是"库存等于查询时的库存", 是有效的
                gt("stock", 0).update();
        if (!success) {
            return Result.fail("库存不足");
        }

        // 7. 创建订单
        VoucherOrder voucherOrder = new VoucherOrder();
        // 7.1 设置订单ID
        Long orderId = redisIdWorker.nextId("order");
        voucherOrder.setId(orderId);
        // 7.2 设置用户ID
        voucherOrder.setUserId(userId);
        // 7.3 设置优惠券ID
        voucherOrder.setVoucherId(voucherId);
        // 7.4 将订单写入数据库
        save(voucherOrder);

        // 8. 返回订单id
        return Result.ok(orderId);
    }
}
