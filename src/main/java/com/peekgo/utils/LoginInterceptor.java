package com.peekgo.utils;

import com.peekgo.dto.UserDTO;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

@Component
public class LoginInterceptor implements HandlerInterceptor {
    @Resource
    private StringRedisTemplate stringRedisTemplate;


    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        //从Session中取数据
        //return getFromSession(request,response);

        //从Redis中取数据
        return getFromRedis(request,response);
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) throws Exception {
        //移除用户
        UserHolder.removeUser();
    }

    public boolean getFromRedis(HttpServletRequest request, HttpServletResponse response){
        //直接从LocalThread中获取数据
        UserDTO userDTO = UserHolder.getUser();
        //获取不到截取，获取得到放行
        if (userDTO==null){
            response.setStatus(401);
            return false;
        }
        return true;
    }


    public boolean getFromSession(HttpServletRequest request, HttpServletResponse response){
        HttpSession session = request.getSession();
        //2.获取session中的用户
        Object user = session.getAttribute("user");
        //3. 判断用户是否存在
        if (user == null){
            //4. 不存在，拦截
            response.setStatus(401);
            return false;
        }

        //5. 存在 保存用户信息到ThreadLocal
        UserHolder.saveUser((UserDTO) user);
        //6. 放行
        return true;
    }
}
