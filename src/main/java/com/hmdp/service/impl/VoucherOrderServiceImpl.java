package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.Voucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IVoucherService;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import com.hmdp.utils.lock.impl.SimpleRedisLock;
import lombok.Synchronized;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import java.time.LocalDateTime;
import java.util.concurrent.*;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Autowired
    private ISeckillVoucherService seckillVoucherService;
    @Autowired
    private RedisIdWorker redisIdWorker;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private RedissonClient redissonClient;

    public static final DefaultRedisScript<Long> SECKILL_SCRIPT;

    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    private BlockingQueue<VoucherOrder> blockingQueue = new ArrayBlockingQueue<>(1024*1024);
    private IVoucherOrderService proxy;

    private static final ExecutorService executor = Executors.newSingleThreadExecutor();

    private class VoucherOrderHandler implements Runnable {
        @Override
        public void run(){
            while (true) {
                try {
                    //1.获取队列中的订单信息
                    VoucherOrder voucherOrder = blockingQueue.take();
                    //2.创建订单
                    handleVoucherOrder(voucherOrder);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }
    }

    @PostConstruct
    private void init() {
        executor.submit(new VoucherOrderHandler());
    }
    private void handleVoucherOrder(VoucherOrder voucherOrder) {
        //1.获取用户
        Long userId = voucherOrder.getUserId();
        //2.创建锁对象
        RLock rLock = redissonClient.getLock("lock:order:" + userId);
        //3.获取锁
        boolean isLock = rLock.tryLock();
        //4.判断是否获取锁成功
        if (!isLock) {
            //获取锁失败，返回错误或者重试
            System.out.println("不允许重复下单");
            return;
        }
        try {
            //获取锁成功，创建订单
            proxy.createVoucherOrder2(voucherOrder);
        } finally {
            //释放锁
            rLock.unlock();
        }
    }

    //秒杀优化， 使用异步方式下单
    @Override
    public Result seckillVoucher(Long voucherId) {
        Long UserId = UserHolder.getUser().getId();
        //1.执行lua脚本
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                java.util.Collections.emptyList(),
                voucherId.toString(), UserId.toString()
        );
        //2.判断结果是否为0
        int r = result.intValue();
        if (r != 0) {
            //2.1 不为0，代表没有购买资格
            return Result.fail(r == 1 ? "库存不足" : "不能重复下单");
        }
        Long orderId = redisIdWorker.nextId("order");
        //2.2 为0，有购买资格，把下单信息保存到阻塞队列
        VoucherOrder voucherOrder = new VoucherOrder();
        voucherOrder.setId(orderId);
        voucherOrder.setUserId(UserId);
        voucherOrder.setVoucherId(voucherId);
        blockingQueue.add(voucherOrder);
        //添加proxy对象
        proxy = (IVoucherOrderService) AopContext.currentProxy();
        return Result.ok(orderId);

    }

/*
    @Override
    public Result seckillVoucher(Long voucherId) {
        // 1.查询优惠券
        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
        // 2.判断秒杀是否开始
        if (voucher.getBeginTime().isAfter(LocalDateTime.now())) {
            // 尚未开始
            return Result.fail("秒杀尚未开始！");
        }
        // 3.判断秒杀是否已经结束
        if (voucher.getEndTime().isBefore(LocalDateTime.now())) {
            // 尚未开始
            return Result.fail("秒杀已经结束！");
        }
        // 4.判断库存是否充足
        if (voucher.getStock() < 1) {
            // 库存不足
            return Result.fail("库存不足！");
        }

        // 创建订单（使用分布式锁）
        Long userId = UserHolder.getUser().getId();
//        SimpleRedisLock lock = new SimpleRedisLock(stringRedisTemplate, "order:" + userId);
//        boolean isLock = lock.tryLock(1200);
        //使用redisson分布式锁
        RLock rLock = redissonClient.getLock("lock:order:" + userId);
        boolean isLock = rLock.tryLock();
        if (!isLock) {
            // 索取锁失败，重试或者直接抛异常（这个业务是一人一单，所以直接返回失败信息）
            return Result.fail("一人只能下一单");
        }
        try {
            // 索取锁成功，创建代理对象，使用代理对象调用第三方事务方法， 防止事务失效
            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
            return proxy.createVoucherOrder(voucherId);
        } finally {
            rLock.unlock();
        }
*/



//        //只能解决单体一人一单问题
//        Long userID = UserHolder.getUser().getId();
//        synchronized (userID.toString().intern()) {
//            IVoucherOrderService o = (IVoucherOrderService) AopContext.currentProxy();
//            return o.createVoucherOrder(voucherId);
//        }




    //这里不能在整个方法加锁，因为针对不同的用户才加锁
    //也不能在内部加锁， 因为我们必须保证事务提交之后 才释放锁
    @Transactional
    public Result createVoucherOrder(Long voucherId) {
        // 实现一人一单功能： 一人一单逻辑
        Long userId = UserHolder.getUser().getId();
        int count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
        if (count > 0) {
            return Result.fail("用户已经购买过一次！");
        }

        //5，扣减库存
        //乐观锁解决超卖问题
        boolean success = seckillVoucherService.update()
                .setSql("stock= stock -1") //set stock = stock -1
                .eq("voucher_id", voucherId).gt("stock",0).update();
        if (!success) {
            //扣减库存
            return Result.fail("库存不足！");
        }
        //6.创建订单
        VoucherOrder voucherOrder = new VoucherOrder();
        // 6.1.订单id
        long orderId = redisIdWorker.nextId("order");
        voucherOrder.setId(orderId);
        // 6.2.用户id
        userId = UserHolder.getUser().getId();
        voucherOrder.setUserId(userId);
        // 6.3.代金券id
        voucherOrder.setVoucherId(voucherId);
        save(voucherOrder);

        return Result.ok(orderId);
    }

    @Transactional
    public void createVoucherOrder2(VoucherOrder voucherOrder) {
        Long userId = voucherOrder.getUserId();
        // 5.1.查询订单
        int count = query().eq("user_id", userId).eq("voucher_id", voucherOrder.getVoucherId()).count();
        // 5.2.判断是否存在
        if (count > 0) {
            // 用户已经购买过了
            log.error("用户已经购买过了");
            return ;
        }

        // 6.扣减库存
        boolean success = seckillVoucherService.update()
                .setSql("stock = stock - 1") // set stock = stock - 1
                .eq("voucher_id", voucherOrder.getVoucherId()).gt("stock", 0) // where id = ? and stock > 0
                .update();
        if (!success) {
            // 扣减失败
            log.error("库存不足");
            return ;
        }
        save(voucherOrder);
    }
}
