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
import com.alibaba.dubbo.common.utils.ClassHelper;
import com.alibaba.dubbo.common.utils.ConfigUtils;
import com.alibaba.dubbo.common.utils.NamedThreadFactory;
import com.alibaba.dubbo.common.utils.StringUtils;
import com.alibaba.dubbo.config.annotation.Service;
import com.alibaba.dubbo.config.invoker.DelegateProviderMetaDataInvoker;
import com.alibaba.dubbo.config.model.ApplicationModel;
import com.alibaba.dubbo.config.model.ProviderModel;
import com.alibaba.dubbo.config.support.Parameter;
import com.alibaba.dubbo.rpc.Exporter;
import com.alibaba.dubbo.rpc.Invoker;
import com.alibaba.dubbo.rpc.Protocol;
import com.alibaba.dubbo.rpc.ProxyFactory;
import com.alibaba.dubbo.rpc.ServiceClassHolder;
import com.alibaba.dubbo.rpc.cluster.ConfiguratorFactory;
import com.alibaba.dubbo.rpc.service.GenericService;
import com.alibaba.dubbo.rpc.support.ProtocolUtils;

import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static com.alibaba.dubbo.common.utils.NetUtils.LOCALHOST;
import static com.alibaba.dubbo.common.utils.NetUtils.getAvailablePort;
import static com.alibaba.dubbo.common.utils.NetUtils.getLocalHost;
import static com.alibaba.dubbo.common.utils.NetUtils.isInvalidLocalHost;
import static com.alibaba.dubbo.common.utils.NetUtils.isInvalidPort;

/**
 * ServiceConfig
 *
 * @export
 */
public class ServiceConfig<T> extends AbstractServiceConfig {

    private static final long serialVersionUID = 3033787999037024738L;

    /**
     * 自适应 Protocol 实现对象
     */
    private static final Protocol protocol = ExtensionLoader.getExtensionLoader(Protocol.class).getAdaptiveExtension();

    /**
     * 自适应 ProxyFactory 实现对象
     */
    private static final ProxyFactory proxyFactory = ExtensionLoader.getExtensionLoader(ProxyFactory.class).getAdaptiveExtension();

    private static final Map<String, Integer> RANDOM_PORT_MAP = new HashMap<String, Integer>();

    //延迟暴露执行器
    private static final ScheduledExecutorService delayExportExecutor = Executors.newSingleThreadScheduledExecutor(new NamedThreadFactory("DubboServiceDelayExporter", true));

    //导出URL
    private final List<URL> urls = new ArrayList<URL>();

    /**
     * 服务配置暴露的 Exporter 。
     * URL ：Exporter 不一定是 1：1 的关系。
     * 例如 {@link #scope} 未设置时，会暴露 Local + Remote 两个，也就是 URL ：Exporter = 1：2
     *      {@link #scope} 设置为空时，不会暴露，也就是 URL ：Exporter = 1：0
     *      {@link #scope} 设置为 Local 或 Remote 任一时，会暴露 Local 或 Remote 一个，也就是 URL ：Exporter = 1：1
     *
     * 非配置。
     */
    private final List<Exporter<?>> exporters = new ArrayList<Exporter<?>>();

    // interface type
    private String interfaceName;

    private Class<?> interfaceClass;

    // reference to interface impl
    private T ref;

    // service name
    private String path;

    // method configuration
    private List<MethodConfig> methods;

    private ProviderConfig provider;

    /**
     * 是否已经暴露服务，参见 {@link #doExport()} 方法。
     *
     * 非配置。
     */
    private transient volatile boolean exported;

    /**
     * 是否已取消暴露服务，参见 {@link #unexport()} 方法。
     *
     * 非配置。
     */
    private transient volatile boolean unexported;

    /**
     * 是否泛化实现，参见 <a href="https://dubbo.gitbooks.io/dubbo-user-book/demos/generic-service.html">实现泛化调用</a>
     * true / false
     *
     * 状态字段，非配置。
     */
    private volatile String generic;

    public ServiceConfig() {
    }

    public ServiceConfig(Service service) {
        appendAnnotation(Service.class, service);
    }

    @Deprecated
    private static List<ProtocolConfig> convertProviderToProtocol(List<ProviderConfig> providers) {
        if (providers == null || providers.isEmpty()) {
            return null;
        }
        List<ProtocolConfig> protocols = new ArrayList<ProtocolConfig>(providers.size());
        for (ProviderConfig provider : providers) {
            protocols.add(convertProviderToProtocol(provider));
        }
        return protocols;
    }

    @Deprecated
    private static List<ProviderConfig> convertProtocolToProvider(List<ProtocolConfig> protocols) {
        if (protocols == null || protocols.isEmpty()) {
            return null;
        }
        List<ProviderConfig> providers = new ArrayList<ProviderConfig>(protocols.size());
        for (ProtocolConfig provider : protocols) {
            providers.add(convertProtocolToProvider(provider));
        }
        return providers;
    }

