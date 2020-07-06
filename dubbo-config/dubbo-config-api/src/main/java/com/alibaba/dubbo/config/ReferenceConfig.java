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
package com.alibaba.dubbo.config;

import com.alibaba.dubbo.common.Constants;
import com.alibaba.dubbo.common.URL;
import com.alibaba.dubbo.common.Version;
import com.alibaba.dubbo.common.bytecode.Wrapper;
import com.alibaba.dubbo.common.extension.ExtensionLoader;
import com.alibaba.dubbo.common.utils.ConfigUtils;
import com.alibaba.dubbo.common.utils.NetUtils;
import com.alibaba.dubbo.common.utils.ReflectUtils;
import com.alibaba.dubbo.common.utils.StringUtils;
import com.alibaba.dubbo.config.annotation.Reference;
import com.alibaba.dubbo.config.model.ApplicationModel;
import com.alibaba.dubbo.config.model.ConsumerModel;
import com.alibaba.dubbo.config.support.Parameter;
import com.alibaba.dubbo.rpc.Invoker;
import com.alibaba.dubbo.rpc.Protocol;
import com.alibaba.dubbo.rpc.ProxyFactory;
import com.alibaba.dubbo.rpc.StaticContext;
import com.alibaba.dubbo.rpc.cluster.Cluster;
import com.alibaba.dubbo.rpc.cluster.directory.StaticDirectory;
import com.alibaba.dubbo.rpc.cluster.support.AvailableCluster;
import com.alibaba.dubbo.rpc.cluster.support.ClusterUtils;
import com.alibaba.dubbo.rpc.protocol.injvm.InjvmProtocol;
import com.alibaba.dubbo.rpc.service.GenericService;
import com.alibaba.dubbo.rpc.support.ProtocolUtils;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import static com.alibaba.dubbo.common.utils.NetUtils.isInvalidLocalHost;

/**
 * ReferenceConfig
 *
 * @export
 */
public class ReferenceConfig<T> extends AbstractReferenceConfig {

    private static final long serialVersionUID = -5864351140409987595L;

    /**
     * 协议自适应
     */
    private static final Protocol refprotocol = ExtensionLoader.getExtensionLoader(Protocol.class).getAdaptiveExtension();

    /**
     * 集群自适应
     */
    private static final Cluster cluster = ExtensionLoader.getExtensionLoader(Cluster.class).getAdaptiveExtension();

    /**
     * 代理自适应
     */
    private static final ProxyFactory proxyFactory = ExtensionLoader.getExtensionLoader(ProxyFactory.class).getAdaptiveExtension();

    /**
     * 如果是直连的话就是直连地址集合，如果是通过注册中心的话就是注册中心集合
     */
    private final List<URL> urls = new ArrayList<URL>();

    /**
     * 引用接口名称
     */
    // interface name
    private String interfaceName;

    /**
     * 引用接口
     */
    private Class<?> interfaceClass;
    // client type
    private String client;

    /**
     * 直连提供者地址
     */
    // url for peer-to-peer invocation
    private String url;
    // method configs
    private List<MethodConfig> methods;
    // default config
    private ConsumerConfig consumer;
    private String protocol;
    // interface proxy reference
    private transient volatile T ref;
    private transient volatile Invoker<?> invoker;
    private transient volatile boolean initialized;
    private transient volatile boolean destroyed;
    @SuppressWarnings("unused")
    private final Object finalizerGuardian = new Object() {
        @Override
        protected void finalize() throws Throwable {
            super.finalize();

            if (!ReferenceConfig.this.destroyed) {
                logger.warn("ReferenceConfig(" + url + ") is not DESTROYED when FINALIZE");

                /* don't destroy for now
                try {
                    ReferenceConfig.this.destroy();
                } catch (Throwable t) {
                        logger.warn("Unexpected err when destroy invoker of ReferenceConfig(" + url + ") in finalize method!", t);
                }
                */
            }
        }
    };

    public ReferenceConfig() {
    }

    public ReferenceConfig(Reference reference) {
        appendAnnotation(Reference.class, reference);
    }

