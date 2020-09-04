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
package com.alibaba.dubbo.registry.integration;

import com.alibaba.dubbo.common.Constants;
import com.alibaba.dubbo.common.URL;
import com.alibaba.dubbo.common.Version;
import com.alibaba.dubbo.common.extension.ExtensionLoader;
import com.alibaba.dubbo.common.logger.Logger;
import com.alibaba.dubbo.common.logger.LoggerFactory;
import com.alibaba.dubbo.common.utils.NetUtils;
import com.alibaba.dubbo.common.utils.StringUtils;
import com.alibaba.dubbo.registry.NotifyListener;
import com.alibaba.dubbo.registry.Registry;
import com.alibaba.dubbo.rpc.Invocation;
import com.alibaba.dubbo.rpc.Invoker;
import com.alibaba.dubbo.rpc.Protocol;
import com.alibaba.dubbo.rpc.RpcException;
import com.alibaba.dubbo.rpc.RpcInvocation;
import com.alibaba.dubbo.rpc.cluster.Cluster;
import com.alibaba.dubbo.rpc.cluster.Configurator;
import com.alibaba.dubbo.rpc.cluster.ConfiguratorFactory;
import com.alibaba.dubbo.rpc.cluster.Router;
import com.alibaba.dubbo.rpc.cluster.RouterFactory;
import com.alibaba.dubbo.rpc.cluster.directory.AbstractDirectory;
import com.alibaba.dubbo.rpc.cluster.directory.StaticDirectory;
import com.alibaba.dubbo.rpc.cluster.support.ClusterUtils;
import com.alibaba.dubbo.rpc.protocol.InvokerWrapper;
import com.alibaba.dubbo.rpc.support.RpcUtils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * RegistryDirectory
 * 该类是多例的，只要有一个服务消费者就对应一个实例
 * 实现 NotifyListener 接口，实现 AbstractDirectory 抽象类，基于注册中心的 Directory 实现类。
 * 订阅注册中心( Registry ) 的数据，实现对变更的监听。
 */
public class RegistryDirectory<T> extends AbstractDirectory<T> implements NotifyListener {

    private static final Logger logger = LoggerFactory.getLogger(RegistryDirectory.class);

    /**
     * 集群自适应工厂
     */
    private static final Cluster cluster = ExtensionLoader.getExtensionLoader(Cluster.class).getAdaptiveExtension();

    /**
     * 路由自适应工厂
     */
    private static final RouterFactory routerFactory = ExtensionLoader.getExtensionLoader(RouterFactory.class).getAdaptiveExtension();

    /**
     * 配置自适应工厂
     */
    private static final ConfiguratorFactory configuratorFactory = ExtensionLoader.getExtensionLoader(ConfiguratorFactory.class).getAdaptiveExtension();

    /**
     * 注册中心的服务类，目前是 com.alibaba.dubbo.registry.RegistryService
     *
     * 通过 {@link #url} 的 {@link URL#getServiceKey()} 获得
     */
    private final String serviceKey; // Initialization at construction time, assertion not null

    //类似com.alibaba.dubbo.demo.DemoService
    private final Class<T> serviceType; // Initialization at construction time, assertion not null

    //消费方，服务引用配置
    private final Map<String, String> queryMap; // Initialization at construction time, assertion not null

    /**
     * 原始的目录 URL
     *
     * 例如：zookeeper://127.0.0.1:2181/com.alibaba.dubbo.registry.RegistryService?application=demo-consumer&callbacks=1000&check=false&client=netty4&cluster=failback&dubbo=2.0.0&interface=com.alibaba.dubbo.demo.DemoService&methods=sayHello,callbackParam,save,update,say03,delete,say04,demo,say01,bye,say02,saves&payload=1000&pid=63400&qos.port=33333&register.ip=192.168.16.23&sayHello.async=true&side=consumer&timeout=10000&timestamp=1527056491064
     */
    private final URL directoryUrl; // Initialization at construction time, assertion not null, and always assign non null value

    //消费者方法名集合
    private final String[] serviceMethods;

    //是否消费多个组
    private final boolean multiGroup;

    //注册中心的 Protocol 对象
    private Protocol protocol; // Initialization at the time of injection, the assertion is not null

    //注册中心
    private Registry registry; // Initialization at the time of injection, the assertion is not null

    /**
     * 是否禁止访问。
     *
     * 有两种情况会导致：
     *
     * 1. 没有服务提供者
     * 2. 服务提供者被禁用
     */
    private volatile boolean forbidden = false;

    /**
     * 覆写的目录 URL ，结合配置规则
     */
    private volatile URL overrideDirectoryUrl; // Initialization at construction time, assertion not null, and always assign non null value

    /**
     *
     * 配置规则数组
     * override rules
     * Priority: override>-D>consumer>provider
     * Rule one: for a certain provider <ip:port,timeout=100>
     * Rule two: for all providers <* ,timeout=5000>
     */
    private volatile List<Configurator> configurators; // The initial value is null and the midway may be assigned to null, please use the local variable reference

    //缓存了服务地址到提供者
    // Map<url, Invoker> cache service url to invoker mapping.
    private volatile Map<String, Invoker<T>> urlInvokerMap; // The initial value is null and the midway may be assigned to null, please use the local variable reference

