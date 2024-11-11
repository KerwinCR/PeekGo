package com.peekgo.service;

import com.peekgo.dto.Result;
import com.peekgo.entity.Follow;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface IFollowService extends IService<Follow> {

    Result followSomebody(Long followUserId, Boolean isFollow);

    Result isFollow(Long followUserId);

    Result followCommon(Long id);
}
