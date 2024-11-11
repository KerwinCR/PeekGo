package com.peekgo;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.EnableAspectJAutoProxy;

@MapperScan("com.peekgo.mapper")
@SpringBootApplication
@EnableAspectJAutoProxy(exposeProxy = true)
public class PeekGoApplication {

    public static void main(String[] args) {
        SpringApplication.run(PeekGoApplication.class, args);
    }


}
