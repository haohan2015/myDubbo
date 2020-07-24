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
import com.alibaba.dubbo.common.extension.ExtensionLoader;
import com.alibaba.dubbo.common.utils.ConfigUtils;
import com.alibaba.dubbo.common.utils.NetUtils;
import com.alibaba.dubbo.common.utils.ReflectUtils;
import com.alibaba.dubbo.common.utils.StringUtils;
import com.alibaba.dubbo.common.utils.UrlUtils;
import com.alibaba.dubbo.config.support.Parameter;
import com.alibaba.dubbo.monitor.MonitorFactory;
import com.alibaba.dubbo.monitor.MonitorService;
import com.alibaba.dubbo.registry.RegistryFactory;
import com.alibaba.dubbo.registry.RegistryService;
import com.alibaba.dubbo.rpc.Filter;
import com.alibaba.dubbo.rpc.InvokerListener;
import com.alibaba.dubbo.rpc.ProxyFactory;
import com.alibaba.dubbo.rpc.cluster.Cluster;
import com.alibaba.dubbo.rpc.support.MockInvoker;

import static com.alibaba.dubbo.common.utils.NetUtils.isInvalidLocalHost;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * AbstractDefaultConfig
 *
 * @export
 */
public abstract class AbstractInterfaceConfig extends AbstractMethodConfig {

    private static final long serialVersionUID = -1559314110797223229L;

    /**
     * local impl class name for the service interface
     * 服务接口客户端本地代理类名，用于在客户端执行本地逻辑，如本地缓存等。
     *
     * 该本地代理类的构造函数必须允许传入远程代理对象，构造函数如：public XxxServiceLocal(XxxService xxxService)
     *
     * 设为 true，表示使用缺省代理类名，即：接口名 + Local 后缀
     */
    protected String local;

    /**
     * local stub class name for the service interface
     * 服务接口客户端本地代理类名，用于在客户端执行本地逻辑，如本地缓存等。
     *
     * 该本地代理类的构造函数必须允许传入远程代理对象，构造函数如：public XxxServiceStub(XxxService xxxService)
     *
     * 设为 true，表示使用缺省代理类名，即：接口名 + Stub 后缀
     *
     * 参见文档 <a href="本地存根">http://dubbo.io/books/dubbo-user-book/demos/local-stub.html</>
     */
    protected String stub;

    //监控器配置
    // service monitor
    protected MonitorConfig monitor;

    //代理类型
    // proxy type
    protected String proxy;

    //集群类型
    // cluster type
    protected String cluster;

    //过滤器
    // filter
    protected String filter;

    //监听器
    // listener
    protected String listener;

    //拥有者
    // owner
    protected String owner;

    //是否共享连接
    // connection limits, 0 means shared connection, otherwise it defines the connections delegated to the
    // current service
    protected Integer connections;

    // layer
    protected String layer;

    // application info
    protected ApplicationConfig application;

    // module info
    protected ModuleConfig module;

    // registry centers
    protected List<RegistryConfig> registries;

    // connection events
    protected String onconnect;

    // disconnection events
    protected String ondisconnect;

    // callback limits
    private Integer callbacks;

    // the scope for referring/exporting a service, if it's local, it means searching in current JVM only.
    private String scope;

    //校验注册中心
    protected void checkRegistry() {
        // for backward compatibility
        if (registries == null || registries.isEmpty()) {
            //如果为null，则根据系统配置属性来创建注册中心
            String address = ConfigUtils.getProperty("dubbo.registry.address");
            if (address != null && address.length() > 0) {
                registries = new ArrayList<RegistryConfig>();
                String[] as = address.split("\\s*[|]+\\s*");
                for (String a : as) {
                    RegistryConfig registryConfig = new RegistryConfig();
                    registryConfig.setAddress(a);
                    registries.add(registryConfig);
                }
            }
        }
        if ((registries == null || registries.isEmpty())) {
            throw new IllegalStateException((getClass().getSimpleName().startsWith("Reference")
                    ? "No such any registry to refer service in consumer "
                    : "No such any registry to export service in provider ")
                    + NetUtils.getLocalHost()
                    + " use dubbo version "
                    + Version.getVersion()
                    + ", Please add <dubbo:registry address=\"...\" /> to your spring config. If you want unregister, please set <dubbo:service registry=\"N/A\" />");
        }
        // 读取环境变量和 properties 配置到 RegistryConfig 对象数组。
        for (RegistryConfig registryConfig : registries) {
            appendProperties(registryConfig);
        }
    }

