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
package com.alibaba.dubbo.validation.support;

import com.alibaba.dubbo.common.URL;
import com.alibaba.dubbo.validation.Validation;
import com.alibaba.dubbo.validation.Validator;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * AbstractValidation
 */
public abstract class AbstractValidation implements Validation {

    private final ConcurrentMap<String, Validator> validators = new ConcurrentHashMap<String, Validator>();

    @Override
    public Validator getValidator(URL url) {
        // 获得 Validator 对象
        //对于服务提供者 此处的key类似dubbo://172.16.10.53:20880/com.alibaba.dubbo.demo.HelloService?anyhost=true
        // &application=demo-provider&bind.ip=172.16.10.53&bind.port=20880&dubbo=2.0.2&generic=false
        // &interface=com.alibaba.dubbo.demo.HelloService&methods=add,sayHello&pid=96704&qos.port=22222
        // &side=provider&timestamp=1595232743424&validation=true
        String key = url.toFullString();
        Validator validator = validators.get(key);
        // 不存在，创建 Validator 对象，并缓存
        if (validator == null) {
            validators.put(key, createValidator(url));
            validator = validators.get(key);
        }
        return validator;
    }

    /**
     * 创建 Validator 对象
     *
     * @param url URL
     * @return Validator 对象
     */
    protected abstract Validator createValidator(URL url);

}