    private static void checkAndConvertImplicitConfig(MethodConfig method, Map<String, String> map, Map<Object, Object> attributes) {
        //check config conflict
        if (Boolean.FALSE.equals(method.isReturn()) && (method.getOnreturn() != null || method.getOnthrow() != null)) {
            throw new IllegalStateException("method config error : return attribute must be set true when onreturn or onthrow has been setted.");
        }

        //把onreturn指定的methodName字符串转换为方法实例
        //convert onreturn methodName to Method
        String onReturnMethodKey = StaticContext.getKey(map, method.getName(), Constants.ON_RETURN_METHOD_KEY);
        Object onReturnMethod = attributes.get(onReturnMethodKey);
        if (onReturnMethod instanceof String) {
            attributes.put(onReturnMethodKey, getMethodByName(method.getOnreturn().getClass(), onReturnMethod.toString()));
        }

        //把onthrow指定的methodName字符串转换为方法实例
        //convert onthrow methodName to Method
        String onThrowMethodKey = StaticContext.getKey(map, method.getName(), Constants.ON_THROW_METHOD_KEY);
        Object onThrowMethod = attributes.get(onThrowMethodKey);
        if (onThrowMethod instanceof String) {
            attributes.put(onThrowMethodKey, getMethodByName(method.getOnthrow().getClass(), onThrowMethod.toString()));
        }

        //把oninvoke指定的methodName字符串转换为方法实例
        //convert oninvoke methodName to Method
        String onInvokeMethodKey = StaticContext.getKey(map, method.getName(), Constants.ON_INVOKE_METHOD_KEY);
        Object onInvokeMethod = attributes.get(onInvokeMethodKey);
        if (onInvokeMethod instanceof String) {
            attributes.put(onInvokeMethodKey, getMethodByName(method.getOninvoke().getClass(), onInvokeMethod.toString()));
        }
    }