    //Application校验
    @SuppressWarnings("deprecation")
    protected void checkApplication() {
        // for backward compatibility
        if (application == null) {
            //如果为null，则根据系统配置来创建应用
            String applicationName = ConfigUtils.getProperty("dubbo.application.name");
            if (applicationName != null && applicationName.length() > 0) {
                application = new ApplicationConfig();
            }
        }
        if (application == null) {
            throw new IllegalStateException(
                    "No such application config! Please add <dubbo:application name=\"...\" /> to your spring config.");
        }
        //通过反射给属性赋值
        appendProperties(application);
        //设置优雅停机等待时间
        String wait = ConfigUtils.getProperty(Constants.SHUTDOWN_WAIT_KEY);
        if (wait != null && wait.trim().length() > 0) {
            System.setProperty(Constants.SHUTDOWN_WAIT_KEY, wait.trim());
        } else {
            wait = ConfigUtils.getProperty(Constants.SHUTDOWN_WAIT_SECONDS_KEY);
            if (wait != null && wait.trim().length() > 0) {
                System.setProperty(Constants.SHUTDOWN_WAIT_SECONDS_KEY, wait.trim());
            }
        }
    }

    /**
     * 1.检测是否存在注册中心配置类，不存在则抛出异常
     * 2.构建参数映射集合，也就是 map
     * 3.构建注册中心链接列表
     * 4.遍历链接列表，并根据条件决定是否将其添加到 registryList 中
     *
     */
    protected List<URL> loadRegistries(boolean provider) {
        // 检测是否存在注册中心配置类，不存在则抛出异常
        checkRegistry();

        List<URL> registryList = new ArrayList<URL>();
        if (registries != null && !registries.isEmpty()) {
            for (RegistryConfig config : registries) {
                String address = config.getAddress();
                if (address == null || address.length() == 0) {
                    // 若 address 为空，则将其设为 0.0.0.0
                    address = Constants.ANYHOST_VALUE;
                }
                // 从系统属性中加载注册中心地址
                //系统属性中的注册中心地址优先级高
                String sysaddress = System.getProperty("dubbo.registry.address");
                if (sysaddress != null && sysaddress.length() > 0) {
                    address = sysaddress;
                }

                // 检测 address 是否合法
                if (address.length() > 0 && !RegistryConfig.NO_AVAILABLE.equalsIgnoreCase(address)) {
                    //注册中心的地址是一个有效的地址
                    Map<String, String> map = new HashMap<String, String>();
                    //通过反射把application中的属性添加到map
                    appendParameters(map, application);
                    //通过反射把RegistryConfig中的属性添加到map
                    appendParameters(map, config);

                    // 添加 path、pid，protocol 等信息到 map 中
                    map.put("path", RegistryService.class.getName());
                    //添加dubbo协议版本号
                    map.put("dubbo", Version.getProtocolVersion());
                    //设置时间戳
                    map.put(Constants.TIMESTAMP_KEY, String.valueOf(System.currentTimeMillis()));
                    if (ConfigUtils.getPid() > 0) {
                        //设置进程id
                        map.put(Constants.PID_KEY, String.valueOf(ConfigUtils.getPid()));
                    }
                    //如果不指定，此处一般不包含protocol，并且最终会选择dubbo协议
                    if (!map.containsKey("protocol")) {
                        if (ExtensionLoader.getExtensionLoader(RegistryFactory.class).hasExtension("remote")) {
                            map.put("protocol", "remote");
                        } else {
                            //默认dubbo协议
                            map.put("protocol", "dubbo");
                        }
                    }
                    // 解析得到 URL 列表，address 可能包含多个注册中心 ip，
                    // 因此解析得到的是一个 URL 列表
                    //处理同一个注册中心多个地址的情况，例如zookeeper集群，如果就一个注册中心，那么urls的长度也是1
                    List<URL> urls = UrlUtils.parseURLs(address, map);
                    for (URL url : urls) {
                        //设置实际注册中心协议，如果注册中心是zookeeper，那么getProtocol获取的就是zookeeper
                        url = url.addParameter(Constants.REGISTRY_KEY, url.getProtocol());
                        // 将 URL 协议头设置为 registry
                        //设置通用注册协议
                        url = url.setProtocol(Constants.REGISTRY_PROTOCOL);
                        // 通过判断条件，决定是否添加 url 到 registryList 中，条件如下：
                        // (服务提供者 && register = true 或 null)
                        //    || (非服务提供者 && subscribe = true 或 null)
                        if ((provider && url.getParameter(Constants.REGISTER_KEY, true))
                                || (!provider && url.getParameter(Constants.SUBSCRIBE_KEY, true))) {
                            //1.提供者，并且需要注册服务 2.消费者，需要消费服务
                            registryList.add(url);
                        }
                    }
                }
            }
        }
        return registryList;
    }

