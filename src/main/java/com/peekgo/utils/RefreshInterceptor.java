package com.peekgo.utils;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.peekgo.dto.UserDTO;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import java.util.concurrent.TimeUnit;

import static com.peekgo.utils.RedisConstants.LOGIN_USER_KEY;
import static com.peekgo.utils.RedisConstants.LOGIN_USER_TTL;

@Component
public class RefreshInterceptor implements HandlerInterceptor {
    @Resource
    private StringRedisTemplate stringRedisTemplate;


    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        String uuid = request.getHeader("authorization");
        //从Redis中取用户
        String userDtoJson = stringRedisTemplate.opsForValue().get(LOGIN_USER_KEY + uuid);
        // 判断用户是否存在
        if (StrUtil.isEmpty(userDtoJson)){
            //不存在,交给下一个拦截器
            return true;
        }
        //5. 存在，刷新有效时间
        UserDTO userDTO = JSONUtil.toBean(userDtoJson, UserDTO.class);
        stringRedisTemplate.expire(LOGIN_USER_KEY + uuid,LOGIN_USER_TTL, TimeUnit.MINUTES);
        UserHolder.saveUser(userDTO);
        //6. 放行
        return true;
    }
}