    //缓存了方法名到提供者
    // Map<methodName, Invoker> cache service method to invokers mapping.
    private volatile Map<String, List<Invoker<T>>> methodInvokerMap; // The initial value is null and the midway may be assigned to null, please use the local variable reference

    //缓存的提供者Url
    // Set<invokerUrls> cache invokeUrls to invokers mapping.
    private volatile Set<URL> cachedInvokerUrls; // The initial value is null and the midway may be assigned to null, please use the local variable reference

    public RegistryDirectory(Class<T> serviceType, URL url) {
        super(url);
        if (serviceType == null)
            throw new IllegalArgumentException("service type is null.");
        if (url.getServiceKey() == null || url.getServiceKey().length() == 0)
            throw new IllegalArgumentException("registry serviceKey is null.");
        //类似com.alibaba.dubbo.demo.DemoService
        this.serviceType = serviceType;
        //类似com.alibaba.dubbo.registry.RegistryService
        this.serviceKey = url.getServiceKey();
        // 获得 queryMap
        this.queryMap = StringUtils.parseQueryString(url.getParameterAndDecoded(Constants.REFER_KEY));
        this.overrideDirectoryUrl = this.directoryUrl = url.setPath(url.getServiceInterface()).clearParameters().addParameters(queryMap).removeParameter(Constants.MONITOR_KEY);
        String group = directoryUrl.getParameter(Constants.GROUP_KEY, "");
        this.multiGroup = group != null && ("*".equals(group) || group.contains(","));
        //类似sayHello
        String methods = queryMap.get(Constants.METHODS_KEY);
        this.serviceMethods = methods == null ? null : Constants.COMMA_SPLIT_PATTERN.split(methods);
    }

    /**
     * Convert override urls to map for use when re-refer.
     * Send all rules every time, the urls will be reassembled and calculated
     *
     * @param urls Contract:
     *             </br>1.override://0.0.0.0/...( or override://ip:port...?anyhost=true)&para1=value1... means global rules (all of the providers take effect)
     *             //如果指定的覆盖url的ip是0.0.0.0或者其中的参数anyhost=true，那意味着这是一个全局规则，所有的提供者将会受到影响
     *             </br>2.override://ip:port...?anyhost=false Special rules (only for a certain provider)
     *             //如果指定的覆盖url的ip不是0.0.0.0，并且anyhost=false，那么这是一个特殊规则，将只影响部分提供者
     *             </br>3.override:// rule is not supported... ,needs to be calculated by registry itself.
     *             //不支持override://规则... 需要注册中心自行计算.
     *             </br>4.override://0.0.0.0/ without parameters means clearing the override
     *             //如果指定的覆盖url的ip是0.0.0.0，但是没有指定任何参数，那么意味着当前覆盖Url无效
     * @return
     */
    public static List<Configurator> toConfigurators(List<URL> urls) {
        // 忽略，若配置规则 URL 集合为空
        if (urls == null || urls.isEmpty()) {
            return Collections.emptyList();
        }

        // 创建 Configurator 集合
        List<Configurator> configurators = new ArrayList<Configurator>(urls.size());
        for (URL url : urls) {
            // 若协议为 `empty://` ，意味着清空所有配置规则，因此返回空 Configurator 集合
            if (Constants.EMPTY_PROTOCOL.equals(url.getProtocol())) {
                configurators.clear();
                break;
            }
            // 对应第 4 条契约，不带参数的 override://0.0.0.0/ 表示清除 override
            Map<String, String> override = new HashMap<String, String>(url.getParameters());
            //The anyhost parameter of override may be added automatically, it can't change the judgement of changing url
            //anyhost参数可能是被自动添加的，所以它不能作为影响判断url参数的依据，需要移除
            override.remove(Constants.ANYHOST_KEY);
            if (override.size() == 0) {
                //如果当前url没有参数，需要清空已有的覆盖Url
                configurators.clear();
                continue;
            }
            configurators.add(configuratorFactory.getConfigurator(url));
        }

        // 排序
        Collections.sort(configurators);
        return configurators;
    }

    public void setProtocol(Protocol protocol) {
        this.protocol = protocol;
    }

    public void setRegistry(Registry registry) {
        this.registry = registry;
    }

    public void subscribe(URL url) {
        //此处的url是服务消费者URL
        setConsumerUrl(url);
        //此处的registry类型为ZookeeperRegistry，实际调用的是FailbackRegistry的subscribe
        //此处的this类型RegistryDirectory也同样是个NotifyListener
        registry.subscribe(url, this);
    }


    @Override
    public void destroy() {
        if (isDestroyed()) {
            return;
        }
        //先取消订阅
        // unsubscribe.
        try {
            if (getConsumerUrl() != null && registry != null && registry.isAvailable()) {
                registry.unsubscribe(getConsumerUrl(), this);
            }
        } catch (Throwable t) {
            logger.warn("unexpeced error when unsubscribe service " + serviceKey + "from registry" + registry.getUrl(), t);
        }
        //标识已经销毁
        super.destroy(); // must be executed after unsubscribing

        //销毁改服务消费者的所有invoker
        try {
            destroyAllInvokers();
        } catch (Throwable t) {
            logger.warn("Failed to destroy service " + serviceKey, t);
        }
    }

