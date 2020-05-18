package com.alibaba.dubbo.demo.consumer;

/**
 * @author admin
 * @Description: TODO
 * @date 2020/5/15 20:30
 */
public interface Notify {

    void onreturn(String result,String param);


    void onthrow(Throwable throwable,String param);
}
