package com.peekgo.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.RandomUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.peekgo.dto.LoginFormDTO;
import com.peekgo.dto.Result;
import com.peekgo.dto.UserDTO;
import com.peekgo.entity.User;
import com.peekgo.mapper.UserMapper;
import com.peekgo.service.IUserService;
import com.peekgo.utils.RegexUtils;
import com.peekgo.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.servlet.http.HttpSession;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static com.peekgo.utils.RedisConstants.*;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
@Slf4j
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {
    @Autowired
    private IUserService userService;
    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result sedCode(String phone, HttpSession session) {
        //如果手机格式不正确
        if (!RegexUtils.isPhoneInvalid(phone)){
            return Result.fail("手机格式不正确");
        }
        //如果手机格式正确,生成验证码
        String code = RandomUtil.randomNumbers(6);


        //将验证码存进session
        //session.setAttribute("code",code);

        //将验证码存进Redis,将验证码设置为2分钟有效期
        stringRedisTemplate.opsForValue().set(LOGIN_CODE_KEY+phone,code,LOGIN_CODE_TTL, TimeUnit.MINUTES);

        //模拟发送验证码
        log.info(phone+"的验证码为:"+code);
        return Result.ok("验证码发送成功");
    }

    /**
     * 签到函数
     */
    @Override
    public Result sign() {
        Long userId = UserHolder.getUser().getId();
        LocalDateTime now = LocalDateTime.now();
        String keySuffix = now.format(DateTimeFormatter.ofPattern("yyyyMM"));
        String key = "sign:"+userId+keySuffix;
        int dayOfMonth = now.getDayOfMonth();
        stringRedisTemplate.opsForValue().setBit(key,dayOfMonth-1,true);
        return Result.ok("签到成功");
    }

    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        //拿到手机号，验证是否正确
        String phone = loginForm.getPhone();
        if (!RegexUtils.isPhoneInvalid(phone)){
            return Result.fail("手机格式不正确");
        }
        //拿到密码和验证码
        String receivePassword=loginForm.getPassword();
        String receiveCode=loginForm.getCode();
        //如果密码和验证码都为空
        if (StrUtil.isEmpty(receiveCode)&&StrUtil.isEmpty(receivePassword)){
            return Result.fail("请输入正确的密码或验证码");
        }
        //验证码为空转向密码登录
        if (StrUtil.isEmpty(receiveCode)){
            return loginByPasswordWithRedis(phone,receivePassword);
        }
        //密码为空转向验证码登录
        if (StrUtil.isEmpty(loginForm.getPassword())) {
            String trueCode = (String) session.getAttribute("code");
            return loginByCodeWithRedis(phone,receiveCode);
        }
        return Result.fail("未知异常");
    }


/**
 * 分割线，下面是函数
 */



     private User getUserByPhone(String phone){
        LambdaQueryWrapper<User> lqw = new LambdaQueryWrapper<>();
        lqw.eq(User::getPhone, phone);
        User user = userService.getOne(lqw);
        return user;
    }

    private void createNewUser(String phone){
        User newUser = new User();
        newUser.setPhone(phone);
        newUser.setPassword("123456");
        userService.save(newUser);
    }




    private Result loginByPasswordWithRedis(String phone,String receivePassword){
        User user = getUserByPhone(phone);
        if (user==null){
            return Result.fail("新用户请使用请使用验证码登录");
        }
        String truePassword = user.getPassword();
        if (!truePassword.equals(receivePassword)){
            return Result.fail("密码错误");
        }
        //登录成功将其存进Redis
        String uuid = UUID.randomUUID().toString();
        saveInRedis(user,stringRedisTemplate,uuid);
        saveInThreadLocal(user);
        return Result.ok(uuid);
    }
    private Result loginByCodeWithRedis(String phone,String receiveCode){
        String trueCode = stringRedisTemplate.opsForValue().get(LOGIN_CODE_KEY + phone);
        if (!receiveCode.equals(trueCode)){
            return Result.fail("验证码错误");
        }
        User user = getUserByPhone(phone);
        //如果为null则新创用户
        if (user==null){
            createNewUser(phone);
            user=getUserByPhone(phone);
        }
        //登录成功将其存进Session
        String uuid = UUID.randomUUID().toString();
        saveInRedis(user,stringRedisTemplate,uuid);
        saveInThreadLocal(user);
        return Result.ok(uuid);
    }
    private void saveInRedis(User user,StringRedisTemplate stringRedisTemplate,String uuid){
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        String userDTOJson = JSONUtil.toJsonStr(userDTO);

        //随机生成token作为key
        String redisKey=LOGIN_USER_KEY+uuid;
        //将用户信息存进Redis
        stringRedisTemplate.opsForValue().set(redisKey,userDTOJson,LOGIN_USER_TTL,TimeUnit.MINUTES);
    }


    private void saveInSession(User user,HttpSession session) {
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        session.setAttribute("user", userDTO);
    }

    private void saveInThreadLocal(User user) {
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        UserHolder.saveUser(userDTO);
    }
}



/**
    下面这三个函数配合session使用
    启用时要将验证码存入方式改成session
    private Result loginByPasswordWithSession(String phone,String password,HttpSession session){
        User user = getUserByPhone(phone);
        if (user==null){
            return Result.fail("新用户请使用请使用验证码登录");
        }
        String truePassword = user.getPassword();
        String receivePassword = password;
        if (!truePassword.equals(receivePassword)){
            return Result.fail("密码错误");
        }
        //登录成功将其存进Session
        saveInSession(user,session);
        return Result.ok();
    }
    private Result loginByCodeWithSession(String phone,String trueCode,String receiveCode,HttpSession session){
        if (!receiveCode.equals(trueCode)){
            return Result.fail("验证码错误");
        }
        User user = getUserByPhone(phone);
        //如果为null则新创用户
        if (user==null){
            createNewUser(phone);
            user=getUserByPhone(phone);
        }
        //登录成功将其存进Session
        saveInSession(user,session);
        return Result.ok();
    }
    private void saveInSession(User user,HttpSession session){
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        session.setAttribute("user",userDTO);
    }
**/