    /**
     * 在注册中心( Registry )发现数据发生变化时，会通知对应的 NotifyListener 们
     * @param urls 已注册信息列表，总不为空，含义同{@link com.alibaba.dubbo.registry.RegistryService#lookup(URL)}的返回值。
     * 注意，是按照分类循环通知的，也就是说，一次只有一类 URL 。
     */
    @Override
    public synchronized void notify(List<URL> urls) {
        // 根据 URL 的分类或协议，分组成三个集合 。
        List<URL> invokerUrls = new ArrayList<URL>();
        List<URL> routerUrls = new ArrayList<URL>();
        List<URL> configuratorUrls = new ArrayList<URL>();
        for (URL url : urls) {
            String protocol = url.getProtocol();
            // 获取 category 参数
            String category = url.getParameter(Constants.CATEGORY_KEY, Constants.DEFAULT_CATEGORY);
            // 根据 category 参数将 url 分别放到不同的列表中
            if (Constants.ROUTERS_CATEGORY.equals(category)
                    || Constants.ROUTE_PROTOCOL.equals(protocol)) {
                // 添加路由器 url
                routerUrls.add(url);
            } else if (Constants.CONFIGURATORS_CATEGORY.equals(category)
                    || Constants.OVERRIDE_PROTOCOL.equals(protocol)) {
                // 添加配置器 url
                configuratorUrls.add(url);
            } else if (Constants.PROVIDERS_CATEGORY.equals(category)) {
                // 添加服务提供者 url
                invokerUrls.add(url);
            } else {
                // 忽略不支持的 category
                logger.warn("Unsupported category " + category + " in notified url: " + url + " from registry " + getUrl().getAddress() + " to consumer " + NetUtils.getLocalHost());
            }
        }

        // 处理配置规则 URL 集合
        // configurators
        if (configuratorUrls != null && !configuratorUrls.isEmpty()) {
            // 将 url 转成 Configurator
            this.configurators = toConfigurators(configuratorUrls);
        }

        // 处理路由规则 URL 集合
        // routers
        if (routerUrls != null && !routerUrls.isEmpty()) {
            // 将 url 转成 Router
            List<Router> routers = toRouters(routerUrls);
            if (routers != null) { // null - do nothing
                setRouters(routers);
            }
        }

        // 合并配置规则，到 `directoryUrl` 中，形成 `overrideDirectoryUrl` 变量。
        List<Configurator> localConfigurators = this.configurators; // local reference
        // merge override parameters
        this.overrideDirectoryUrl = directoryUrl;
        if (localConfigurators != null && !localConfigurators.isEmpty()) {
            for (Configurator configurator : localConfigurators) {
                this.overrideDirectoryUrl = configurator.configure(overrideDirectoryUrl);
            }
        }

        // 刷新 Invoker 列表
        // providers
        refreshInvoker(invokerUrls);
    }

    /**
     * 根据invokerURLs列表转换为invoker列表，转换规则如下
     * 1.如果url已经被转换为invoker，则不在重新引用，直接从缓存中取，注意如果url中任何一个参数变更也会重新引用
     * 2.如果传入的invoker列表不空，则表示最新的invoker列表
     * 3.如果传入的invokerUrl列表是空，则表示只是下发的override规则或route规则，需要重新交叉对比，决定是否需要重新引用
     *
     * @param invokerUrls 传入的参数不能为null
     */
    // TODO: 2017/8/31 FIXME The thread pool should be used to refresh the address, otherwise the task may be accumulated.
    private void refreshInvoker(List<URL> invokerUrls) {
        // invokerUrls 仅有一个元素，且 url 协议头为 empty，此时表示禁用所有服务
        if (invokerUrls != null && invokerUrls.size() == 1 && invokerUrls.get(0) != null
                && Constants.EMPTY_PROTOCOL.equals(invokerUrls.get(0).getProtocol())) {
            // 设置 forbidden 为 true
            this.forbidden = true; // Forbid to access
            this.methodInvokerMap = null; // Set the method invoker map to null
            // 销毁所有 Invoker
            destroyAllInvokers(); // Close all invokers
        } else {
            this.forbidden = false; // Allow to access
            Map<String, Invoker<T>> oldUrlInvokerMap = this.urlInvokerMap; // local reference
            // 传入的 invokerUrls 为空，说明是路由规则或配置规则发生改变，此时 invokerUrls 是空的，直接使用 cachedInvokerUrls 。
            if (invokerUrls.isEmpty() && this.cachedInvokerUrls != null) {
                // 添加缓存 url 到 invokerUrls 中
                invokerUrls.addAll(this.cachedInvokerUrls);
             // 传入的 invokerUrls 非空，更新 cachedInvokerUrls 。
            } else {
                //缓存invokerUrls，方便比较
                this.cachedInvokerUrls = new HashSet<URL>();
                this.cachedInvokerUrls.addAll(invokerUrls);//Cached invoker urls, convenient for comparison
            }

            // 忽略，若无 invokerUrls
            if (invokerUrls.isEmpty()) {
                return;
            }
            //根据提供者的url将其转换为invoker,其中key=dubbo://172.16.10.53:20880/com.alibaba.dubbo.demo.DemoService?anyhost=true
            // &application=demo-consumer&check=false&dubbo=2.0.2&generic=false&interface=com.alibaba.dubbo.demo.DemoService&methods=sayHello
            // &pid=232892&qos.port=33333&register.ip=172.16.10.53&remote.timestamp=1568286676746&side=consumer&timestamp=1568292213076
            //value=InvokerDelegate
            Map<String, Invoker<T>> newUrlInvokerMap = toInvokers(invokerUrls);// Translate url list to Invoker map
            //key=sayHello，value=List<InvokerDelegate>，key=*，value=List<InvokerDelegate>
            // 将 newUrlInvokerMap 转成方法名到 Invoker 列表的映射
            Map<String, List<Invoker<T>>> newMethodInvokerMap = toMethodInvokers(newUrlInvokerMap); // Change method name to map Invoker Map
            // state change
            // If the calculation is wrong, it is not processed.
            // 转换出错，直接打印异常，并返回
            if (newUrlInvokerMap == null || newUrlInvokerMap.size() == 0) {
                logger.error(new IllegalStateException("urls to invokers error .invokerUrls.size :" + invokerUrls.size() + ", invoker.size :0. urls :" + invokerUrls.toString()));
                return;
            }
            // 若服务引用多 group ，则按照 method + group 聚合 Invoker 集合
            this.methodInvokerMap = multiGroup ? toMergeMethodInvokerMap(newMethodInvokerMap) : newMethodInvokerMap;
            this.urlInvokerMap = newUrlInvokerMap;
            try {
                //关闭无效的invoker
                destroyUnusedInvokers(oldUrlInvokerMap, newUrlInvokerMap); // Close the unused Invoker
            } catch (Exception e) {
                logger.warn("destroyUnusedInvokers error. ", e);
            }
        }
    }

