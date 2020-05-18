package com.alibaba.dubbo.demo.consumer;

/**
 * @author admin
 * @Description: TODO
 * @date 2020/5/15 20:31
 */
public class NotifyImpl implements Notify{

    @Override
    public void onreturn(String result, String param) {
        System.out.println("onreturn = [" + result + "], param = [" + param + "]");
    }

    @Override
    public void onthrow(Throwable throwable, String param) {
        System.out.println("throwable = [" + throwable + "], param = [" + param + "]");
        System.out.println("onthrow =  param = [" + param + "]");
    }
}
