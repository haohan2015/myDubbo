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
package com.alibaba.dubbo.rpc.protocol.injvm;

import com.alibaba.dubbo.common.Constants;
import com.alibaba.dubbo.common.URL;
import com.alibaba.dubbo.common.extension.ExtensionLoader;
import com.alibaba.dubbo.common.utils.UrlUtils;
import com.alibaba.dubbo.rpc.Exporter;
import com.alibaba.dubbo.rpc.Invoker;
import com.alibaba.dubbo.rpc.Protocol;
import com.alibaba.dubbo.rpc.RpcException;
import com.alibaba.dubbo.rpc.protocol.AbstractProtocol;
import com.alibaba.dubbo.rpc.support.ProtocolUtils;

import java.util.Map;

/**
 * InjvmProtocol
 */
public class InjvmProtocol extends AbstractProtocol implements Protocol {

    /**
     * 协议名
     */
    public static final String NAME = Constants.LOCAL_PROTOCOL;

    /**
     * 默认端口
     */
    public static final int DEFAULT_PORT = 0;

    /**
     * 单例。在 Dubbo SPI 中，被初始化，有且仅有一次。
     */
    private static InjvmProtocol INSTANCE;

    public InjvmProtocol() {
        INSTANCE = this;
    }

    public static InjvmProtocol getInjvmProtocol() {
        if (INSTANCE == null) {
            ExtensionLoader.getExtensionLoader(Protocol.class).getExtension(InjvmProtocol.NAME); // load
        }
        return INSTANCE;
    }

    static Exporter<?> getExporter(Map<String, Exporter<?>> map, URL key) {
        Exporter<?> result = null;

        if (!key.getServiceKey().contains("*")) {
            result = map.get(key.getServiceKey());
        } else {
            if (map != null && !map.isEmpty()) {
                for (Exporter<?> exporter : map.values()) {
                    if (UrlUtils.isServiceKeyMatch(key, exporter.getInvoker().getUrl())) {
                        result = exporter;
                        break;
                    }
                }
            }
        }

        if (result == null) {
            return null;
        } else if (ProtocolUtils.isGeneric(
                result.getInvoker().getUrl().getParameter(Constants.GENERIC_KEY))) {
            return null;
        } else {
            return result;
        }
    }

    @Override
    public int getDefaultPort() {
        return DEFAULT_PORT;
    }

    @Override
    public <T> Exporter<T> export(Invoker<T> invoker) throws RpcException {
        //此处的invoker是包装AbstractProxyInvoker的匿名实现类的过滤器invoker
        // 创建 InjvmExporter
        return new InjvmExporter<T>(invoker, invoker.getUrl().getServiceKey(), exporterMap);
    }

    @Override
    public <T> Invoker<T> refer(Class<T> serviceType, URL url) throws RpcException {
        return new InjvmInvoker<T>(serviceType, url, url.getServiceKey(), exporterMap);
    }

    public boolean isInjvmRefer(URL url) {
        final boolean isJvmRefer;
        String scope = url.getParameter(Constants.SCOPE_KEY);
        // Since injvm protocol is configured explicitly, we don't need to set any extra flag, use normal refer process.
        //引起injvm协议是被显示配置的，我们并不需要去设置其他的标识，使用正常的引用
        if (Constants.LOCAL_PROTOCOL.toString().equals(url.getProtocol())) {
            isJvmRefer = false;
        } else if (Constants.SCOPE_LOCAL.equals(scope) || (url.getParameter("injvm", false))) {
            // if it's declared as local reference
            // 'scope=local' is equivalent to 'injvm=true', injvm will be deprecated in the future release
            //如果指定scope=local 或者injvm=true 那就是代表失injvm引用，未来injvm会被作废
            isJvmRefer = true;
        } else if (Constants.SCOPE_REMOTE.equals(scope)) {
            // it's declared as remote reference
            //如果scope=remote则代表不是injvm引用
            isJvmRefer = false;
        } else if (url.getParameter(Constants.GENERIC_KEY, false)) {
            // generic invocation is not local reference
            //如果是泛化引用的话，也不是代表injvm引用
            isJvmRefer = false;
        } else if (getExporter(exporterMap, url) != null) {
            // by default, go through local reference if there's the service exposed locally
            //默认如果本地有服务导出，那么也是允许injvm
            isJvmRefer = true;
        } else {
            isJvmRefer = false;
        }
        return isJvmRefer;
    }
}