    @Deprecated
    private static ProtocolConfig convertProviderToProtocol(ProviderConfig provider) {
        ProtocolConfig protocol = new ProtocolConfig();
        protocol.setName(provider.getProtocol().getName());
        protocol.setServer(provider.getServer());
        protocol.setClient(provider.getClient());
        protocol.setCodec(provider.getCodec());
        protocol.setHost(provider.getHost());
        protocol.setPort(provider.getPort());
        protocol.setPath(provider.getPath());
        protocol.setPayload(provider.getPayload());
        protocol.setThreads(provider.getThreads());
        protocol.setParameters(provider.getParameters());
        return protocol;
    }

    @Deprecated
    private static ProviderConfig convertProtocolToProvider(ProtocolConfig protocol) {
        ProviderConfig provider = new ProviderConfig();
        provider.setProtocol(protocol);
        provider.setServer(protocol.getServer());
        provider.setClient(protocol.getClient());
        provider.setCodec(protocol.getCodec());
        provider.setHost(protocol.getHost());
        provider.setPort(protocol.getPort());
        provider.setPath(protocol.getPath());
        provider.setPayload(protocol.getPayload());
        provider.setThreads(protocol.getThreads());
        provider.setParameters(protocol.getParameters());
        return provider;
    }

    private static Integer getRandomPort(String protocol) {
        protocol = protocol.toLowerCase();
        if (RANDOM_PORT_MAP.containsKey(protocol)) {
            return RANDOM_PORT_MAP.get(protocol);
        }
        return Integer.MIN_VALUE;
    }

    private static void putRandomPort(String protocol, Integer port) {
        protocol = protocol.toLowerCase();
        if (!RANDOM_PORT_MAP.containsKey(protocol)) {
            RANDOM_PORT_MAP.put(protocol, port);
        }
    }

    public URL toUrl() {
        return urls.isEmpty() ? null : urls.iterator().next();
    }

    public List<URL> toUrls() {
        return urls;
    }

    @Parameter(excluded = true)
    public boolean isExported() {
        return exported;
    }

    @Parameter(excluded = true)
    public boolean isUnexported() {
        return unexported;
    }

    public synchronized void export() {
        // 当 export 或者 delay ，从 ProviderConfig 对象读取。
        if (provider != null) {
            if (export == null) {
                export = provider.getExport();
            }
            if (delay == null) {
                delay = provider.getDelay();
            }
        }
        //如果export不为null并且不导出该服务，那么直接返回
        if (export != null && !export) {
            return;
        }

        //是否是延迟导出服务，如果是那么单独用延迟线程池来延迟导出
        if (delay != null && delay > 0) {
            delayExportExecutor.schedule(new Runnable() {
                @Override
                public void run() {
                    doExport();
                }
            }, delay, TimeUnit.MILLISECONDS);
        } else {
            doExport();
        }
    }

