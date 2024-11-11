package com.peekgo.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.peekgo.dto.Result;
import com.peekgo.entity.Voucher;
import com.peekgo.mapper.VoucherMapper;
import com.peekgo.entity.SeckillVoucher;
import com.peekgo.service.ISeckillVoucherService;
import com.peekgo.service.IVoucherService;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.util.List;

import static com.peekgo.utils.RedisConstants.SECKILL_STOCK_KEY;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class VoucherServiceImpl extends ServiceImpl<VoucherMapper, Voucher> implements IVoucherService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;
    @Resource
    private IVoucherService voucherService;
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result queryVoucherOfShop(Long shopId) {
        LambdaQueryWrapper<Voucher> lqw = new LambdaQueryWrapper<>();
        lqw.eq(Voucher::getShopId,shopId);

        List<Voucher> vouchers = voucherService.list(lqw);
        for (Voucher voucher : vouchers) {
            LambdaQueryWrapper<SeckillVoucher> seclqw = new LambdaQueryWrapper<>();
            seclqw.eq(SeckillVoucher::getVoucherId,voucher.getId());
            SeckillVoucher one = seckillVoucherService.getOne(seclqw);
            if (one!=null){
                voucher.setBeginTime(one.getBeginTime());
                voucher.setEndTime(one.getEndTime());
                voucher.setStock(one.getStock());
            }
        }
        //返回结果
        return Result.ok(vouchers);
    }

    @Override
    @Transactional
    public void addSeckillVoucher(Voucher voucher) {
        // 保存优惠券
        save(voucher);
        // 保存秒杀信息到mysql
        SeckillVoucher seckillVoucher = new SeckillVoucher();
        seckillVoucher.setVoucherId(voucher.getId());
        seckillVoucher.setStock(voucher.getStock());
        seckillVoucher.setBeginTime(voucher.getBeginTime());
        seckillVoucher.setEndTime(voucher.getEndTime());
        seckillVoucherService.save(seckillVoucher);
        //保存秒杀信息到Redis
        stringRedisTemplate.opsForValue().set(SECKILL_STOCK_KEY+voucher.getId(),voucher.getStock().toString());
    }
}