    /**
     * 把同一个方法的invokers中的属于相同组的invoker合并成一个MockClusterInvoker
     * @param methodMap
     * @return
     */
    private Map<String, List<Invoker<T>>> toMergeMethodInvokerMap(Map<String, List<Invoker<T>>> methodMap) {
        Map<String, List<Invoker<T>>> result = new HashMap<String, List<Invoker<T>>>();
        // 循环方法，按照 method + group 聚合 Invoker 集合
        for (Map.Entry<String, List<Invoker<T>>> entry : methodMap.entrySet()) {
            String method = entry.getKey();
            List<Invoker<T>> invokers = entry.getValue();
            // 按照 Group 聚合 Invoker 集合的结果。其中，KEY：group VALUE：Invoker 集合。
            Map<String, List<Invoker<T>>> groupMap = new HashMap<String, List<Invoker<T>>>();
            // 循环 Invoker 集合，按照 group 聚合 Invoker 集合
            for (Invoker<T> invoker : invokers) {
                String group = invoker.getUrl().getParameter(Constants.GROUP_KEY, "");
                List<Invoker<T>> groupInvokers = groupMap.get(group);
                if (groupInvokers == null) {
                    groupInvokers = new ArrayList<Invoker<T>>();
                    // 缓存 <group, List<Invoker>> 到 groupMap 中
                    groupMap.put(group, groupInvokers);
                }
                // 存储 invoker 到 groupInvokers
                groupInvokers.add(invoker);
            }
            // 大小为 1，使用第一个
            if (groupMap.size() == 1) {
                // 如果 groupMap 中仅包含一组键值对，此时直接取出该键值对的值即可
                result.put(method, groupMap.values().iterator().next());
            } else if (groupMap.size() > 1) {
                List<Invoker<T>> groupInvokers = new ArrayList<Invoker<T>>();
                for (List<Invoker<T>> groupList : groupMap.values()) {
                    //此处的cluster的真实类型是clusterAdaptive，然后真实调用的是MockClusterWrapper
                    // 通过集群类合并每个分组对应的 Invoker 列表
                    groupInvokers.add(cluster.join(new StaticDirectory<T>(groupList)));
                }
                // 缓存结果
                result.put(method, groupInvokers);
            } else {
                result.put(method, invokers);
            }
        }
        return result;
    }

    /**
     * @param urls
     * @return null : no routers ,do nothing
     * else :routers list
     */
    private List<Router> toRouters(List<URL> urls) {
        List<Router> routers = new ArrayList<Router>();
        if (urls == null || urls.isEmpty()) {
            return routers;
        }
        if (urls != null && !urls.isEmpty()) {
            for (URL url : urls) {
                if (Constants.EMPTY_PROTOCOL.equals(url.getProtocol())) {
                    //忽略空协议
                    continue;
                }
                //获取路由类型
                // key for router type, for e.g., "script"/"file",  corresponding to ScriptRouterFactory.NAME, FileRouterFactory.NAME
                String routerType = url.getParameter(Constants.ROUTER_KEY);
                if (routerType != null && routerType.length() > 0) {
                    url = url.setProtocol(routerType);
                }
                try {
                    //此处的routerFactory的类型是RouterFactory$Adaptive
                    Router router = routerFactory.getRouter(url);
                    if (!routers.contains(router))
                        routers.add(router);
                } catch (Throwable t) {
                    logger.error("convert router url to router error, url: " + url, t);
                }
            }
        }
        return routers;
    }