    protected synchronized void doExport() {
        //判断是否取消导出，如果是抛异常
        if (unexported) {
            throw new IllegalStateException("Already unexported!");
        }
        //是否已经导出，如果是则直接返回
        if (exported) {
            return;
        }
        //设置volatile变量export为true，来标识服务已经导出，不需要其他线程导出
        exported = true;
        //判断导出服务的接口名称是否非法
        if (interfaceName == null || interfaceName.length() == 0) {
            throw new IllegalStateException("<dubbo:service interface=\"\" /> interface not allow null!");
        }

        // 检测 provider 是否为空，为空则新建一个，并通过系统变量为其初始化
        checkDefault();

        // 下面几个 if 语句用于检测 provider、application 等核心配置类对象是否为空，
        // 若为空，则尝试从其他配置类对象中获取相应的实例。
        if (provider != null) {
            //根据provider为配置赋值
            if (application == null) {
                application = provider.getApplication();
            }
            if (module == null) {
                module = provider.getModule();
            }
            if (registries == null) {
                registries = provider.getRegistries();
            }
            if (monitor == null) {
                monitor = provider.getMonitor();
            }
            if (protocols == null) {
                protocols = provider.getProtocols();
            }
        }

        // 从 ModuleConfig 对象中，读取 registries、monitor 配置对象。
        if (module != null) {
            //根据module为配置赋值
            if (registries == null) {
                registries = module.getRegistries();
            }
            if (monitor == null) {
                monitor = module.getMonitor();
            }
        }

        // 从 ApplicationConfig 对象中，读取 registries、monitor 配置对象。
        if (application != null) {
            //根据application为配置赋值
            if (registries == null) {
                registries = application.getRegistries();
            }
            if (monitor == null) {
                monitor = application.getMonitor();
            }
        }
        //判断是否为泛化暴露服务
        if (ref instanceof GenericService) {
            //如果是泛化暴露服务，这是接口名称为泛化接口名称
            interfaceClass = GenericService.class;
            if (StringUtils.isEmpty(generic)) {
                //如果泛化标识不为空，则设置泛化标识为true
                generic = Boolean.TRUE.toString();
            }
        } else {
            try {
                //检查暴露服务的接口的类是否存在
                interfaceClass = Class.forName(interfaceName, true, Thread.currentThread()
                        .getContextClassLoader());
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException(e.getMessage(), e);
            }
            //如果在<dubbo:service>标签内有<dubbo:method>，则校验指定的方法在接口里是否存在
            // 对 interfaceClass，以及 <dubbo:method> 标签中的必要字段进行检查
            checkInterfaceAndMethods(interfaceClass, methods);
            //校验引用是否存在，如果存在则判断一下该引用是否是指定接口名称的实现
            // 对 ref 合法性进行检测
            checkRef();
            //设置泛化标识为false
            generic = Boolean.FALSE.toString();
        }
        // local 和 stub 在功能应该是一致的，用于配置本地存根
        if (local != null) {
            //判断是否是本地暴露
            if ("true".equals(local)) {
                local = interfaceName + "Local";
            }
            Class<?> localClass;
            try {
                localClass = ClassHelper.forNameWithThreadContextClassLoader(local);
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException(e.getMessage(), e);
            }
            if (!interfaceClass.isAssignableFrom(localClass)) {
                throw new IllegalStateException("The local implementation class " + localClass.getName() + " not implement interface " + interfaceName);
            }
        }
        if (stub != null) {
            //判断是否有本地存根
            if ("true".equals(stub)) {
                stub = interfaceName + "Stub";
            }
            Class<?> stubClass;
            try {
                // 获取本地存根类
                stubClass = ClassHelper.forNameWithThreadContextClassLoader(stub);
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException(e.getMessage(), e);
            }
            // 检测本地存根类是否实现了给定接口类，若不可赋值则会抛出异常，提醒使用者本地存根类类型不合法
            if (!interfaceClass.isAssignableFrom(stubClass)) {
                throw new IllegalStateException("The stub implementation class " + stubClass.getName() + " not implement interface " + interfaceName);
            }
        }

        // 检测各种对象是否为空，为空则新建，或者抛出异常
        //校验应用配置
        checkApplication();
        //校验注册中心配置
        checkRegistry();
        //校验协议配
        checkProtocol();
        //校验服务暴露配置，并且通过反射为属性赋值
        appendProperties(this);
        //对stub和local的校验
        checkStubAndMock(interfaceClass);
        //判断服务导出路径是否为空，为空则设置指定接口名称
        if (path == null || path.length() == 0) {
            path = interfaceName;
        }

        //暴露服务
        doExportUrls();

        // ProviderModel 表示服务提供者模型，此对象中存储了与服务提供者相关的信息。
        // 比如服务的配置信息，服务实例等。每个被导出的服务对应一个 ProviderModel。
        // ApplicationModel 持有所有的 ProviderModel。
        ProviderModel providerModel = new ProviderModel(getUniqueServiceName(), this, ref);
        ApplicationModel.initProviderModel(getUniqueServiceName(), providerModel);
    }

    private void checkRef() {
        // reference should not be null, and is the implementation of the given interface
        if (ref == null) {
            throw new IllegalStateException("ref not allow null!");
        }
        if (!interfaceClass.isInstance(ref)) {
            throw new IllegalStateException("The class "
                    + ref.getClass().getName() + " unimplemented interface "
                    + interfaceClass + "!");
        }
    }

