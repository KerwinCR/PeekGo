package com.peekgo.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.peekgo.dto.Result;
import com.peekgo.dto.UserDTO;
import com.peekgo.entity.Follow;
import com.peekgo.mapper.FollowMapper;
import com.peekgo.service.IFollowService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.peekgo.service.IUserService;
import com.peekgo.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class FollowServiceImpl extends ServiceImpl<FollowMapper, Follow> implements IFollowService {
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private IUserService userService;

    /**
     * 一个用户维护一个关注列表
     * @param followUserId
     * @param isFollow
     * @return
     */
    @Override
    public Result followSomebody(Long followUserId, Boolean isFollow) {
        Long userId = UserHolder.getUser().getId();
        String key="follows:"+userId;
        if (isFollow){
            Follow follow = new Follow();
            follow.setUserId(userId);
            follow.setFollowUserId(followUserId);
            save(follow);

            stringRedisTemplate.opsForSet().add(key,followUserId.toString());
        }
        else {
            remove(new LambdaQueryWrapper<Follow>().eq(Follow::getUserId,userId).eq(Follow::getFollowUserId,followUserId));
            stringRedisTemplate.opsForSet().remove(key,followUserId.toString());
        }
        return Result.ok();
    }

    @Override
    public Result isFollow(Long followUserId) {
        Long userId = UserHolder.getUser().getId();
        Follow follow = getOne(new LambdaQueryWrapper<Follow>().eq(Follow::getUserId, userId).eq(Follow::getFollowUserId, followUserId));
        return Result.ok(follow!=null);
    }

    @Override
    public Result followCommon(Long id) {
        Long userId = UserHolder.getUser().getId();
        String key1="follows:"+id;
        String key2="follows:"+userId;
        Set<String> intersect = stringRedisTemplate.opsForSet().intersect(key1, key2);
        if (intersect==null||intersect.isEmpty()){
            return Result.ok();
        }
        List<Long> collect = intersect.stream().map(Long::valueOf).collect(Collectors.toList());
        Stream<UserDTO> userDTOStream = userService.listByIds(collect).stream().map(user -> BeanUtil.copyProperties(user, UserDTO.class));
        return Result.ok(userDTOStream);
    }
}