    /**
     * Turn urls into invokers, and if url has been refer, will not re-reference.
     * 返回url到invoker的关系，如果已经是之前引用过的，那么不用重新引用
     * @param urls
     * @return invokers
     */
    private Map<String, Invoker<T>> toInvokers(List<URL> urls) {
        // 新的 `newUrlInvokerMap`
        Map<String, Invoker<T>> newUrlInvokerMap = new HashMap<String, Invoker<T>>();
        // 若为空，直接返回
        if (urls == null || urls.isEmpty()) {
            return newUrlInvokerMap;
        }
        // 已初始化的服务器提供 URL 集合
        Set<String> keys = new HashSet<String>();
        // 获取服务消费端配置的协议
        String queryProtocols = this.queryMap.get(Constants.PROTOCOL_KEY);
        for (URL providerUrl : urls) {
            // If protocol is configured at the reference side, only the matching protocol is selected
            // 过滤掉消费者不支持的协议，比如，消费者可能只支持dubbo协议，但是从注册中心却获取到包含hession的url
            if (queryProtocols != null && queryProtocols.length() > 0) {
                boolean accept = false;
                String[] acceptProtocols = queryProtocols.split(",");
                for (String acceptProtocol : acceptProtocols) {
                    if (providerUrl.getProtocol().equals(acceptProtocol)) {
                        accept = true;
                        break;
                    }
                }
                if (!accept) {
                    // 若服务提供者协议头不被消费者所支持，则忽略当前 providerUrl
                    continue;
                }
            }

            //忽略协议为空的url
            if (Constants.EMPTY_PROTOCOL.equals(providerUrl.getProtocol())) {
                continue;
            }

            //校验客户端是否支持该协议
            if (!ExtensionLoader.getExtensionLoader(Protocol.class).hasExtension(providerUrl.getProtocol())) {
                logger.error(new IllegalStateException("Unsupported protocol " + providerUrl.getProtocol() + " in notified url: " + providerUrl + " from registry " + getUrl().getAddress() + " to consumer " + NetUtils.getLocalHost()
                        + ", supported protocol: " + ExtensionLoader.getExtensionLoader(Protocol.class).getSupportedExtensions()));
                continue;
            }
            // 合并 URL 参数
            URL url = mergeUrl(providerUrl);

            // 忽略，若已经初始化
            //dubbo://172.16.10.53:20880/com.alibaba.dubbo.demo.DemoService?anyhost=true&application=demo-consumer&
            // check=false&dubbo=2.0.2&generic=false&interface=com.alibaba.dubbo.demo.DemoService&methods=sayHello&monitor=dubbo%3A%2F%2F127.0.0.1%3A2181%2Fcom.alibaba.dubbo.registry.RegistryService%3Fapplication%3Ddemo-consumer%26dubbo%3D2.0.2%26pid%3D74640%26protocol%3Dregistry%26qos.port%3D33333%26refer%3Dapplication%253Ddemo-consumer%2526dubbo%253D2.0.2%2526interface%253Dcom.alibaba.dubbo.monitor.MonitorService%2526pid%253D74640%2526qos.port%253D33333%2526register.ip%253D172.16.10.53%2526timestamp%253D1594125486794%26registry%3Dzookeeper%26timestamp%3D1594125486731&pid=74640&qos.port=33333&register.ip=172.16.10.53&remote.timestamp=1594125403713&side=consumer&timestamp=1594125486595
            String key = url.toFullString(); // The parameter urls are sorted
            if (keys.contains(key)) { // Repeated url
                // 忽略重复 url
                continue;
            }

            // 添加到 `keys` 中
            keys.add(key);
            // Cache key is url that does not merge with consumer side parameters, regardless of how the consumer combines parameters, if the server url changes, then refer again
            //如果之前已经创建的invoker,则不用重新引用，否则需要需要引用，因为有些提供者有可能是后来才添加的
            Map<String, Invoker<T>> localUrlInvokerMap = this.urlInvokerMap; // local reference
            Invoker<T> invoker = localUrlInvokerMap == null ? null : localUrlInvokerMap.get(key);
            if (invoker == null) { // Not in the cache, refer again
                //缓存未命中，重新引用
                try {
                    // 判断是否开启
                    boolean enabled = true;
                    if (url.hasParameter(Constants.DISABLED_KEY)) {
                        // 获取 disable 配置，取反，然后赋值给 enable 变量
                        enabled = !url.getParameter(Constants.DISABLED_KEY, false);
                    } else {
                        // 获取 enable 配置，并赋值给 enable 变量
                        enabled = url.getParameter(Constants.ENABLED_KEY, true);
                    }
                    // 若开启，创建 Invoker 对象
                    if (enabled) {
                        //此处protocol的真实类型是ProtocolAdaptive，然后调用的QosProtocolWrapper，什么也不做，然后调用protocolListenerWrapper，
                        // 然后调用ProtocolFilterWrapper，最后调用的是DubboProtocol的refer，返回DubboInvoker后，在protocolFilterWrapper生成过滤器Invoker，
                        // 然后在ProtocolListenerWrapper触发服务导出通知，然后把过滤器Invoker包装为ListenerInvokerWrapper，然后返回，所以此处的Invoker的真实类型是ListenerInvokerWrapper
                        invoker = new InvokerDelegate<T>(protocol.refer(serviceType, url), url, providerUrl);
                    }
                } catch (Throwable t) {
                    logger.error("Failed to refer invoker for interface:" + serviceType + ",url:(" + url + ")" + t.getMessage(), t);
                }

                // 添加到 newUrlInvokerMap 中
                if (invoker != null) { // Put new invoker in cache
                    //添加新的Invoker到缓存
                    newUrlInvokerMap.put(key, invoker);
                }
            } else {
                // 将 invoker 存储到 newUrlInvokerMap 中
                newUrlInvokerMap.put(key, invoker);
            }
        }

        // 清空 keys
        keys.clear();
        return newUrlInvokerMap;
    }

