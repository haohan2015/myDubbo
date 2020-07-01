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
package com.alibaba.dubbo.config.invoker;

import com.alibaba.dubbo.common.URL;
import com.alibaba.dubbo.config.ServiceConfig;
import com.alibaba.dubbo.rpc.Invocation;
import com.alibaba.dubbo.rpc.Invoker;
import com.alibaba.dubbo.rpc.Result;
import com.alibaba.dubbo.rpc.RpcException;

/**
 * 该类的作用是把AbstractProxyInvoker的匿名实现类和serverConfig封装到一起
 * @param <T>
 */
public class DelegateProviderMetaDataInvoker<T> implements Invoker {
    protected final Invoker<T> invoker;
    private ServiceConfig metadata;

    public DelegateProviderMetaDataInvoker(Invoker<T> invoker,ServiceConfig metadata) {
        //如果是服务提供者，那么此处的Invoker的真实类型是AbstractProxyInvoker的匿名实现类
        this.invoker = invoker;
        this.metadata = metadata;
    }

    @Override
    public Class<T> getInterface() {
        return invoker.getInterface();
    }

    @Override
    public URL getUrl() {
        return invoker.getUrl();
    }

    @Override
    public boolean isAvailable() {
        return invoker.isAvailable();
    }

    @Override
    public Result invoke(Invocation invocation) throws RpcException {
        //服务提供者 此处invoker的真实类型是JavassistProxyFactory的getInvoker方法生成的AbstractProxyInvoker的匿名类，
        // 因为该类没有覆盖父类的AbstractProxyInvoker方法，所以实际上调用的还是AbstractProxyInvoker
        return invoker.invoke(invocation);
    }

    @Override
    public void destroy() {
        invoker.destroy();
    }

    public ServiceConfig getMetadata() {
        return metadata;
    }
}
