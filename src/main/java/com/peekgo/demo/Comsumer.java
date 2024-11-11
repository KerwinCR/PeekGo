package com.peekgo.demo;

import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Component
public class Comsumer {
    @RabbitListener(queues = "order.queue")
    public void listen(Object msg){
        System.out.println(msg);
    }
}