    //加载监控中心
    protected URL loadMonitor(URL registryURL) {
        if (monitor == null) {
            //配置中读取监控中心的地址和协议
            String monitorAddress = ConfigUtils.getProperty("dubbo.monitor.address");
            String monitorProtocol = ConfigUtils.getProperty("dubbo.monitor.protocol");
            if ((monitorAddress == null || monitorAddress.length() == 0) && (monitorProtocol == null || monitorProtocol.length() == 0)) {
                return null;
            }

            //创建监控中心
            monitor = new MonitorConfig();
            if (monitorAddress != null && monitorAddress.length() > 0) {
                monitor.setAddress(monitorAddress);
            }
            if (monitorProtocol != null && monitorProtocol.length() > 0) {
                monitor.setProtocol(monitorProtocol);
            }
        }
        //设置监控中心属性
        appendProperties(monitor);
        // 添加 `interface` `dubbo` `timestamp` `pid` 到 `map` 集合中
        Map<String, String> map = new HashMap<String, String>();
        map.put(Constants.INTERFACE_KEY, MonitorService.class.getName());
        map.put("dubbo", Version.getProtocolVersion());
        map.put(Constants.TIMESTAMP_KEY, String.valueOf(System.currentTimeMillis()));
        if (ConfigUtils.getPid() > 0) {
            map.put(Constants.PID_KEY, String.valueOf(ConfigUtils.getPid()));
        }
        //set ip
        //获取需要dubbo服务的ip地址，服务消费者和服务提供者都会调用该方法
        String hostToRegistry = ConfigUtils.getSystemProperty(Constants.DUBBO_IP_TO_REGISTRY);
        if (hostToRegistry == null || hostToRegistry.length() == 0) {
            hostToRegistry = NetUtils.getLocalHost();
        } else if (isInvalidLocalHost(hostToRegistry)) {
            throw new IllegalArgumentException("Specified invalid registry ip from property:" + Constants.DUBBO_IP_TO_REGISTRY + ", value:" + hostToRegistry);
        }
        // 将 MonitorConfig ，添加到 `map` 集合中。
        map.put(Constants.REGISTER_IP_KEY, hostToRegistry);
        appendParameters(map, monitor);
        // 将 application ，添加到 `map` 集合中。
        appendParameters(map, application);
        // 获得地址，系统属性优先级高
        String address = monitor.getAddress();
        String sysaddress = System.getProperty("dubbo.monitor.address");
        if (sysaddress != null && sysaddress.length() > 0) {
            address = sysaddress;
        }
        //直连监控中心服务器地址的情况
        if (ConfigUtils.isNotEmpty(address)) {
            // 若不存在 `protocol` 参数，默认 "dubbo" 添加到 `map` 集合中。
            if (!map.containsKey(Constants.PROTOCOL_KEY)) {
                //logstat 这个拓展实现已经不存在
                if (ExtensionLoader.getExtensionLoader(MonitorFactory.class).hasExtension("logstat")) {
                    map.put(Constants.PROTOCOL_KEY, "logstat");
                } else {
                    map.put(Constants.PROTOCOL_KEY, "dubbo");
                }
            }
            // 解析地址，创建 Dubbo URL 对象。
            return UrlUtils.parseURL(address, map);
        } else if (Constants.REGISTRY_PROTOCOL.equals(monitor.getProtocol()) && registryURL != null) {
            // 从注册中心发现监控中心地址
            return registryURL.setProtocol("dubbo").addParameter(Constants.PROTOCOL_KEY, "registry").addParameterAndEncoded(Constants.REFER_KEY, StringUtils.toQueryString(map));
        }
        return null;
    }

