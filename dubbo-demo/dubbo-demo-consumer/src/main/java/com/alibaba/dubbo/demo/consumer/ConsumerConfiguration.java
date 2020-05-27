package com.alibaba.dubbo.demo.consumer;

import com.alibaba.dubbo.config.spring.context.annotation.EnableDubbo;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.PropertySource;

/**
 * @author admin
 * @Description: TODO
 * @date 2020/5/18 19:01
 */
@Configuration
@EnableDubbo(scanBasePackages = "com.alibaba.dubbo.demo.consumer")
@PropertySource("classpath:/dubbo-consumer.properties")
@ComponentScan(value = {"com.alibaba.dubbo.demo.consumer"})
public class ConsumerConfiguration {

    public static void main(String[] args) {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(ConsumerConfiguration.class);
        context.start();
        final AnnotationAction annotationAction = (AnnotationAction) context.getBean("annotationAction");
        annotationAction.doSayHello("world");
    }
}
