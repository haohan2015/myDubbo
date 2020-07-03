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
package com.alibaba.dubbo.rpc.protocol.dubbo;

import com.alibaba.dubbo.common.URL;
import com.alibaba.dubbo.rpc.Exporter;
import com.alibaba.dubbo.rpc.Invoker;
import com.alibaba.dubbo.rpc.protocol.AbstractExporter;

import java.util.Map;

/**
 * DubboExporter
 */
public class DubboExporter<T> extends AbstractExporter<T> {

    /**
     * 此处的Key类似demoGroup/com.alibaba.dubbo.demo.DemoService:1.0.1:20880
     */
    private final String key;

    /**
     * Exporter 集合
     *
     * 该集合拥有该协议中，所有暴露中的 Exporter 对象，也就是同一个服务的同一个协议只会有一个具体的协议实例，比如InjvmProtocol协议实例
     * 但是同一个协议的不同提供服务，也就是接口，在Map中会有多个实例，key类似demoGroup/com.alibaba.dubbo.demo.DemoService:1.0.1:20880
     * 同一个接口再次暴露key=demoGroup/com.alibaba.dubbo.demo.DemoService2:1.0.1:20880
     */
    private final Map<String, Exporter<?>> exporterMap;

    public DubboExporter(Invoker<T> invoker, String key, Map<String, Exporter<?>> exporterMap) {
        //对于服务提供者 此处的invoker的实际类型是基于过滤器生成的Invoker链条
        super(invoker);
        this.key = key;
        this.exporterMap = exporterMap;
    }

    /**
     * 取消暴露
     */
    @Override
    public void unexport() {
        super.unexport();
        exporterMap.remove(key);
    }

}