    private static Method getMethodByName(Class<?> clazz, String methodName) {
        try {
            return ReflectUtils.findMethodByMethodName(clazz, methodName);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public URL toUrl() {
        return urls.isEmpty() ? null : urls.iterator().next();
    }

    public List<URL> toUrls() {
        return urls;
    }

    public synchronized T get() {
        //判断是否已经销毁
        if (destroyed) {
            throw new IllegalStateException("Already destroyed!");
        }
        // 检测 ref 是否为空，为空则通过 init 方法创建
        if (ref == null) {
            // init 方法主要用于处理配置，以及调用 createProxy 生成代理类
            init();
        }
        return ref;
    }

    public synchronized void destroy() {
        if (ref == null) {
            return;
        }
        if (destroyed) {
            return;
        }
        destroyed = true;
        try {
            invoker.destroy();
        } catch (Throwable t) {
            logger.warn("Unexpected err when destroy invoker of ReferenceConfig(" + url + ").", t);
        }
        invoker = null;
        ref = null;
    }

    private void init() {
        //如果已经初始化，则返回
        // 避免重复初始化
        if (initialized) {
            return;
        }
        //设置初始化标识
        initialized = true;
        //如果接口名称为空或者长度为零则抛异常
        // 检测接口名合法性
        if (interfaceName == null || interfaceName.length() == 0) {
            throw new IllegalStateException("<dubbo:reference interface=\"\" /> interface not allow null!");
        }
        // get consumer's global configuration
        //检测 consumer 变量是否为空，为空则创建,并且为其属性设置值
        checkDefault();
        //为当前配置属性设置值
        appendProperties(this);
        // 若未设置 `generic` 属性，使用 `ConsumerConfig.generic` 属性。
        if (getGeneric() == null && getConsumer() != null) {
            //设置generic
            setGeneric(getConsumer().getGeneric());
        }
        // 泛化接口的实现
        if (ProtocolUtils.isGeneric(getGeneric())) {
            //检查是否为泛化
            interfaceClass = GenericService.class;
        } else {
            // 普通接口的实现
            try {
                //加载接口类，以判断接口类是否存在
                interfaceClass = Class.forName(interfaceName, true, Thread.currentThread()
                        .getContextClassLoader());
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException(e.getMessage(), e);
            }
            // 校验接口和方法
            //如果有dubbo:method标签，则判断标签中的方法和接口中的方法是否匹配
            checkInterfaceAndMethods(interfaceClass, methods);
        }

        // 直连提供者，参见文档《直连提供者》http://dubbo.apache.org/zh-cn/docs/user/demos/explicit-target.html
        // 【直连提供者】第一优先级，通过 -D 参数指定 ，例如 java -Dcom.alibaba.xxx.XxxService=dubbo://localhost:20890
        // 从系统变量中获取与接口名对应的属性值
        String resolve = System.getProperty(interfaceName);
        String resolveFile = null;
        // 【直连提供者】第二优先级，通过文件映射，例如 com.alibaba.xxx.XxxService=dubbo://localhost:20890
        if (resolve == null || resolve.length() == 0) {
            // 默认先加载，`${user.home}/dubbo-resolve.properties` 文件 ，无需配置
            // 从系统属性中获取解析文件路径
            resolveFile = System.getProperty("dubbo.resolve.file");
            if (resolveFile == null || resolveFile.length() == 0) {
                // 从指定位置加载配置文件
                File userResolveFile = new File(new File(System.getProperty("user.home")), "dubbo-resolve.properties");
                if (userResolveFile.exists()) {
                    // 获取文件绝对路径
                    resolveFile = userResolveFile.getAbsolutePath();
                }
            }
            // 存在 resolveFile ，则进行文件读取加载。
            if (resolveFile != null && resolveFile.length() > 0) {
                Properties properties = new Properties();
                FileInputStream fis = null;
                try {
                    fis = new FileInputStream(new File(resolveFile));
                    // 从文件中加载配置
                    properties.load(fis);
                } catch (IOException e) {
                    throw new IllegalStateException("Unload " + resolveFile + ", cause: " + e.getMessage(), e);
                } finally {
                    try {
                        if (null != fis) fis.close();
                    } catch (IOException e) {
                        logger.warn(e.getMessage(), e);
                    }
                }
                // 获取与接口名对应的配置
                resolve = properties.getProperty(interfaceName);
            }
        }
        // 设置直连提供者的 url
        if (resolve != null && resolve.length() > 0) {
            // 将 resolve 赋值给 url，是用于直连情况下配置
            url = resolve;
            if (logger.isWarnEnabled()) {
                if (resolveFile != null) {
                    logger.warn("Using default dubbo resolve file " + resolveFile + " replace " + interfaceName + "" + resolve + " to p2p invoke remote service.");
                } else {
                    logger.warn("Using -D" + interfaceName + "=" + resolve + " to p2p invoke remote service.");
                }
            }
        }
        // 从 ConsumerConfig 对象中，读取 application、module、registries、monitor 配置对象。
        if (consumer != null) {
            //如果consumer不为空，并且其他配置为空，那么从consumer中获取其他配置
            if (application == null) {
                application = consumer.getApplication();
            }
            if (module == null) {
                module = consumer.getModule();
            }
            if (registries == null) {
                registries = consumer.getRegistries();
            }
            if (monitor == null) {
                monitor = consumer.getMonitor();
            }
        }
        // 从 ModuleConfig 对象中，读取 registries、monitor 配置对象。
        if (module != null) {
            //如果module不为空，并且其他配置为空，那么从module中获取其他配置
            if (registries == null) {
                registries = module.getRegistries();
            }
            if (monitor == null) {
                monitor = module.getMonitor();
            }
        }
        // 从 ApplicationConfig 对象中，读取 registries、monitor 配置对象。
        if (application != null) {
            //如果application不为空，并且其他配置为空，那么从application中获取其他配置
            if (registries == null) {
                registries = application.getRegistries();
            }
            if (monitor == null) {
                monitor = application.getMonitor();
            }
        }
        // 校验 ApplicationConfig 配置。
        //校验application
        checkApplication();
        // 校验 Stub 和 Mock 相关的配置
        // 检测本地存根配置合法性
        checkStubAndMock(interfaceClass);
        // 将 `side`，`dubbo`，`timestamp`，`pid` 参数，添加到 `map` 集合中。
        // 添加 side、协议版本信息、时间戳和进程号等信息到 map 中
        Map<String, String> map = new HashMap<String, String>();
        Map<Object, Object> attributes = new HashMap<Object, Object>();
        //添加消费端标识
        map.put(Constants.SIDE_KEY, Constants.CONSUMER_SIDE);
        //添加dubbo版本号
        map.put(Constants.DUBBO_VERSION_KEY, Version.getProtocolVersion());
        //添加时间戳
        map.put(Constants.TIMESTAMP_KEY, String.valueOf(System.currentTimeMillis()));
        if (ConfigUtils.getPid() > 0) {
            //添加进程号
            map.put(Constants.PID_KEY, String.valueOf(ConfigUtils.getPid()));
        }
        // 非泛化服务
        if (!isGeneric()) {
            // 获取版本
            String revision = Version.getVersion(interfaceClass, version);
            if (revision != null && revision.length() > 0) {
                map.put("revision", revision);
            }

            // 获取接口方法列表，并添加到 map 中
            String[] methods = Wrapper.getWrapper(interfaceClass).getMethodNames();
            if (methods.length == 0) {
                logger.warn("NO method found in service interface " + interfaceClass.getName());
                map.put("methods", Constants.ANY_VALUE);
            } else {
                map.put("methods", StringUtils.join(new HashSet<String>(Arrays.asList(methods)), ","));
            }
        }
        //添加接口标识
        map.put(Constants.INTERFACE_KEY, interfaceName);
        // 将 ApplicationConfig、ConsumerConfig、ReferenceConfig 等对象的字段信息添加到 map 中
        appendParameters(map, application);
        appendParameters(map, module);
        appendParameters(map, consumer, Constants.DEFAULT_KEY);
        appendParameters(map, this);
        //prefix=com.alibaba.dubbo.demo.DemoService
        String prefix = StringUtils.getServiceKey(map);
        //当指定dubbo:method标签的时候
        if (methods != null && !methods.isEmpty()) {
            // 遍历 MethodConfig 列表
            for (MethodConfig method : methods) {
                appendParameters(map, method, method.getName());
                String retryKey = method.getName() + ".retry";
                // 检测 map 是否包含 methodName.retry
                if (map.containsKey(retryKey)) {
                    String retryValue = map.remove(retryKey);
                    if ("false".equals(retryValue)) {
                        // 添加重试次数配置 methodName.retries
                        map.put(method.getName() + ".retries", "0");
                    }
                }
                // 添加 MethodConfig 中的“属性”字段到 attributes
                // 比如 onreturn、onthrow、oninvoke 等
                appendAttributes(attributes, method, prefix + "." + method.getName());
                checkAndConvertImplicitConfig(method, map, attributes);
            }
        }

        // 获取服务消费者 ip 地址
        String hostToRegistry = ConfigUtils.getSystemProperty(Constants.DUBBO_IP_TO_REGISTRY);
        if (hostToRegistry == null || hostToRegistry.length() == 0) {
            hostToRegistry = NetUtils.getLocalHost();
        } else if (isInvalidLocalHost(hostToRegistry)) {
            throw new IllegalArgumentException("Specified invalid registry ip from property:" + Constants.DUBBO_IP_TO_REGISTRY + ", value:" + hostToRegistry);
        }

        map.put(Constants.REGISTER_IP_KEY, hostToRegistry);

        //attributes are stored by system context.
        // 存储 attributes 到系统上下文中，目的是事件通知
        StaticContext.getSystemContext().putAll(attributes);
        // 创建代理类
        ref = createProxy(map);
        // 根据服务名，ReferenceConfig，代理类构建 ConsumerModel，
        // 并将 ConsumerModel 存入到 ApplicationModel 中
        ConsumerModel consumerModel = new ConsumerModel(getUniqueServiceName(), this, ref, interfaceClass.getMethods());
        ApplicationModel.initConsumerModel(getUniqueServiceName(), consumerModel);
    }

    @SuppressWarnings({"unchecked", "rawtypes", "deprecation"})
    private T createProxy(Map<String, String> map) {
        URL tmpUrl = new URL("temp", "localhost", 0, map);
        final boolean isJvmRefer;
        if (isInjvm() == null) {
            // url 配置被指定，则不做本地引用
            if (url != null && url.length() > 0) { // if a url is specified, don't do local reference
                isJvmRefer = false;
                // 根据 url 的协议、scope 以及 injvm 等参数检测是否需要本地引用
                // 比如如果用户显式配置了 scope=local，此时 isInjvmRefer 返回 true
            } else if (InjvmProtocol.getInjvmProtocol().isInjvmRefer(tmpUrl)) {
                // by default, reference local service if there is
                isJvmRefer = true;
            } else {
                isJvmRefer = false;
            }
        } else {
            // 获取 injvm 配置值
            isJvmRefer = isInjvm().booleanValue();
        }

        if (isJvmRefer) {
            // 生成本地引用 URL，协议为 injvm
            URL url = new URL(Constants.LOCAL_PROTOCOL, NetUtils.LOCALHOST, 0, interfaceClass.getName()).addParameters(map);
            /**
             * 此处的refprotocol的真实类型是protocol$Adaptive，自适应也不是直接调用inJvmProtocol，而是首先调用的是ProtocolListenerWrapper
             * ，然后又调用ProtocolFilterWrapper，然后才是真实的调用inJvmProtocol，生成InjvmInvoker，返回到ProtocolFilterWrapper封装引用过滤器，
             * 返回到ProtocolListenerWrapper会通知该服务的引用通知，然后封装为ListenerInvokerWrapper返回，所以此处的Invoker的真实类型是ListenerInvokerWrapper
             */
            invoker = refprotocol.refer(interfaceClass, url);
            if (logger.isInfoEnabled()) {
                logger.info("Using injvm service " + interfaceClass.getName());
            }
        } else {
            // 远程引用
            // url 不为空，1.用户想跳过注册中心直连服务提供者，2.用户想单独指定注册中心
            if (url != null && url.length() > 0) { // user specified URL, could be peer-to-peer address, or register center's address.
                // 当需要配置多个 url 时，可用分号进行分割，这里会进行切分
                String[] us = Constants.SEMICOLON_SPLIT_PATTERN.split(url);
                if (us != null && us.length > 0) {
                    for (String u : us) {
                        URL url = URL.valueOf(u);
                        if (url.getPath() == null || url.getPath().length() == 0) {
                            // 设置接口全限定名为 url 路径
                            url = url.setPath(interfaceName);
                        }
                        // 检测 url 协议是否为 registry，若是，表明用户想使用指定的注册中心
                        if (Constants.REGISTRY_PROTOCOL.equals(url.getProtocol())) {
                            // 将 map 转换为查询字符串，并作为 refer 参数的值添加到 url 中
                            urls.add(url.addParameterAndEncoded(Constants.REFER_KEY, StringUtils.toQueryString(map)));
                        } else {
                            // 合并 url，移除服务提供者的一些配置（这些配置来源于用户配置的 url 属性），
                            // 比如线程池相关配置。并保留服务提供者的部分配置，比如版本，group，时间戳等
                            // 最后将合并后的配置设置为 url 查询字符串中。
                            urls.add(ClusterUtils.mergeUrl(url, map));
                        }
                    }
                }
            } else { // assemble URL from register center's configuration
                //加载注册中心的url的数组
                List<URL> us = loadRegistries(false);
                if (us != null && !us.isEmpty()) {
                    for (URL u : us) {
                        //加载监控地址
                        URL monitorUrl = loadMonitor(u);
                        // 服务引用配置对象 `map`，带上监控中心的 URL
                        if (monitorUrl != null) {
                            map.put(Constants.MONITOR_KEY, URL.encode(monitorUrl.toFullString()));
                        }
                        // 注册中心的地址，带上服务引用的配置参数，通过这种方式注册中心的URL中包含了服务引用的配置
                        urls.add(u.addParameterAndEncoded(Constants.REFER_KEY, StringUtils.toQueryString(map)));
                    }
                }
                // 未配置注册中心，抛出异常
                if (urls.isEmpty()) {
                    throw new IllegalStateException("No such any registry to reference " + interfaceName + " on the consumer " + NetUtils.getLocalHost() + " use dubbo version " + Version.getVersion() + ", please config <dubbo:registry address=\"...\" /> to your spring config.");
                }
            }
            // 单个注册中心或服务直连提供者(服务直连，下同)
            if (urls.size() == 1) {
                /**
                 * 此处的refprotocol类型为Protocol$Adaptive，会根据url中的Protocol来加载具体的实现，但是实际也并不是直接调用的真实实现类，而是首先调用的是ProtocolFilterWrapper
                 * 对于有注册中心的服务，第一步什么也不做，会紧接着调用ProtocolListenerWrapper，同样对于有注册中心的服务，这一步什么也不做，然后调用RegistryProtocol
                 */
                invoker = refprotocol.refer(interfaceClass, urls.get(0));
            } else {
                // 多个注册中心，或多个服务直连提供者，或者两者混合
                List<Invoker<?>> invokers = new ArrayList<Invoker<?>>();
                URL registryURL = null;
                // 获取所有的 Invoker
                for (URL url : urls) {
                    // 通过 refprotocol 调用 refer 构建 Invoker，refprotocol 会在运行时
                    // 根据 url 协议头加载指定的 Protocol 实例，并调用实例的 refer 方法
                    invokers.add(refprotocol.refer(interfaceClass, url));
                    if (Constants.REGISTRY_PROTOCOL.equals(url.getProtocol())) {
                        registryURL = url; // use last registry url
                    }
                }
                if (registryURL != null) { // registry url is available
                    // use AvailableCluster only when register's cluster is available
                    // 如果注册中心链接不为空，则将使用 AvailableCluster
                    URL u = registryURL.addParameter(Constants.CLUSTER_KEY, AvailableCluster.NAME);
                    // 创建 StaticDirectory 实例，并由 Cluster 对多个 Invoker 进行合并，此处处理的是多个注册中心的集群问题
                    invoker = cluster.join(new StaticDirectory(u, invokers));
                } else {
                    //说明是多个直连地址，此处处理的是多个直连地址的集群问题
                    // not a registry url
                    invoker = cluster.join(new StaticDirectory(invokers));
                }
            }
        }

        Boolean c = check;
        if (c == null && consumer != null) {
            c = consumer.isCheck();
        }
        if (c == null) {
            c = true; // default true
        }
        if (c && !invoker.isAvailable()) {
            throw new IllegalStateException("Failed to check the status of the service " + interfaceName + ". No provider available for the service " + (group == null ? "" : group + "/") + interfaceName + (version == null ? "" : ":" + version) + " from the url " + invoker.getUrl() + " to the consumer " + NetUtils.getLocalHost() + " use dubbo version " + Version.getVersion());
        }
        if (logger.isInfoEnabled()) {
            logger.info("Refer dubbo service " + interfaceClass.getName() + " from url " + invoker.getUrl());
        }
        // create service proxy
        // 生成代理类，此处的invoker类型为MockClusterInvoker
        //此处proxyFactory的实际类型是proxyFactory$Adaptive调用的是StubProxyFactoryWrapper的getProxy
        return (T) proxyFactory.getProxy(invoker);
    }

    private void checkDefault() {
        if (consumer == null) {
            consumer = new ConsumerConfig();
        }
        appendProperties(consumer);
    }

    public Class<?> getInterfaceClass() {
        if (interfaceClass != null) {
            return interfaceClass;
        }
        if (isGeneric()
                || (getConsumer() != null && getConsumer().isGeneric())) {
            return GenericService.class;
        }
        try {
            if (interfaceName != null && interfaceName.length() > 0) {
                this.interfaceClass = Class.forName(interfaceName, true, Thread.currentThread()
                        .getContextClassLoader());
            }
        } catch (ClassNotFoundException t) {
            throw new IllegalStateException(t.getMessage(), t);
        }
        return interfaceClass;
    }

    /**
     * @param interfaceClass
     * @see #setInterface(Class)
     * @deprecated
     */
    @Deprecated
    public void setInterfaceClass(Class<?> interfaceClass) {
        setInterface(interfaceClass);
    }

    public String getInterface() {
        return interfaceName;
    }

    public void setInterface(Class<?> interfaceClass) {
        if (interfaceClass != null && !interfaceClass.isInterface()) {
            throw new IllegalStateException("The interface class " + interfaceClass + " is not a interface!");
        }
        this.interfaceClass = interfaceClass;
        setInterface(interfaceClass == null ? null : interfaceClass.getName());
    }

    public void setInterface(String interfaceName) {
        this.interfaceName = interfaceName;
        if (id == null || id.length() == 0) {
            id = interfaceName;
        }
    }

    public String getClient() {
        return client;
    }

    public void setClient(String client) {
        checkName("client", client);
        this.client = client;
    }

    @Parameter(excluded = true)
    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public List<MethodConfig> getMethods() {
        return methods;
    }

    @SuppressWarnings("unchecked")
    public void setMethods(List<? extends MethodConfig> methods) {
        this.methods = (List<MethodConfig>) methods;
    }

    public ConsumerConfig getConsumer() {
        return consumer;
    }

    public void setConsumer(ConsumerConfig consumer) {
        this.consumer = consumer;
    }

    public String getProtocol() {
        return protocol;
    }

    public void setProtocol(String protocol) {
        this.protocol = protocol;
    }

    // just for test
    Invoker<?> getInvoker() {
        return invoker;
    }

    @Parameter(excluded = true)
    public String getUniqueServiceName() {
        StringBuilder buf = new StringBuilder();
        if (group != null && group.length() > 0) {
            buf.append(group).append("/");
        }
        buf.append(interfaceName);
        if (version != null && version.length() > 0) {
            buf.append(":").append(version);
        }
        String str = buf.toString();
        return str;
    }

}
