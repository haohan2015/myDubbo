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
package com.alibaba.dubbo.registry.zookeeper;

import com.alibaba.dubbo.common.URL;
import com.alibaba.dubbo.registry.Registry;
import com.alibaba.dubbo.registry.support.AbstractRegistryFactory;
import com.alibaba.dubbo.remoting.zookeeper.ZookeeperTransporter;

/**
 * ZookeeperRegistryFactory.
 *
 */
public class ZookeeperRegistryFactory extends AbstractRegistryFactory {

    /**
     * Zookeeper工厂
     */
    private ZookeeperTransporter zookeeperTransporter;

    /**
     * 设置 Zookeeper 工厂
     * @param zookeeperTransporter
     */
    public void setZookeeperTransporter(ZookeeperTransporter zookeeperTransporter) {
        this.zookeeperTransporter = zookeeperTransporter;
    }

    /**
     *
     * 此处的zookeeperTransporter是自适应
     * package com.alibaba.dubbo.remoting.zookeeper;
     import com.alibaba.dubbo.common.extension.ExtensionLoader;
     public class ZookeeperTransporter$Adaptive implements com.alibaba.dubbo.remoting.zookeeper.ZookeeperTransporter {
     public com.alibaba.dubbo.remoting.zookeeper.ZookeeperClient connect(
     com.alibaba.dubbo.common.URL arg0) {
     if (arg0 == null) {
     throw new IllegalArgumentException("url == null");
     }

     com.alibaba.dubbo.common.URL url = arg0;
     String extName = url.getParameter("client",
     url.getParameter("transporter", "curator"));

     if (extName == null) {
     throw new IllegalStateException(
     "Fail to get extension(com.alibaba.dubbo.remoting.zookeeper.ZookeeperTransporter) name from url(" +
     url.toString() + ") use keys([client, transporter])");
     }

     com.alibaba.dubbo.remoting.zookeeper.ZookeeperTransporter extension = (com.alibaba.dubbo.remoting.zookeeper.ZookeeperTransporter) ExtensionLoader.getExtensionLoader(com.alibaba.dubbo.remoting.zookeeper.ZookeeperTransporter.class)
     .getExtension(extName);

     return extension.connect(arg0);
     }
     }

     * @param url
     * @return
     */
    @Override
    public Registry createRegistry(URL url) {
        //创建注册中心
        return new ZookeeperRegistry(url, zookeeperTransporter);
    }

}