    /**
     * Merge url parameters. the order is: override > -D >Consumer > Provider
     * 优先级为配置规则 > 服务消费者配置 > 服务提供者配置
     * @param providerUrl
     * @return
     */
    private URL mergeUrl(URL providerUrl) {
        //合并一些消费者侧的配置信息
        providerUrl = ClusterUtils.mergeUrl(providerUrl, queryMap); // Merge the consumer side parameters

        // 合并配置规则
        List<Configurator> localConfigurators = this.configurators; // local reference
        if (localConfigurators != null && !localConfigurators.isEmpty()) {
            for (Configurator configurator : localConfigurators) {
                providerUrl = configurator.configure(providerUrl);
            }
        }

        // 不检查连接是否成功，总是创建 Invoker ！
        providerUrl = providerUrl.addParameter(Constants.CHECK_KEY, String.valueOf(false)); // Do not check whether the connection is successful or not, always create Invoker!

        // The combination of directoryUrl and override is at the end of notify, which can't be handled here
        // 仅合并提供者参数，因为 directoryUrl 与 override 合并是在 notify 的最后，这里不能够处理
        this.overrideDirectoryUrl = this.overrideDirectoryUrl.addParametersIfAbsent(providerUrl.getParameters()); // Merge the provider side parameters

        // 【忽略】因为是对 1.0 版本的兼容
        if ((providerUrl.getPath() == null || providerUrl.getPath().length() == 0)
                && "dubbo".equals(providerUrl.getProtocol())) { // Compatible version 1.0
            //fix by tony.chenl DUBBO-44
            String path = directoryUrl.getParameter(Constants.INTERFACE_KEY);
            if (path != null) {
                int i = path.indexOf('/');
                if (i >= 0) {
                    path = path.substring(i + 1);
                }
                i = path.lastIndexOf(':');
                if (i >= 0) {
                    path = path.substring(0, i);
                }
                providerUrl = providerUrl.setPath(path);
            }
        }
        return providerUrl;
    }

    private List<Invoker<T>> route(List<Invoker<T>> invokers, String method) {
        Invocation invocation = new RpcInvocation(method, new Class<?>[0], new Object[0]);
        List<Router> routers = getRouters();
        if (routers != null) {
            for (Router router : routers) {
                if (router.getUrl() != null) {
                    invokers = router.route(invokers, getConsumerUrl(), invocation);
                }
            }
        }
        return invokers;
    }

    /**
     * Transform the invokers list into a mapping relationship with a method
     * 转换方法名到执行者invoker列表，并且根据路由规则过滤invoker
     * @param invokersMap Invoker Map
     * @return Mapping relation between Invoker and method
     */
    private Map<String, List<Invoker<T>>> toMethodInvokers(Map<String, Invoker<T>> invokersMap) {
        // 方法名 -> Invoker 列表
        Map<String, List<Invoker<T>>> newMethodInvokerMap = new HashMap<String, List<Invoker<T>>>();
        // According to the methods classification declared by the provider URL, the methods is compatible with the registry to execute the filtered methods
        // 创建 Invoker 集合
        List<Invoker<T>> invokersList = new ArrayList<Invoker<T>>();
        // 按服务提供者 URL 所声明的 methods 分类，兼容注册中心执行路由过滤掉的 methods
        if (invokersMap != null && invokersMap.size() > 0) {
            for (Invoker<T> invoker : invokersMap.values()) {
                // 获取 methods 参数
                String parameter = invoker.getUrl().getParameter(Constants.METHODS_KEY);
                if (parameter != null && parameter.length() > 0) {
                    // 切分 methods 参数值，得到方法名数组
                    String[] methods = Constants.COMMA_SPLIT_PATTERN.split(parameter);
                    if (methods != null && methods.length > 0) {
                        // 循环每个方法，按照方法名为维度，聚合到 `methodInvokerMap` 中
                        for (String method : methods) {
                            // 方法名不为 *
                            if (method != null && method.length() > 0
                                    && !Constants.ANY_VALUE.equals(method)) {
                                // 根据方法名获取 Invoker 列表
                                List<Invoker<T>> methodInvokers = newMethodInvokerMap.get(method);
                                if (methodInvokers == null) {
                                    methodInvokers = new ArrayList<Invoker<T>>();
                                    newMethodInvokerMap.put(method, methodInvokers);
                                }
                                // 存储 Invoker 到列表中
                                methodInvokers.add(invoker);
                            }
                        }
                    }
                }
                // 添加到 `invokersList` 中
                invokersList.add(invoker);
            }
        }
        // 进行服务级别路由，参考 pull request #749
        List<Invoker<T>> newInvokersList = route(invokersList, null);
        // 存储 <*, newInvokersList> 映射关系
        // 添加 `newInvokersList` 到 `newMethodInvokerMap` 中，表示该服务提供者的全量 Invoker 集合
        newMethodInvokerMap.put(Constants.ANY_VALUE, newInvokersList);
        // 循环，基于每个方法路由，匹配合适的 Invoker 集合
        if (serviceMethods != null && serviceMethods.length > 0) {
            for (String method : serviceMethods) {
                List<Invoker<T>> methodInvokers = newMethodInvokerMap.get(method);
                if (methodInvokers == null || methodInvokers.isEmpty()) {
                    methodInvokers = newInvokersList;
                }
                // 进行方法级别路由
                newMethodInvokerMap.put(method, route(methodInvokers, method));
            }
        }
        // 排序，转成不可变列表
        // sort and unmodifiable
        for (String method : new HashSet<String>(newMethodInvokerMap.keySet())) {
            List<Invoker<T>> methodInvokers = newMethodInvokerMap.get(method);
            Collections.sort(methodInvokers, InvokerComparator.getComparator());
            newMethodInvokerMap.put(method, Collections.unmodifiableList(methodInvokers));
        }
        return Collections.unmodifiableMap(newMethodInvokerMap);
    }

