package com.alibaba.dubbo.demo.provider;

import com.alibaba.dubbo.demo.HelloService;
import com.alibaba.dubbo.demo.User;
import com.alibaba.fastjson.JSON;

import javax.validation.constraints.NotNull;

/**
 * @author admin
 * @Description: TODO
 * @date 2020/7/10 18:27
 */
public class HelloServiceImpl implements HelloService{


    @Override
    public String sayHello(String name) {
        System.out.println("name = [" + name + "]");
        return "Hello " +name;
    }

    @Override
    public void add(@NotNull User user) {
        System.out.println("user = [" + JSON.toJSONString(user) + "]");
    }
}
