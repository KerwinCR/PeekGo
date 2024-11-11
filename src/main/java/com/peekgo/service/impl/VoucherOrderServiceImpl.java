package com.peekgo.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.peekgo.dto.Result;
import com.peekgo.entity.SeckillVoucher;
import com.peekgo.entity.VoucherOrder;
import com.peekgo.mapper.VoucherOrderMapper;
import com.peekgo.service.ISeckillVoucherService;
import com.peekgo.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.peekgo.utils.ILock;
import com.peekgo.utils.RedisIdWorker;
import com.peekgo.utils.SimpleRedisLock;
import com.peekgo.utils.UserHolder;
import org.redisson.api.RedissonClient;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.aop.framework.AopContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

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
    private RedissonClient redissonClient;
    @Autowired
    private StringRedisTemplate stringRedisTemplate;
    //初始化lua脚本
    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;
    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }
    //阻塞队列
    private BlockingQueue<VoucherOrder> orderTasks=new ArrayBlockingQueue<>(1024*1024);
    //创建线程池
    private static final ExecutorService SCEKILL_ORDER_EXECUTOR=Executors.newSingleThreadExecutor();
    //创建具体任务
//    private class VoucherOrderHandler implements Runnable{
//        @Override
//        public void run() {
//            while (true){
//                try {
//                    VoucherOrder voucherOrder = orderTasks.take();
//                    proxy.createVoucherOrderAsynchronous(voucherOrder);
//                } catch (InterruptedException e) {
//                    e.printStackTrace();
//                }
//            }
//        }
//    }
    //获取代理对象，使事务生效
    private IVoucherOrderService proxy;
    //获取RabbitMQ对象
    @Resource
    private RabbitTemplate rabbitTemplate;



    //类加载后就会执行这一个方法
//    @PostConstruct
//    private void init(){
//        SCEKILL_ORDER_EXECUTOR.execute(new VoucherOrderHandler());
//    }
    /**
     * 一、执行这个函数要加锁，具体锁的策略
     * 1.对于非分布式系统，可以用java内置的synchronized关键字
     * 2、对于分布式系统，可以用redission里面的锁
     * 二、执行这个函数，某些操作需要保证其原子性，可以用lua脚本来解决
     * 1.判断条件执行完对应判断结果的函数，这两部分操作要封装到含有原子性功能的工具上
     *
     * @param voucherId
     * @return
     */
    @Override
    public Result seckillVoucher(Long voucherId) {
//        下列方法基于锁的实现
//        IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
//        return proxy.createVoucherOrderWithJava(voucherId);
        Long userId = UserHolder.getUser().getId();
        Long result = stringRedisTemplate.execute(SECKILL_SCRIPT, Collections.emptyList(), voucherId.toString(), userId.toString());
        if (result!=0){
            return Result.fail(result==1? "库存不足":"不能重复下单");
        }
        long orderId = redisIdWorker.nextId("order");
        //放入阻塞队列，获取代理对象使事务生效
        VoucherOrder voucherOrder = new VoucherOrder(orderId,userId,voucherId);
        proxy = (IVoucherOrderService) AopContext.currentProxy();
//        orderTasks.add(voucherOrder);
        sendToOrderQueue(voucherOrder);
        return Result.ok(orderId);
    }

    /**
     * 同步下单时使用，判断用户是否已经下单
     * @param voucherId
     * @return
     */
    public boolean IsHaveOrdered(Long voucherId){
        Long userId = UserHolder.getUser().getId();
        LambdaQueryWrapper<VoucherOrder> lqw = new LambdaQueryWrapper<>();
        lqw.eq(VoucherOrder::getUserId,userId).eq(VoucherOrder::getVoucherId,voucherId);
        VoucherOrder one = getOne(lqw);
        if (one!=null){
            return true;
        }
        return false;
    }

    /**
     * 同步下单，在写入Mysql时需要用到锁
     * @param voucherId
     * @return
     */
    @Transactional
    public Result createVoucherOrderSynchronous(Long voucherId){
        //先查出优惠券的信息，然后判断优惠券各种条件看是否符合抢购条件
        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
        if (voucher.getBeginTime().isAfter(LocalDateTime.now())||voucher.getEndTime().isBefore(LocalDateTime.now())||voucher.getStock()<1){
            return Result.fail("当前不符合抢购条件");
        }
        Long userId = UserHolder.getUser().getId();
        //用锁标识+业务名+具体业务+用户具体定位一个范围最合适的锁
        //这里的锁有多种实现方式，可以自行更换锁的类型
        //RLock lock = redissonClient.getLock("shop:voucherId:" + voucherId);
        ILock lock = new SimpleRedisLock("shop:voucherId:" + voucherId, stringRedisTemplate);
        try {
            boolean isLock = lock.tryLock(1200);
            if(!isLock){
                return Result.fail("请稍后再试");
            }
            boolean flag = IsHaveOrdered(voucherId);
            if (flag){
                return Result.fail("每个ID限购一张");
            }
            //条件通过，开始抢购
            LambdaUpdateWrapper<SeckillVoucher> luw = new LambdaUpdateWrapper<>();
            //使用乐观锁解决并发冲突
            luw.eq(SeckillVoucher::getVoucherId,voucherId).gt(SeckillVoucher::getStock,0)
                    .set(SeckillVoucher::getStock,voucher.getStock()-1);
            boolean success = seckillVoucherService.update(luw);
            if (!success){
                //为false扣减失败
                return Result.fail("当前不符合抢购条件");
            }
            //创建订单
            VoucherOrder voucherOrder = new VoucherOrder();
            //将三个基本信息填入订单
            long orderId = redisIdWorker.nextId("order");
            voucherOrder.setId(orderId);
            voucherOrder.setUserId(userId);
            voucherOrder.setVoucherId(voucherId);
            save(voucherOrder);
            return Result.ok(orderId);
        } finally {
            lock.unlock();
        }
    }

    /**
     * 异步下单，锁的判断已经由Redis和Lua托管，此处不再用到锁，此处简单的将库存扣减以及将订单存进数据库
     * @param voucherOrder
     */
    @Transactional
    public void createVoucherOrderAsynchronous(VoucherOrder voucherOrder){
        Long userId = voucherOrder.getUserId();
        Long voucherId = voucherOrder.getVoucherId();
        //扣减库存
        LambdaUpdateWrapper<SeckillVoucher> luw = new LambdaUpdateWrapper<>();
        luw.eq(SeckillVoucher::getVoucherId,voucherId).setSql("stock=stock-1");
        seckillVoucherService.update(luw);
        //新增订单
        save(voucherOrder);
    }

    public void sendToOrderQueue(VoucherOrder voucherOrder){
        String queueName="order.queue";
        rabbitTemplate.convertAndSend(queueName,voucherOrder);
    }

    @RabbitListener(queues = "order.queue")
    public void receiveOrder(VoucherOrder voucherOrder){
        SCEKILL_ORDER_EXECUTOR.execute(()->{proxy.createVoucherOrderAsynchronous(voucherOrder);});
    }
}