    /**
     * Close all invokers
     */
    private void destroyAllInvokers() {
        Map<String, Invoker<T>> localUrlInvokerMap = this.urlInvokerMap; // local reference
        if (localUrlInvokerMap != null) {
            for (Invoker<T> invoker : new ArrayList<Invoker<T>>(localUrlInvokerMap.values())) {
                try {
                    invoker.destroy();
                } catch (Throwable t) {
                    logger.warn("Failed to destroy service " + serviceKey + " to provider " + invoker.getUrl(), t);
                }
            }
            localUrlInvokerMap.clear();
        }
        methodInvokerMap = null;
    }

    /**
     * Check whether the invoker in the cache needs to be destroyed
     * If set attribute of url: refer.autodestroy=false, the invokers will only increase without decreasing,there may be a refer leak
     * 校验本地缓存中的invoker是否应该被销毁，
     * @param oldUrlInvokerMap
     * @param newUrlInvokerMap
     */
    private void destroyUnusedInvokers(Map<String, Invoker<T>> oldUrlInvokerMap, Map<String, Invoker<T>> newUrlInvokerMap) {
        if (newUrlInvokerMap == null || newUrlInvokerMap.size() == 0) {
            //如果新获取到的url到invoker关系为空，那么销毁之前所有的本地invoker
            destroyAllInvokers();
            return;
        }
        // check deleted invoker
        //统计在本地缓存中有的invoker，但是在最新获取列表中没有的invoker
        List<String> deleted = null;
        if (oldUrlInvokerMap != null) {
            // 获取新生成的 Invoker 列表
            Collection<Invoker<T>> newInvokers = newUrlInvokerMap.values();
            // 遍历老的 <url, Invoker> 映射表
            for (Map.Entry<String, Invoker<T>> entry : oldUrlInvokerMap.entrySet()) {
                // 检测 newInvokers 中是否包含老的 Invoker
                if (!newInvokers.contains(entry.getValue())) {
                    if (deleted == null) {
                        deleted = new ArrayList<String>();
                    }
                    // 若不包含，则将老的 Invoker 对应的 url 存入 deleted 列表中
                    deleted.add(entry.getKey());
                }
            }
        }

        //如果存在这样的invoker，这全部销毁
        if (deleted != null) {
            // 遍历 deleted 集合，并到老的 <url, Invoker> 映射关系表查出 Invoker，销毁之
            for (String url : deleted) {
                if (url != null) {
                    // 从 oldUrlInvokerMap 中移除 url 对应的 Invoker
                    Invoker<T> invoker = oldUrlInvokerMap.remove(url);
                    if (invoker != null) {
                        try {
                            // 销毁 Invoker
                            invoker.destroy();
                            if (logger.isDebugEnabled()) {
                                logger.debug("destroy invoker[" + invoker.getUrl() + "] success. ");
                            }
                        } catch (Exception e) {
                            logger.warn("destroy invoker[" + invoker.getUrl() + "] faild. " + e.getMessage(), e);
                        }
                    }
                }
            }
        }
    }

    @Override
    public List<Invoker<T>> doList(Invocation invocation) {
        if (forbidden) {
            // 1. No service provider 2. Service providers are disabled
            //1.服务被禁用了，2.提供者不可用，都会抛出异常
            throw new RpcException(RpcException.FORBIDDEN_EXCEPTION,
                "No provider available from registry " + getUrl().getAddress() + " for service " + getConsumerUrl().getServiceKey() + " on consumer " +  NetUtils.getLocalHost()
                        + " use dubbo version " + Version.getVersion() + ", please check status of providers(disabled, not registered or in blacklist).");
        }
        List<Invoker<T>> invokers = null;
        // 获取 Invoker 本地缓存
        Map<String, List<Invoker<T>>> localMethodInvokerMap = this.methodInvokerMap; // local reference
        if (localMethodInvokerMap != null && localMethodInvokerMap.size() > 0) {
            // 获取方法名和参数列表
            String methodName = RpcUtils.getMethodName(invocation);
            Object[] args = RpcUtils.getArguments(invocation);
            // 检测参数列表的第一个参数是否为 String 或 enum 类型
            if (args != null && args.length > 0 && args[0] != null
                    && (args[0] instanceof String || args[0].getClass().isEnum())) {
                //可能根据第一个参数来路由
                // The routing can be enumerated according to the first parameter
                invokers = localMethodInvokerMap.get(methodName + "." + args[0]);
            }
            if (invokers == null) {
                // 通过方法名获取 Invoker 列表
                invokers = localMethodInvokerMap.get(methodName);
            }
            if (invokers == null) {
                // 通过星号 * 获取 Invoker 列表
                // 【第三】使用全量 Invoker 集合。例如，`#$echo(name)` ，回声方法
                invokers = localMethodInvokerMap.get(Constants.ANY_VALUE);
            }
            // 冗余逻辑，pull request #2861 移除了下面的 if 分支代码
            // 【第四】使用 `methodInvokerMap` 第一个 Invoker 集合。防御性编程。
            if (invokers == null) {
                Iterator<List<Invoker<T>>> iterator = localMethodInvokerMap.values().iterator();
                if (iterator.hasNext()) {
                    invokers = iterator.next();
                }
            }
        }
        // 返回 Invoker 列表
        return invokers == null ? new ArrayList<Invoker<T>>(0) : invokers;
    }