    protected void checkInterfaceAndMethods(Class<?> interfaceClass, List<MethodConfig> methods) {
        // interface cannot be null
        if (interfaceClass == null) {
            throw new IllegalStateException("interface not allow null!");
        }
        // to verify interfaceClass is an interface
        if (!interfaceClass.isInterface()) {
            throw new IllegalStateException("The interface class " + interfaceClass + " is not a interface!");
        }
        // check if methods exist in the interface
        if (methods != null && !methods.isEmpty()) {
            for (MethodConfig methodBean : methods) {
                String methodName = methodBean.getName();
                if (methodName == null || methodName.length() == 0) {
                    throw new IllegalStateException("<dubbo:method> name attribute is required! Please check: <dubbo:service interface=\"" + interfaceClass.getName() + "\" ... ><dubbo:method name=\"\" ... /></<dubbo:reference>");
                }
                boolean hasMethod = false;
                for (java.lang.reflect.Method method : interfaceClass.getMethods()) {
                    if (method.getName().equals(methodName)) {
                        hasMethod = true;
                        break;
                    }
                }
                if (!hasMethod) {
                    throw new IllegalStateException("The interface " + interfaceClass.getName()
                            + " not found method " + methodName);
                }
            }
        }
    }

    protected void checkStubAndMock(Class<?> interfaceClass) {
        if (ConfigUtils.isNotEmpty(local)) {
            //已弃用
            Class<?> localClass = ConfigUtils.isDefault(local) ? ReflectUtils.forName(interfaceClass.getName() + "Local") : ReflectUtils.forName(local);
            if (!interfaceClass.isAssignableFrom(localClass)) {
                throw new IllegalStateException("The local implementation class " + localClass.getName() + " not implement interface " + interfaceClass.getName());
            }
            try {
                ReflectUtils.findConstructor(localClass, interfaceClass);
            } catch (NoSuchMethodException e) {
                throw new IllegalStateException("No such constructor \"public " + localClass.getSimpleName() + "(" + interfaceClass.getName() + ")\" in local implementation class " + localClass.getName());
            }
        }
        if (ConfigUtils.isNotEmpty(stub)) {
            //如果弃用本地存根，则判断存根实现类是否是指定接口的实现
            Class<?> localClass = ConfigUtils.isDefault(stub) ? ReflectUtils.forName(interfaceClass.getName() + "Stub") : ReflectUtils.forName(stub);
            if (!interfaceClass.isAssignableFrom(localClass)) {
                throw new IllegalStateException("The local implementation class " + localClass.getName() + " not implement interface " + interfaceClass.getName());
            }
            try {
                //判断存根类是否存在参数为接口类型构造函数
                ReflectUtils.findConstructor(localClass, interfaceClass);
            } catch (NoSuchMethodException e) {
                throw new IllegalStateException("No such constructor \"public " + localClass.getSimpleName() + "(" + interfaceClass.getName() + ")\" in local implementation class " + localClass.getName());
            }
        }
        if (ConfigUtils.isNotEmpty(mock)) {
            if (mock.startsWith(Constants.RETURN_PREFIX)) {
                //如果是mock，且是以return开头，则校验mock的合法性
                String value = mock.substring(Constants.RETURN_PREFIX.length());
                try {
                    MockInvoker.parseMockValue(value);
                } catch (Exception e) {
                    throw new IllegalStateException("Illegal mock json value in <dubbo:service ... mock=\"" + mock + "\" />");
                }
            } else {
                //否则就是指定的mokc类，校验指定的mock类是否是指定的接口的实现
                //从 Mock 配置校验的逻辑我们可以看出，不允许配置 "force:" 和 "fail:" 为开头。所以，不能通过 Java API 或者 XML ，又或者注解来配置 Mock 规则，只能通过配置规则来开启服务降级
                Class<?> mockClass = ConfigUtils.isDefault(mock) ? ReflectUtils.forName(interfaceClass.getName() + "Mock") : ReflectUtils.forName(mock);
                if (!interfaceClass.isAssignableFrom(mockClass)) {
                    throw new IllegalStateException("The mock implementation class " + mockClass.getName() + " not implement interface " + interfaceClass.getName());
                }
                try {
                    //判断mock类是是否有无参数构造函数
                    mockClass.getConstructor(new Class<?>[0]);
                } catch (NoSuchMethodException e) {
                    throw new IllegalStateException("No such empty constructor \"public " + mockClass.getSimpleName() + "()\" in mock implementation class " + mockClass.getName());
                }
            }
        }
    }

