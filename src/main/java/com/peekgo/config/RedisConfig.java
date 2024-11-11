package com.peekgo.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.Codec;
import org.redisson.codec.JsonJacksonCodec;
import org.redisson.config.Config;
import org.redisson.config.SingleServerConfig;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
/**
 * 配置Redission客户端
 */
public class RedisConfig {
    @Bean
    public RedissonClient redissonClient() {
        Config conf = new Config();
        //单节点模式
        SingleServerConfig singleServerConfig = conf.useSingleServer();
        singleServerConfig.setAddress("redis://127.0.0.1:6379");
        Codec codec = new JsonJacksonCodec();
        conf.setCodec(codec);
        RedissonClient redissonClient = Redisson.create(conf);
        return redissonClient;
    }
}
