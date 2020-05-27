package com.alibaba.dubbo.demo.consumer;

import com.alibaba.dubbo.config.annotation.Reference;
import com.alibaba.dubbo.config.annotation.Service;
import com.alibaba.dubbo.demo.DemoService;
import org.springframework.stereotype.Component;


/**
 * @author admin
 * @Description: TODO
 * @date 2020/5/18 19:02
 */
@Component("annotationAction")
public class AnnotationAction {

    @Reference
    private DemoService demoService;

    public void doSayHello(String name){
        System.out.println(demoService.sayHello(name));
    }

}
