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
package com.alibaba.dubbo.common.serialize.fst;

import com.alibaba.dubbo.common.serialize.support.SerializableClassRegistry;

import org.nustaq.serialization.FSTConfiguration;
import org.nustaq.serialization.FSTObjectInput;
import org.nustaq.serialization.FSTObjectOutput;

import java.io.InputStream;
import java.io.OutputStream;

/**
 * FST 工厂
 */
public class FstFactory {

    /**
     * 单例
     */
    private static final FstFactory factory = new FstFactory();

    /**
     * 配置对象
     */
    private final FSTConfiguration conf = FSTConfiguration.createDefaultConfiguration();


    public static FstFactory getDefaultFactory() {
        return factory;
    }

    /**
     *
     * Preregister a class (use at init time). This avoids having to write class names.
     * Its a very simple and effective optimization (frequently > 2 times faster for small objects).
     * 预注册一个类(在初始化时使用)。这样可以避免编写类名。
     * 它是一种非常简单有效的优化(对于小对象来说，通常是>的2倍)。
     *
     * Read and write side need to have classes preregistered in the exact same order.
     * 客户端和服务端需要预先以完全相同的顺序注册。
     *
     *
     * The list does not have to be complete. Just add your most frequently serialized classes here
     * to get significant gains in speed and smaller serialized representation size.
     *
     * 这个列表并不一定要完整。只需在这里添加最常见的序列化类，以获得速度和较小的序列化表示大小的显著提高。
     */
    public FstFactory() {
        // 注册
        for (Class clazz : SerializableClassRegistry.getRegisteredClasses()) {
            conf.registerClass(clazz);
        }
    }

    public FSTObjectOutput getObjectOutput(OutputStream outputStream) {
        return conf.getObjectOutput(outputStream);
    }

    public FSTObjectInput getObjectInput(InputStream inputStream) {
        return conf.getObjectInput(inputStream);
    }
}
