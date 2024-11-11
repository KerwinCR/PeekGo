package com.peekgo.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.peekgo.entity.SeckillVoucher;
import com.peekgo.mapper.SeckillVoucherMapper;
import com.peekgo.service.ISeckillVoucherService;
import com.peekgo.utils.RedisIdWorker;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;

/**
 * <p>
 * 秒杀优惠券表，与优惠券是一对一关系 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2022-01-04
 */
@Service
public class SeckillVoucherServiceImpl extends ServiceImpl<SeckillVoucherMapper, SeckillVoucher> implements ISeckillVoucherService {
    @Resource
    private ISeckillVoucherService seckillVoucherService;
    @Resource
    private RedisIdWorker redisIdWorker;


}