    /**
     * @return local
     * @deprecated Replace to <code>getStub()</code>
     */
    @Deprecated
    public String getLocal() {
        return local;
    }

    /**
     * @param local
     * @deprecated Replace to <code>setStub(Boolean)</code>
     */
    @Deprecated
    public void setLocal(Boolean local) {
        if (local == null) {
            setLocal((String) null);
        } else {
            setLocal(String.valueOf(local));
        }
    }

    /**
     * @param local
     * @deprecated Replace to <code>setStub(String)</code>
     */
    @Deprecated
    public void setLocal(String local) {
        checkName("local", local);
        this.local = local;
    }

    public String getStub() {
        return stub;
    }

    public void setStub(Boolean stub) {
        if (stub == null) {
            setStub((String) null);
        } else {
            setStub(String.valueOf(stub));
        }
    }

    public void setStub(String stub) {
        checkName("stub", stub);
        this.stub = stub;
    }

    public String getCluster() {
        return cluster;
    }

    public void setCluster(String cluster) {
        checkExtension(Cluster.class, "cluster", cluster);
        this.cluster = cluster;
    }

    public String getProxy() {
        return proxy;
    }

    public void setProxy(String proxy) {
        checkExtension(ProxyFactory.class, "proxy", proxy);
        this.proxy = proxy;
    }

    public Integer getConnections() {
        return connections;
    }

    public void setConnections(Integer connections) {
        this.connections = connections;
    }

    @Parameter(key = Constants.REFERENCE_FILTER_KEY, append = true)
    public String getFilter() {
        return filter;
    }

    public void setFilter(String filter) {
        checkMultiExtension(Filter.class, "filter", filter);
        this.filter = filter;
    }

    @Parameter(key = Constants.INVOKER_LISTENER_KEY, append = true)
    public String getListener() {
        return listener;
    }

    public void setListener(String listener) {
        checkMultiExtension(InvokerListener.class, "listener", listener);
        this.listener = listener;
    }

    public String getLayer() {
        return layer;
    }

    public void setLayer(String layer) {
        checkNameHasSymbol("layer", layer);
        this.layer = layer;
    }

    public ApplicationConfig getApplication() {
        return application;
    }

    public void setApplication(ApplicationConfig application) {
        this.application = application;
    }

    public ModuleConfig getModule() {
        return module;
    }

    public void setModule(ModuleConfig module) {
        this.module = module;
    }

    public RegistryConfig getRegistry() {
        return registries == null || registries.isEmpty() ? null : registries.get(0);
    }

    public void setRegistry(RegistryConfig registry) {
        List<RegistryConfig> registries = new ArrayList<RegistryConfig>(1);
        registries.add(registry);
        this.registries = registries;
    }

    public List<RegistryConfig> getRegistries() {
        return registries;
    }

    @SuppressWarnings({"unchecked"})
    public void setRegistries(List<? extends RegistryConfig> registries) {
        this.registries = (List<RegistryConfig>) registries;
    }

    public MonitorConfig getMonitor() {
        return monitor;
    }

    public void setMonitor(String monitor) {
        this.monitor = new MonitorConfig(monitor);
    }

    public void setMonitor(MonitorConfig monitor) {
        this.monitor = monitor;
    }

    public String getOwner() {
        return owner;
    }

    public void setOwner(String owner) {
        checkMultiName("owner", owner);
        this.owner = owner;
    }

    public Integer getCallbacks() {
        return callbacks;
    }

    public void setCallbacks(Integer callbacks) {
        this.callbacks = callbacks;
    }

    public String getOnconnect() {
        return onconnect;
    }

    public void setOnconnect(String onconnect) {
        this.onconnect = onconnect;
    }

    public String getOndisconnect() {
        return ondisconnect;
    }

    public void setOndisconnect(String ondisconnect) {
        this.ondisconnect = ondisconnect;
    }

    public String getScope() {
        return scope;
    }

    public void setScope(String scope) {
        this.scope = scope;
    }

}