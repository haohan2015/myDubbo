/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.alibaba.dubbo.rpc.proxy;

import com.alibaba.dubbo.common.URL;
import com.alibaba.dubbo.rpc.Invocation;
import com.alibaba.dubbo.rpc.Invoker;
import com.alibaba.dubbo.rpc.Result;
import com.alibaba.dubbo.rpc.RpcException;
import com.alibaba.dubbo.rpc.RpcResult;

import java.lang.reflect.InvocationTargetException;

/**
 * InvokerWrapper
 */
public abstract class AbstractProxyInvoker<T> implements Invoker<T> {

    /**
     * 如果是服务提供者，此处的proxy是spring中的bean
     */
    private final T proxy;

    /**
     * 接口类型，一般是 Service 接口
     */
    private final Class<T> type;

    /**
     * 对于需要注册中心的服务提供者来说，此处的url是RegistryURL，
     * 如果是不需要注册中心只需要暴露服务，那么此处的url就是服务提供者的Url
     */
    private final URL url;

    public AbstractProxyInvoker(T proxy, Class<T> type, URL url) {
        if (proxy == null) {
            throw new IllegalArgumentException("proxy == null");
        }
        if (type == null) {
            throw new IllegalArgumentException("interface == null");
        }
        if (!type.isInstance(proxy)) {
            throw new IllegalArgumentException(proxy.getClass().getName() + " not implement interface " + type);
        }
        this.proxy = proxy;
        this.type = type;
        this.url = url;
    }

    @Override
    public Class<T> getInterface() {
        return type;
    }

    @Override
    public URL getUrl() {
        return url;
    }

    @Override
    public boolean isAvailable() {
        return true;
    }

    @Override
    public void destroy() {
    }

    @Override
    public Result invoke(Invocation invocation) throws RpcException {
        try {
            // 调用 doInvoke 执行后续的调用，并将调用结果封装到 RpcResult 中，此处父类并没有实现doInvoke方法，调用的是子类方法
            // 具体的子实现类有两个，但是都是匿名实现类，一个是在JdkProxyFactory，一个是在JavassistProxyFactory
            //其中JdkProxyFactory的实现类的逻辑是通过反射来调用真实bean的方法
            //而JavassistProxyFactory是通过提前生成的wrapper类来调用服务的具体方法，貌似这样效率更高
            return new RpcResult(doInvoke(proxy, invocation.getMethodName(), invocation.getParameterTypes(), invocation.getArguments()));
        } catch (InvocationTargetException e) {
            return new RpcResult(e.getTargetException());
        } catch (Throwable e) {
            throw new RpcException("Failed to invoke remote proxy method " + invocation.getMethodName() + " to " + getUrl() + ", cause: " + e.getMessage(), e);
        }
    }

    /**
     * 执行调用
     *
     * @param proxy 代理的对象
     * @param methodName 方法名
     * @param parameterTypes 方法参数类型数组
     * @param arguments 方法参数数组
     * @return 调用结果
     * @throws Throwable 发生异常
     */
    protected abstract Object doInvoke(T proxy, String methodName, Class<?>[] parameterTypes, Object[] arguments) throws Throwable;

    @Override
    public String toString() {
        return getInterface() + " -> " + (getUrl() == null ? " " : getUrl().toString());
    }


}