    public synchronized void unexport() {
        if (!exported) {
            return;
        }
        if (unexported) {
            return;
        }
        if (!exporters.isEmpty()) {
            for (Exporter<?> exporter : exporters) {
                try {
                    exporter.unexport();
                } catch (Throwable t) {
                    logger.warn("unexpected err when unexport" + exporter, t);
                }
            }
            exporters.clear();
        }
        unexported = true;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void doExportUrls() {
        //加载注册中心
        List<URL> registryURLs = loadRegistries(true);
        //根据不同的协议来分别暴露服务
        for (ProtocolConfig protocolConfig : protocols) {
            doExportUrlsFor1Protocol(protocolConfig, registryURLs);
        }
    }

    /**
     * 此处的protocolConfig一般就是指dubbo协议，实体里只有name=dubbo
     * @param protocolConfig
     * @param registryURLs
     */
    private void doExportUrlsFor1Protocol(ProtocolConfig protocolConfig, List<URL> registryURLs) {
        String name = protocolConfig.getName();
        // 如果协议名为空，或空串，则将协议名变量设置为 dubbo
        if (name == null || name.length() == 0) {
            name = "dubbo";
        }

        Map<String, String> map = new HashMap<String, String>();
        // 添加 side、版本、时间戳以及进程号等信息到 map 中
        //设置这是服务提供方标识
        map.put(Constants.SIDE_KEY, Constants.PROVIDER_SIDE);
        //dubbo版本号
        map.put(Constants.DUBBO_VERSION_KEY, Version.getProtocolVersion());
        //时间戳
        map.put(Constants.TIMESTAMP_KEY, String.valueOf(System.currentTimeMillis()));
        if (ConfigUtils.getPid() > 0) {
            //进程id
            map.put(Constants.PID_KEY, String.valueOf(ConfigUtils.getPid()));
        }
        // 通过反射将对象的字段信息添加到 map 中
        //把应用application的属性添加到map
        appendParameters(map, application);
        //把模块module的属性添加到map
        appendParameters(map, module);
        //把提供者provider的属性添加到map，并且设置key的前缀
        // ProviderConfig ，为 ServiceConfig 的默认属性，因此添加 `default` 属性前缀。
        appendParameters(map, provider, Constants.DEFAULT_KEY);
        //把协议配置protocolConfig的属性添加到map
        appendParameters(map, protocolConfig);
        //把服务配置的属性添加到map
        appendParameters(map, this);

        // methods 为 MethodConfig 集合，MethodConfig 中存储了 <dubbo:method> 标签的配置信息
        if (methods != null && !methods.isEmpty()) {
            // 这段代码用于添加 Callback 配置到 map 中，代码太长，待会单独分析
            //如果service内有method子标签，那么基于方法标签对map赋值
            for (MethodConfig method : methods) {
                // 添加 MethodConfig 对象的字段信息到 map 中，键 = 方法名.属性名。
                // 比如存储 <dubbo:method name="sayHello" retries="2"> 对应的 MethodConfig，
                // 键 = sayHello.retries，map = {"sayHello.retries": 2, "xxx": "yyy"}
                //把方法配置属性添加到map，并且前缀是方法名
                appendParameters(map, method, method.getName());
                String retryKey = method.getName() + ".retry";
                if (map.containsKey(retryKey)) {
                    //如果map中包含方法级别的重试参数，那么移除，并且如果值等于false那么设置方法级别的重试次数为0
                    String retryValue = map.remove(retryKey);
                    if ("false".equals(retryValue)) {
                        map.put(method.getName() + ".retries", "0");
                    }
                }

                // 获取 ArgumentConfig 列表
                //获取方法标签下的参数配置标签
                List<ArgumentConfig> arguments = method.getArguments();
                if (arguments != null && !arguments.isEmpty()) {
                    for (ArgumentConfig argument : arguments) {
                        // 检测 type 属性是否为空，或者空串（分支1 ⭐️）
                        if (argument.getType() != null && argument.getType().length() > 0) {
                            //指定参数类型，例如<dubbo:argument type="com.demo.CallbackListener" callback="true" />
                            //获取接口的所有方法
                            Method[] methods = interfaceClass.getMethods();
                            // 比对方法名，查找目标方法
                            if (methods != null && methods.length > 0) {
                                for (int i = 0; i < methods.length; i++) {
                                    String methodName = methods[i].getName();
                                    // target the method, and get its signature
                                    if (methodName.equals(method.getName())) {
                                        //如果标签的方法名和当前接口的方法名相同
                                        Class<?>[] argtypes = methods[i].getParameterTypes();
                                        // one callback in the method
                                        if (argument.getIndex() != -1) {
                                            // 检测 ArgumentConfig 中的 type 属性与方法参数列表
                                            // 中的参数名称是否一致，不一致则抛出异常(分支2 ⭐️)
                                            //如果参数标签同时设置了下表属性，例如<dubbo:argument index="1" callback="true"/>
                                            //则判断index标签指定的方法参数名称和实际argument指定的type是否相等
                                            if (argtypes[argument.getIndex()].getName().equals(argument.getType())) {
                                                // 添加 ArgumentConfig 字段信息到 map 中，
                                                // 键前缀 = 方法名.index，比如:
                                                // map = {"sayHello.3": true}
                                                //如果相等则把参数属性添加到map，并且前缀为方法名加参数下标
                                                appendParameters(map, argument, method.getName() + "." + argument.getIndex());
                                            } else {
                                                //否则抛异常
                                                throw new IllegalArgumentException("argument config error : the index attribute and type attribute not match :index :" + argument.getIndex() + ", type:" + argument.getType());
                                            }
                                        } else {
                                            // 分支3
                                            // multiple callbacks in the method
                                            //遍历接口的方法参数
                                            for (int j = 0; j < argtypes.length; j++) {
                                                Class<?> argclazz = argtypes[j];
                                                // 从参数类型列表中查找类型名称为 argument.type 的参数
                                                if (argclazz.getName().equals(argument.getType())) {
                                                    //如果相等则把参数属性添加到map，并且前缀为方法名加参数下标
                                                    //比如map = {"sayHello.3": true}
                                                    appendParameters(map, argument, method.getName() + "." + j);
                                                    if (argument.getIndex() != -1 && argument.getIndex() != j) {
                                                        //不会走到这
                                                        throw new IllegalArgumentException("argument config error : the index attribute and type attribute not match :index :" + argument.getIndex() + ", type:" + argument.getType());
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                            // 用户未配置 type 属性，但配置了 index 属性，且 index != -1
                        } else if (argument.getIndex() != -1) {
                            // 添加 ArgumentConfig 字段信息到 map 中
                            //如果参数标签指定index，则把参数属性添加到map，并且前缀为方法名加参数下标
                            appendParameters(map, argument, method.getName() + "." + argument.getIndex());
                        } else {
                            //否则抛异常
                            throw new IllegalArgumentException("argument config must set index or type attribute.eg: <dubbo:argument index='0' .../> or <dubbo:argument type=xxx .../>");
                        }

                    }
                }
            } // end of methods for
        }

        // 检测 generic 是否为 "true"，并根据检测结果向 map 中添加不同的信息
        if (ProtocolUtils.isGeneric(generic)) {
            //判断是否泛化
            map.put(Constants.GENERIC_KEY, generic);
            map.put(Constants.METHODS_KEY, Constants.ANY_VALUE);
        } else {
            String revision = Version.getVersion(interfaceClass, version);
            if (revision != null && revision.length() > 0) {
                map.put("revision", revision);
            }

            // 为接口生成包裹类 Wrapper，Wrapper 中包含了接口的详细信息，比如接口方法名数组，字段信息等，类似{"sayHello"}
            //获取接口包装类，然后获取包装类的所有方法名
            String[] methods = Wrapper.getWrapper(interfaceClass).getMethodNames();
            if (methods.length == 0) {
                logger.warn("NO method found in service interface " + interfaceClass.getName());
                map.put(Constants.METHODS_KEY, Constants.ANY_VALUE);
            } else {
                // 将逗号作为分隔符连接方法名，并将连接后的字符串放入 map 中
                //设置methods属性，方法名用逗号分隔
                map.put(Constants.METHODS_KEY, StringUtils.join(new HashSet<String>(Arrays.asList(methods)), ","));
            }
        }
        //如果配置了令牌验证，例如<dubbo:provider interface="com.foo.BarService" token="123456" />
        if (!ConfigUtils.isEmpty(token)) {
            if (ConfigUtils.isDefault(token)) {
                //如果配置为true，例如<dubbo:provider interface="com.foo.BarService" token="true" />，则通过uuid生成凭证
                map.put(Constants.TOKEN_KEY, UUID.randomUUID().toString());
            } else {
                map.put(Constants.TOKEN_KEY, token);
            }
        }
        // 判断协议名是否为 injvm
        //如果配置的协议是本地，则不需要注册，例如<dubbo:protocol name="injvm" />
        if (Constants.LOCAL_PROTOCOL.equals(protocolConfig.getName())) {
            protocolConfig.setRegister(false);
            map.put("notify", "false");
        }
        // export service
        //上下文路径，通常的配置，此处的contextPath都是null
        String contextPath = protocolConfig.getContextpath();
        if ((contextPath == null || contextPath.length() == 0) && provider != null) {
            contextPath = provider.getContextpath();
        }
        //获取服务暴露地址
        String host = this.findConfigedHosts(protocolConfig, registryURLs, map);
        //获取服务暴露端口
        Integer port = this.findConfigedPorts(protocolConfig, name, map);
        //创建服务暴露URL，此处的path类似com.alibaba.dubbo.demo.DemoService
        URL url = new URL(name, host, port, (contextPath == null || contextPath.length() == 0 ? "" : contextPath + "/") + path, map);

        //此处url.getProtocol()获取的是dubbo，此处返回的是false, 配置规则 后续解读
        if (ExtensionLoader.getExtensionLoader(ConfiguratorFactory.class)
                .hasExtension(url.getProtocol())) {
            // 加载 ConfiguratorFactory，并生成 Configurator 实例，然后通过实例配置 url
            url = ExtensionLoader.getExtensionLoader(ConfiguratorFactory.class)
                    .getExtension(url.getProtocol()).getConfigurator(url).configure(url);
        }

        //根据服务导出范围来分别导出服务
        String scope = url.getParameter(Constants.SCOPE_KEY);
        // 如果 scope = none，则什么都不做，一般此处是为Null
        // don't export when none is configured
        if (!Constants.SCOPE_NONE.toString().equalsIgnoreCase(scope)) {

            // export to local if the config is not remote (export to remote only when config is remote)
            // scope != remote，导出到本地
            //只要不明确指定只导出远程服务，都会导出本地服务
            if (!Constants.SCOPE_REMOTE.toString().equalsIgnoreCase(scope)) {
                exportLocal(url);
            }
            // export to remote if the config is not local (export to local only when config is local)
            //只要不明确指定只导出本地服务，都会导出远程服务
            if (!Constants.SCOPE_LOCAL.toString().equalsIgnoreCase(scope)) {
                if (logger.isInfoEnabled()) {
                    logger.info("Export dubbo service " + interfaceClass.getName() + " to url " + url);
                }
                //如果同时存在多个不同的注册中心，那么分别导出服务
                if (registryURLs != null && !registryURLs.isEmpty()) {
                    for (URL registryURL : registryURLs) {
                        //服务是否动态注册，默认为true，如果设为false，注册后将显示为disable状态，需人工启用，并且服务提供者停止时，也不会自动取消注册，需人工禁用。
                        url = url.addParameterIfAbsent(Constants.DYNAMIC_KEY, registryURL.getParameter(Constants.DYNAMIC_KEY));
                        // 加载监视器链接
                        URL monitorUrl = loadMonitor(registryURL);
                        if (monitorUrl != null) {
                            url = url.addParameterAndEncoded(Constants.MONITOR_KEY, monitorUrl.toFullString());
                        }
                        if (logger.isInfoEnabled()) {
                            logger.info("Register dubbo service " + interfaceClass.getName() + " url " + url + " to registry " + registryURL);
                        }

                        // For providers, this is used to enable custom proxy to generate invoker
                        //对于提供者，可以自己制定代理创建方式，默认为null
                        String proxy = url.getParameter(Constants.PROXY_KEY);
                        if (StringUtils.isNotEmpty(proxy)) {
                            registryURL = registryURL.addParameter(Constants.PROXY_KEY, proxy);
                        }

                        // 为服务提供类(ref)生成 Invoker
                        //Dubbo 默认的 ProxyFactory 实现类是 JavassistProxyFactory
                        //通过代理工厂创建代理类，注意此时invoker里放的url是registryURL，此处proxyFactory的是proxyFactory$Adaptive，此处默认实现是JavassistProxyFactory
                        Invoker<?> invoker = proxyFactory.getInvoker(ref, (Class) interfaceClass, registryURL.addParameterAndEncoded(Constants.EXPORT_KEY, url.toFullString()));
                        // DelegateProviderMetaDataInvoker 用于持有 Invoker 和 ServiceConfig，此处的invoer实际是AbstractProxyInvoker的匿名实现类
                        //原始Invoker的代理
                        /**
                         * 此处的protocol实际类型是Protocol$Adaptive，而适应类里调用的也不是RegistryProtocol,
                         * 首先是包装类ProtocolListenerWrapper，该类中继续调用包装类ProtocolFilterWrapper，最后调用
                         * RegistryProtocol生成Exporter，然后在ProtocolListenerWrapper中创建ListenerExporterWrapper，并且在ListenerExporterWrapper对于服务导出监听器调用
                         * 此处导出的exporter实际类型是ListenerExporterWrapper
                         */
                        DelegateProviderMetaDataInvoker wrapperInvoker = new DelegateProviderMetaDataInvoker(invoker, this);
                        //通过RegistryProtocol来暴露远程服务
                        Exporter<?> exporter = protocol.export(wrapperInvoker);
                        exporters.add(exporter);
                    }
                } else {
                    Invoker<?> invoker = proxyFactory.getInvoker(ref, (Class) interfaceClass, url);
                    DelegateProviderMetaDataInvoker wrapperInvoker = new DelegateProviderMetaDataInvoker(invoker, this);

                    Exporter<?> exporter = protocol.export(wrapperInvoker);
                    exporters.add(exporter);
                }
            }
        }
        this.urls.add(url);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void exportLocal(URL url) {
        // 如果 URL 的协议头等于 injvm，说明已经导出到本地了，无需再次导出
        if (!Constants.LOCAL_PROTOCOL.equalsIgnoreCase(url.getProtocol())) {
            //因为utl在后面远程暴露服务会使用，所以要创建新的本地url
            URL local = URL.valueOf(url.toFullString())
                    .setProtocol(Constants.LOCAL_PROTOCOL)// 设置协议头为 injvm
                    .setHost(LOCALHOST)
                    .setPort(0);
            //把当前引用的类型放到当前线程的ThreadLocal
            // 添加服务的真实类名，例如 DemoServiceImpl ，仅用于 RestProtocol 中。
            ServiceClassHolder.getInstance().pushServiceClass(getServiceClass(ref));
            /**此处的proxyFactory是自适应，实际是通过Javassist生成代理类，此处的protocol实际类型是Protocol$Adaptive，而适应类里调用的也不是injvmProtocol,
             * 首先是包装类ProtocolFilterWrapper，在该类中基于真实的invoker生成过滤器链条，该类中继续调用包装类ProtocolListenerWrapper，最后调用
             * injvmInvoker生成Exporter，然后在ProtocolListenerWrapper中创建ListenerExporterWrapper，并且在ListenerExporterWrapper对于服务导出监听器调用
             * 此处导出的exporter实际类型是ListenerExporterWrapper
             */
            Exporter<?> exporter = protocol.export(
                    proxyFactory.getInvoker(ref, (Class) interfaceClass, local));
            //添加服务Exporter到exporters
            exporters.add(exporter);
            logger.info("Export dubbo service " + interfaceClass.getName() + " to local registry");
        }
    }

    protected Class getServiceClass(T ref) {
        return ref.getClass();
    }

    /**
     * Register & bind IP address for service provider, can be configured separately.
     * Configuration priority: environment variables -> java system properties -> host property in config file ->
     * /etc/hosts -> default network address -> first available network address
     *
     * @param protocolConfig
     * @param registryURLs
     * @param map
     * @return
     */
    private String findConfigedHosts(ProtocolConfig protocolConfig, List<URL> registryURLs, Map<String, String> map) {
        boolean anyhost = false;

        //首先从环境变量中获取服务暴露地址，如果不为空，并且不是合法的ip那么抛异常
        String hostToBind = getValueFromConfig(protocolConfig, Constants.DUBBO_IP_TO_BIND);
        if (hostToBind != null && hostToBind.length() > 0 && isInvalidLocalHost(hostToBind)) {
            throw new IllegalArgumentException("Specified invalid bind ip from property:" + Constants.DUBBO_IP_TO_BIND + ", value:" + hostToBind);
        }

        // if bind ip is not found in environment, keep looking up
        //如果环境变量中没有找到，那么继续寻找
        if (hostToBind == null || hostToBind.length() == 0) {
            //从协议配置获取主机地址
            hostToBind = protocolConfig.getHost();
            if (provider != null && (hostToBind == null || hostToBind.length() == 0)) {
                //如果主机地址为空，继续从提供者获取
                hostToBind = provider.getHost();
            }
            if (isInvalidLocalHost(hostToBind)) {
                //如果当前得到的主机地址仍然是无效的，那么继续寻找
                anyhost = true;
                try {
                    hostToBind = InetAddress.getLocalHost().getHostAddress();
                } catch (UnknownHostException e) {
                    logger.warn(e.getMessage(), e);
                }
                //如果当前获取到的仍然为无效主机地址，连接注册中心通过套接字获取
                if (isInvalidLocalHost(hostToBind)) {
                    if (registryURLs != null && !registryURLs.isEmpty()) {
                        for (URL registryURL : registryURLs) {
                            if (Constants.MULTICAST.equalsIgnoreCase(registryURL.getParameter("registry"))) {
                                // skip multicast registry since we cannot connect to it via Socket
                                continue;
                            }
                            try {
                                Socket socket = new Socket();
                                try {
                                    SocketAddress addr = new InetSocketAddress(registryURL.getHost(), registryURL.getPort());
                                    socket.connect(addr, 1000);
                                    hostToBind = socket.getLocalAddress().getHostAddress();
                                    break;
                                } finally {
                                    try {
                                        socket.close();
                                    } catch (Throwable e) {
                                    }
                                }
                            } catch (Exception e) {
                                logger.warn(e.getMessage(), e);
                            }
                        }
                    }
                    //如果当前获取到的仍然为无效主机地址，则通过当前宿主机的第一块网卡来获取
                    if (isInvalidLocalHost(hostToBind)) {
                        hostToBind = getLocalHost();
                    }
                }
            }
        }
        //设置服务暴露地址到map
        map.put(Constants.BIND_IP_KEY, hostToBind);

        // registry ip is not used for bind ip by default
        //判断注册到注册中心的ip地址是否被指定，如果被指定，判断其合法性
        String hostToRegistry = getValueFromConfig(protocolConfig, Constants.DUBBO_IP_TO_REGISTRY);
        if (hostToRegistry != null && hostToRegistry.length() > 0 && isInvalidLocalHost(hostToRegistry)) {
            throw new IllegalArgumentException("Specified invalid registry ip from property:" + Constants.DUBBO_IP_TO_REGISTRY + ", value:" + hostToRegistry);
        } else if (hostToRegistry == null || hostToRegistry.length() == 0) {
            // bind ip is used as registry ip by default
            //如果注册到注册中心的ip地址为空，则同样适用服务绑定ip
            hostToRegistry = hostToBind;
        }

        map.put(Constants.ANYHOST_KEY, String.valueOf(anyhost));

        return hostToRegistry;
    }

    /**
     * Register port and bind port for the provider, can be configured separately
     * Configuration priority: environment variable -> java system properties -> port property in protocol config file
     * -> protocol default port
     *
     * @param protocolConfig
     * @param name
     * @return
     */
    private Integer findConfigedPorts(ProtocolConfig protocolConfig, String name, Map<String, String> map) {
        Integer portToBind = null;

        // parse bind port from environment
        //从环境变量中获取端口
        String port = getValueFromConfig(protocolConfig, Constants.DUBBO_PORT_TO_BIND);
        //把端口转换成整数
        portToBind = parsePort(port);

        // if there's no bind port found from environment, keep looking up.
        //如果没有在环境变量中找到，继续找
        if (portToBind == null) {
            portToBind = protocolConfig.getPort();
            if (provider != null && (portToBind == null || portToBind == 0)) {
                //如果当前的端口为空，继续从提供者中获取
                portToBind = provider.getPort();
            }

            final int defaultPort = ExtensionLoader.getExtensionLoader(Protocol.class).getExtension(name).getDefaultPort();
            if (portToBind == null || portToBind == 0) {
                //如果仍然为空，继续根据当前指定的协议来获取默认端口，例如dubbo的20880端口
                portToBind = defaultPort;
            }
            if (portToBind == null || portToBind <= 0) {
                //如果仍然为空，那就随机指定一个
                portToBind = getRandomPort(name);
                if (portToBind == null || portToBind < 0) {
                    portToBind = getAvailablePort(defaultPort);
                    putRandomPort(name, portToBind);
                }
                logger.warn("Use random available port(" + portToBind + ") for protocol " + name);
            }
        }

        // save bind port, used as url's key later
        map.put(Constants.BIND_PORT_KEY, String.valueOf(portToBind));

        // registry port, not used as bind port by default
        String portToRegistryStr = getValueFromConfig(protocolConfig, Constants.DUBBO_PORT_TO_REGISTRY);
        Integer portToRegistry = parsePort(portToRegistryStr);
        if (portToRegistry == null) {
            //获取注册到注册中心的端口，如果没有指定，那就同样用服务暴露的端口
            portToRegistry = portToBind;
        }

        return portToRegistry;
    }

    private Integer parsePort(String configPort) {
        Integer port = null;
        if (configPort != null && configPort.length() > 0) {
            try {
                Integer intPort = Integer.parseInt(configPort);
                if (isInvalidPort(intPort)) {
                    throw new IllegalArgumentException("Specified invalid port from env value:" + configPort);
                }
                port = intPort;
            } catch (Exception e) {
                throw new IllegalArgumentException("Specified invalid port from env value:" + configPort);
            }
        }
        return port;
    }

    private String getValueFromConfig(ProtocolConfig protocolConfig, String key) {
        String protocolPrefix = protocolConfig.getName().toUpperCase() + "_";
        String port = ConfigUtils.getSystemProperty(protocolPrefix + key);
        if (port == null || port.length() == 0) {
            port = ConfigUtils.getSystemProperty(key);
        }
        return port;
    }

    private void checkDefault() {
        if (provider == null) {
            //创建provider
            provider = new ProviderConfig();
        }
        //根据provider里面的set方法为属性设置值
        appendProperties(provider);
    }

    private void checkProtocol() {
        if ((protocols == null || protocols.isEmpty())
                && provider != null) {
            //如果协议没有配置，则用provider中的协议
            setProtocols(provider.getProtocols());
        }
        // backward compatibility
        if (protocols == null || protocols.isEmpty()) {
            //如果仍然为null则手动创建
            setProtocol(new ProtocolConfig());
        }
        for (ProtocolConfig protocolConfig : protocols) {
            if (StringUtils.isEmpty(protocolConfig.getName())) {
                //如果协议名称为空则设置默认协议名称dubbo
                protocolConfig.setName(Constants.DUBBO_VERSION_KEY);
            }
            //通过反射为协议属性赋值
            appendProperties(protocolConfig);
        }
    }

    public Class<?> getInterfaceClass() {
        if (interfaceClass != null) {
            return interfaceClass;
        }
        if (ref instanceof GenericService) {
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
    public void setInterfaceClass(Class<?> interfaceClass) {
        setInterface(interfaceClass);
    }

    public String getInterface() {
        return interfaceName;
    }

    public void setInterface(String interfaceName) {
        this.interfaceName = interfaceName;
        if (id == null || id.length() == 0) {
            id = interfaceName;
        }
    }

    public void setInterface(Class<?> interfaceClass) {
        if (interfaceClass != null && !interfaceClass.isInterface()) {
            throw new IllegalStateException("The interface class " + interfaceClass + " is not a interface!");
        }
        this.interfaceClass = interfaceClass;
        setInterface(interfaceClass == null ? null : interfaceClass.getName());
    }

    public T getRef() {
        return ref;
    }

    public void setRef(T ref) {
        this.ref = ref;
    }

    @Parameter(excluded = true)
    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        checkPathName(Constants.PATH_KEY, path);
        this.path = path;
    }

    public List<MethodConfig> getMethods() {
        return methods;
    }

    // ======== Deprecated ========

    @SuppressWarnings("unchecked")
    public void setMethods(List<? extends MethodConfig> methods) {
        this.methods = (List<MethodConfig>) methods;
    }

    public ProviderConfig getProvider() {
        return provider;
    }

    public void setProvider(ProviderConfig provider) {
        this.provider = provider;
    }

    public String getGeneric() {
        return generic;
    }

    public void setGeneric(String generic) {
        if (StringUtils.isEmpty(generic)) {
            return;
        }
        if (ProtocolUtils.isGeneric(generic)) {
            this.generic = generic;
        } else {
            throw new IllegalArgumentException("Unsupported generic type " + generic);
        }
    }

    public List<URL> getExportedUrls() {
        return urls;
    }

    /**
     * @deprecated Replace to getProtocols()
     */
    @Deprecated
    public List<ProviderConfig> getProviders() {
        return convertProtocolToProvider(protocols);
    }

    /**
     * @deprecated Replace to setProtocols()
     */
    @Deprecated
    public void setProviders(List<ProviderConfig> providers) {
        this.protocols = convertProviderToProtocol(providers);
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
        return buf.toString();
    }
}
