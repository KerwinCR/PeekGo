package com.peekgo.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.peekgo.dto.Result;
import com.peekgo.entity.Shop;
import org.springframework.transaction.annotation.Transactional;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface IShopService extends IService<Shop> {


    Result queryShopById(Long id);

    @Transactional
    Result updateByIdWithRedis(Shop shop);
}