    @Override
    public Class<T> getInterface() {
        return serviceType;
    }

    @Override
    public URL getUrl() {
        return this.overrideDirectoryUrl;
    }

    @Override
    public boolean isAvailable() {
        // 若已销毁，返回不可用
        if (isDestroyed()) {
            return false;
        }
        Map<String, Invoker<T>> localUrlInvokerMap = urlInvokerMap;
        /**
         * key=dubbo://172.16.10.53:20880/com.alibaba.dubbo.demo.DemoService?anyhost=true&application=demo-consumer&check=false&dubbo=2.0.2
         * &generic=false&interface=com.alibaba.dubbo.demo.DemoService&methods=sayHello&monitor=dubbo%3A%2F%2F127.0.0.1%3A2181%2F
         * com.alibaba.dubbo.registry.RegistryService%3Fapplication%3Ddemo-consumer%26dubbo%3D2.0.2%26pid%3D27528%26protocol%3Dregistry
         * %26qos.port%3D33333%26refer%3Dapplication%253Ddemo-consumer%2526dubbo%253D2.0.2%2526interface%253Dcom.alibaba.dubbo.monitor.
         * MonitorService%2526pid%253D27528%2526qos.port%253D33333%2526register.ip%253D172.16.10.53%2526timestamp%253D1579011845640%26registry
         * %3Dzookeeper%26timestamp%3D1579011845572&pid=27528&qos.port=33333&register.ip=172.16.10.53&remote.timestamp=1578996226466&side=consumer&timestamp=1579011845437
         * value=InvokerDelegate
         *
         * key=dubbo://172.16.10.53:20880/com.alibaba.dubbo.demo.DemoService?anyhost=true&application=demo-consumer&check=false&dubbo=2.0.2
         * &generic=false&interface=com.alibaba.dubbo.demo.DemoService&methods=sayHello&monitor=dubbo%3A%2F%2F127.0.0.1%3A2181%2F
         * com.alibaba.dubbo.registry.RegistryService%3Fapplication%3Ddemo-consumer%26dubbo%3D2.0.2%26pid%3D27528%26protocol%3Dregistry
         * %26qos.port%3D33333%26refer%3Dapplication%253Ddemo-consumer%2526dubbo%253D2.0.2%2526interface%253Dcom.alibaba.dubbo.monitor.
         * MonitorService%2526pid%253D27528%2526qos.port%253D33333%2526register.ip%253D172.16.10.53%2526timestamp%253D1579011845640%26registry
         * %3Dzookeeper%26timestamp%3D1579011845572&pid=27528&qos.port=33333&register.ip=172.16.10.53&remote.timestamp=1578996228257&side=consumer&timestamp=1579011845437
         * value=InvokerDelegate
         */
        // 任意一个 Invoker 可用，则返回可用
        if (localUrlInvokerMap != null && localUrlInvokerMap.size() > 0) {
            for (Invoker<T> invoker : new ArrayList<Invoker<T>>(localUrlInvokerMap.values())) {
                if (invoker.isAvailable()) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Haomin: added for test purpose
     */
    public Map<String, Invoker<T>> getUrlInvokerMap() {
        return urlInvokerMap;
    }

    /**
     * Haomin: added for test purpose
     */
    public Map<String, List<Invoker<T>>> getMethodInvokerMap() {
        return methodInvokerMap;
    }

    /**
     * 实现 Comparator 接口，Invoker 排序器实现类，根据 URL 升序
     */
    private static class InvokerComparator implements Comparator<Invoker<?>> {

        /**
         * 单例
         */
        private static final InvokerComparator comparator = new InvokerComparator();

        private InvokerComparator() {
        }

        public static InvokerComparator getComparator() {
            return comparator;
        }

        @Override
        public int compare(Invoker<?> o1, Invoker<?> o2) {
            return o1.getUrl().toString().compareTo(o2.getUrl().toString());
        }

    }

    /**
     * The delegate class, which is mainly used to store the URL address sent by the registry,and can be reassembled on the basis of providerURL queryMap overrideMap for re-refer.
     * Invoker 代理类，主要用于存储注册中心下发的 url 地址( providerUrl )，用于重新重新 refer 时能够根据 providerURL queryMap overrideMap 重新组装
     * @param <T>
     */
    private static class InvokerDelegate<T> extends InvokerWrapper<T> {

        /**
         * 服务提供者 URL
         *
         * 未经过配置合并
         */
        private URL providerUrl;

        public InvokerDelegate(Invoker<T> invoker, URL url, URL providerUrl) {
            super(invoker, url);
            this.providerUrl = providerUrl;
        }

        public URL getProviderUrl() {
            return providerUrl;
        }
    }
}
