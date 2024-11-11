package com.peekgo.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.AllArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

@AllArgsConstructor
@Component
/**
 * 这个是我自己写的，标准模板为CacheClient
 */
public class CacheClient2 {
    //创建一个固定线程池
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);
    private StringRedisTemplate stringRedisTemplate;
    public void saveInRedis(String key, Object value, Long time, TimeUnit unit){
        String objectJson = JSONUtil.toJsonStr(value);
        stringRedisTemplate.opsForValue().set(key,objectJson,time,unit);
    }
    public void saveInRedisWithLogicalExpireTime(String key, Object value, Long time){
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(time));
        String objectJson = JSONUtil.toJsonStr(redisData);
        stringRedisTemplate.opsForValue().set(key,objectJson);
    }

    public <R> R queryByIdSolveBreakWithWriteNull(String key, Long id, Class<R> type, Function<Long,R> searchDatabase,Long time,TimeUnit unit) {
        String objectJson = stringRedisTemplate.opsForValue().get(key);
        //如果redis未命中查数据库
        if (StrUtil.isBlank(objectJson)){
            return JSONUtil.toBean(objectJson,type);
        }
        if (objectJson!=null){
            return null;
        }
        R data = searchDatabase.apply(id);
        if (data==null){
            stringRedisTemplate.opsForValue().set(key,"",time,unit);
        }
        this.saveInRedis(key,data,time,unit);
        return data;
    }

    public <R> R queryByIdSolvePenetrateWithWriteLogicalExpireTime(String redisKey,String lockKey,Long id,Class<R> type,Function<Long,R> searchDatabase)  {
        String redisDataJson = stringRedisTemplate.opsForValue().get(redisKey);
        //如果redis未命中查数据库
        if (StrUtil.isEmpty(redisDataJson)){
            //未命中说明不是热点数据，转入处理缓存击穿的函数
            return null;
        }
        //如果命中了redis且不为空，将其反序列化成对象，拿到逻辑过期时间
        RedisData redisData = JSONUtil.toBean(redisDataJson, RedisData.class);
        R data = JSONUtil.toBean((JSONObject) redisData.getData(), type);
        LocalDateTime expireTime = redisData.getExpireTime();
        //如果时间已经过期，直接返回旧数据并开启新线程修改数据
        if (expireTime.isBefore(LocalDateTime.now())){
            //在开启新线程之前获得锁，锁的名称要与锁的id关联，修改不同的id可以并行，一样的id才需要加锁
            try {
                boolean isLock = tryLock(lockKey);
                //获取锁成功
                if (isLock){
                    //开启新线程用线程池，性能比自己创建线程好
                    CACHE_REBUILD_EXECUTOR.execute(()->{
                        //重建缓存
                        R newData = searchDatabase.apply(id);
                        saveInRedisWithLogicalExpireTime(redisKey,newData,30*60L);
                    });
                }
            } catch (Exception e) {
                e.printStackTrace();
            } finally {
                unlock(lockKey);
            }
        }
        return data;
    }

    public <R> R queryByIdSolvePenetrateWithWriteMutex(String redisKey,String lockKey,Long id,Function<Long,R> searchDatabase,Long time,TimeUnit unit,Class<R> type) {
        String objectJson = stringRedisTemplate.opsForValue().get(redisKey);
        //如果redis未命中查数据库
        if (StrUtil.isNullOrUndefined(objectJson)){
            //在查数据库之前先获取锁
            try {
                boolean isLock = tryLock(lockKey);
                if (!isLock){
                    //如果获取锁失败了休眠一段时间后重试
                    Thread.sleep(50);
                    return queryByIdSolvePenetrateWithWriteMutex(redisKey,lockKey,id,searchDatabase,time,unit,type);
                }
                //以下为获得锁成功
                R date = searchDatabase.apply(id);
                //数据库也不存则写入空数据到Redis
                if (date==null){
                    saveInRedis(redisKey,"",2L,TimeUnit.MINUTES);
                    return queryByIdSolvePenetrateWithWriteMutex(redisKey,lockKey,id,searchDatabase,time,unit,type);
                }
                //数据库存在则将数据写进redis
                objectJson = JSONUtil.toJsonStr(date);
                stringRedisTemplate.opsForValue().set(redisKey,objectJson,time,unit);
                //写进缓存后再释放锁
            } catch (InterruptedException e) {
                e.printStackTrace();
            } finally {
                unlock(lockKey);
            }
            //重新查一遍，就可以从redis获得数据
            return queryByIdSolvePenetrateWithWriteMutex(redisKey,lockKey,id,searchDatabase,time,unit,type);
        }
        //如果命中了redis,但是为空,直接返回空对象
        if (StrUtil.isBlank(objectJson)){
            return null;
        }
        //如果命中了redis且不为空,重置时间
        R date= JSONUtil.toBean(objectJson,type);
        stringRedisTemplate.expire(redisKey,time,unit);
        return date;
    }
    //对于分布式系统才需要使用Redis作为锁的中介
    private boolean tryLock(String key){
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }
    private void unlock(String key){
        stringRedisTemplate.delete(key);
    }
}


