package com.peekgo.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.peekgo.dto.Result;
import com.peekgo.entity.Shop;
import com.peekgo.mapper.ShopMapper;
import com.peekgo.service.IShopService;
import com.peekgo.utils.CacheClient2;
import com.peekgo.utils.RedisData;
import java.util.regex.Pattern;
import java.util.regex.Matcher;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.peekgo.utils.RedisConstants.*;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private IShopService shopService;
    //创建一个固定线程池
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);
    @Resource
    private CacheClient2 cacheClient2;

    /**
     * 缓存穿透：客户端大量请求不存在的数据，使所有请求都落到数据库中，增大了数据库的压力，写入空对象到Redis，布隆过滤器
     * 缓存雪崩：Redis的大量Key在短时间内大量失效
     * 缓存击穿：Redis的热点数据失效，互斥锁，逻辑过期
     * 核心思想就是同样的请求只允许访问数据库一次，从第二次开始都要命中Redis
     * 都是用来解决大量请求同时未命中Redis而进入数据库的问题，不同的策略应付不用类型的请求
     */

    /**
     * 查询店铺
     * @param id
     * @return
     */
    @Override
    public Result queryShopById(Long id) {
        Shop date = cacheClient2.queryByIdSolvePenetrateWithWriteMutex(CACHE_SHOP_KEY+id, LOCK_SHOP_KEY+id, id,(primeKey)->{
            Shop shop = shopService.getById(primeKey);
            return shop;
            },30L,TimeUnit.MINUTES,Shop.class);
        if (date==null){
            return Result.fail("店铺不存在");
        }
        return Result.ok(date);
    }

    /**
     * 更新店铺
     * @param shop
     * @return
     */
    @Override
    public Result updateByIdWithRedis(Shop shop) {
        Long id = shop.getId();
        if (id==null){
            return Result.fail("ID不能为空");
        }
        //先修改数据库，再删除缓存，可以减少并发冲突的概率
        this.updateById(shop);
        stringRedisTemplate.delete(CACHE_SHOP_KEY+id);
        return Result.ok("修改成功");
    }


    public Shop queryShopByIdBasic(Long id) {
        String shopJson = stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY + id);
        //如果redis未命中查数据库
        if (StrUtil.isEmpty(shopJson)){
            Shop shop = shopService.getById(id);
            //数据库也不存在放回错误
            if (shop==null){
                return null;
            }
            //数据库存在则将数据写进redis
            shopJson = JSONUtil.toJsonStr(shop);
            stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY+id,shopJson,CACHE_SHOP_TTL,TimeUnit.MINUTES);
            //重新查一遍，就可以从redis获得数据
            return queryShopByIdBasic(id);
        }
        //如果命中了redis,重置时间
        stringRedisTemplate.expire(CACHE_SHOP_KEY+id,CACHE_SHOP_TTL,TimeUnit.MINUTES);
        Shop shop = JSONUtil.toBean(shopJson, Shop.class);
        return shop;
    }

    /**
     * 用返回空字符串解决缓存击穿
     * @param id
     * @return
     */
    public Shop queryShopByIdSolveBreakWithWriteNull(Long id) {
        String shopJson = stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY + id);
        //如果redis未命中查数据库
        if (StrUtil.isNullOrUndefined(shopJson)){
            Shop shop = shopService.getById(id);
            //数据库也不存则写入空数据到Redis
            if (shop==null){
                //
                stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id,"",CACHE_NULL_TTL,TimeUnit.MINUTES);
                queryShopByIdSolveBreakWithWriteNull(id);
            }
            //数据库存在则将数据写进redis
            shopJson = JSONUtil.toJsonStr(shop);
            stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY+id,shopJson,CACHE_SHOP_TTL,TimeUnit.MINUTES);
            //重新查一遍，就可以从redis获得数据
            return queryShopByIdSolveBreakWithWriteNull(id);
        }
        //如果命中了redis,但是为空,直接返回空对象
        if (StrUtil.isBlank(shopJson)){
            return null;
        }
        //如果命中了redis且不为空,重置时间
        stringRedisTemplate.expire(CACHE_SHOP_KEY+id,CACHE_SHOP_TTL,TimeUnit.MINUTES);
        Shop shop = JSONUtil.toBean(shopJson, Shop.class);
        return shop;
    }

    /**
     * 用互斥锁解决缓存穿透
     * @param id
     * @return
     */
    public Shop queryShopByIdSolvePenetrateWithWriteMutex(Long id) {
        String shopJson = stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY + id);
        //如果redis未命中查数据库
        if (StrUtil.isNullOrUndefined(shopJson)){
            //在查数据库之前先获取锁
            String lockKey=LOCK_SHOP_KEY+id;
            try {
                boolean isLock = tryLock(lockKey);
                if (!isLock){
                    //如果获取锁失败了休眠一段时间后重试
                    Thread.sleep(50);
                    return queryShopByIdSolvePenetrateWithWriteMutex(id);
                }
                //以下为获得锁成功
                Shop shop = shopService.getById(id);
                //数据库也不存则写入空数据到Redis
                if (shop==null){
                    //
                    stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id,"",CACHE_NULL_TTL,TimeUnit.MINUTES);
                    queryShopByIdSolveBreakWithWriteNull(id);
                }
                //数据库存在则将数据写进redis
                shopJson = JSONUtil.toJsonStr(shop);
                stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY+id,shopJson,CACHE_SHOP_TTL,TimeUnit.MINUTES);
                //写进缓存后再释放锁
            } catch (InterruptedException e) {
                e.printStackTrace();
            } finally {
                unlock(lockKey);
            }
            //重新查一遍，就可以从redis获得数据
            return queryShopByIdSolveBreakWithWriteNull(id);
        }
        //如果命中了redis,但是为空,直接返回空对象
        if (StrUtil.isBlank(shopJson)){
            return null;
        }
        //如果命中了redis且不为空,重置时间
        stringRedisTemplate.expire(CACHE_SHOP_KEY+id,CACHE_SHOP_TTL,TimeUnit.MINUTES);
        Shop shop = JSONUtil.toBean(shopJson, Shop.class);
        return shop;
    }

    /**
     * 用逻辑过期时间解决缓存穿透
     * @param id
     * @return
     */
    public Shop queryShopByIdSolvePenetrateWithWriteLogicalExpireTime(Long id) {
        String redisDataJson = stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY + id);
        //如果redis未命中查数据库
        if (StrUtil.isEmpty(redisDataJson)){
            //未命中说明不是热点数据，转入处理缓存击穿的函数
            return null;
        }
        //如果命中了redis且不为空，将其反序列化成对象，拿到逻辑过期时间
        RedisData redisData = JSONUtil.toBean(redisDataJson, RedisData.class);
        Shop shop = JSONUtil.toBean((JSONObject) redisData.getData(), Shop.class);
        LocalDateTime expireTime = redisData.getExpireTime();
        //如果时间已经过期，直接返回旧数据并开启新线程修改数据
        if (expireTime.isBefore(LocalDateTime.now())){
            //在开启新线程之前获得锁，锁的名称要与锁的id关联，修改不同的id可以并行，一样的id才需要加锁
            String lockKey=LOCK_SHOP_KEY+id;
            try {
                boolean isLock = tryLock(lockKey);
                //获取锁成功
                if (isLock){
                    //开启新线程用线程池，性能比自己创建线程好
                    CACHE_REBUILD_EXECUTOR.execute(()->{
                        //重建缓存
                        saveShopWithExpireTimeToRedis(id,30L);
                    });
                }
            } catch (Exception e) {
                e.printStackTrace();
            } finally {
                unlock(lockKey);
            }
        }
        return shop;
    }


    //对于分布式系统才需要使用Redis作为锁的中介
    //这里的内容要包含自己的标识，否则就会出现释放别人的锁的情况
    private boolean tryLock(String key){
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }
    private void unlock(String key){
        stringRedisTemplate.delete(key);
    }

    //将带有逻辑过期的Shop数据写入Redis
    public void saveShopWithExpireTimeToRedis(long id,Long expireMinutes){
        Shop shop = shopService.getById(id);
        RedisData redisData = new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusMinutes(expireMinutes));
        String redisDataJson = JSONUtil.toJsonStr(redisData);
        //这里不要添加逻辑过期时间
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY+id,redisDataJson);
    }

    /**
     * 以下为模板函数
     */


}
