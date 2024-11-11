package com.peekgo;

import com.peekgo.entity.Shop;
import com.peekgo.service.ISeckillVoucherService;
import com.peekgo.service.IVoucherService;
import com.peekgo.service.impl.ShopServiceImpl;
import com.peekgo.utils.RedisIdWorker;
import lombok.AllArgsConstructor;
import lombok.Data;
import org.elasticsearch.client.RestHighLevelClient;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;

import javax.annotation.Resource;
import java.util.*;
import java.util.stream.Collectors;

@SpringBootTest
class PeekGoApplicationTests {
    @Autowired
    ISeckillVoucherService seckillVoucherService;
    @Autowired
    IVoucherService voucherService;

    @Resource
    private ShopServiceImpl shopService;
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    RedisIdWorker redisIdWorker;
    @Resource
    private RabbitTemplate rabbitTemplate;
    @Resource
    private RestHighLevelClient highLevelClient;

    @Test
    void fn2() throws InterruptedException {
        System.out.println(highLevelClient);
        System.out.println(stringRedisTemplate);
        System.out.println(rabbitTemplate);
        }
    @Test
    void fn3() throws InterruptedException {
        List<Shop> list = shopService.list();
        Map<Long,List<Shop>> map= list.stream().collect(Collectors.groupingBy(Shop::getTypeId));
        for (Map.Entry<Long, List<Shop>> entry : map.entrySet()) {
            Long typeId = entry.getKey();
            String key="shop:geo:"+typeId;
            List<Shop> value = entry.getValue();
            List<RedisGeoCommands.GeoLocation<String>> locations=new ArrayList<>(value.size());
            for (Shop shop : value) {
                //stringRedisTemplate.opsForGeo().add(key,new Point(shop.getX(),shop.getY()),shop.getId().toString());
                locations.add(new RedisGeoCommands.GeoLocation<>(shop.getId().toString(),new Point(shop.getX(),shop.getY())));
            }
            stringRedisTemplate.opsForGeo().add(key,locations);
        }

    }
        @Data
    @AllArgsConstructor
    class Demo<T>{
        String name;
        T date;
    }
    @Data
    @AllArgsConstructor
    class User{
        String name;
        int age;
    }
}

