package com.alibaba.dubbo.demo.provider;

import com.alibaba.dubbo.config.spring.context.annotation.EnableDubbo;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.PropertySource;

import java.io.IOException;

/**
 * @author admin
 * @Description: TODO
 * @date 2020/5/18 18:54
 */
@Configuration
@EnableDubbo(scanBasePackages = "com.alibaba.dubbo.demo.provider")
@PropertySource("classpath:/dubbo-provider.properties")
public class ProviderConfiguration {
    public static void main(String[] args) {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(ProviderConfiguration.class);
        context.start();

        try {
            System.in.read();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
