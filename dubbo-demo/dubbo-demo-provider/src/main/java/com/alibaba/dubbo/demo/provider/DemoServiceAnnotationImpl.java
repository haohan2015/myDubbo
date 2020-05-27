package com.alibaba.dubbo.demo.provider;

import com.alibaba.dubbo.config.annotation.Service;
import com.alibaba.dubbo.demo.DemoService;

/**
 * @author admin
 * @Description: TODO
 * @date 2020/5/18 18:52
 */
@Service
public class DemoServiceAnnotationImpl implements DemoService {

    @Override
    public String sayHello(String name) {
        return "annotation: hello " + name;
    }
}
