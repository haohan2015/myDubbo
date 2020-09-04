package com.alibaba.dubbo.demo;

import javax.validation.constraints.NotNull;

/**
 * @author admin
 * @Description: TODO
 * @date 2020/7/10 18:26
 */
public interface HelloService {

    String sayHello(String name);

    void add(User user);
}
