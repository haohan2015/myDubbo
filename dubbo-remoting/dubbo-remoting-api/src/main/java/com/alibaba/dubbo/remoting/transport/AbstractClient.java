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
package com.alibaba.dubbo.remoting.transport;

import com.alibaba.dubbo.common.Constants;
import com.alibaba.dubbo.common.URL;
import com.alibaba.dubbo.common.Version;
import com.alibaba.dubbo.common.extension.ExtensionLoader;
import com.alibaba.dubbo.common.logger.Logger;
import com.alibaba.dubbo.common.logger.LoggerFactory;
import com.alibaba.dubbo.common.store.DataStore;
import com.alibaba.dubbo.common.utils.ExecutorUtil;
import com.alibaba.dubbo.common.utils.NamedThreadFactory;
import com.alibaba.dubbo.common.utils.NetUtils;
import com.alibaba.dubbo.remoting.Channel;
import com.alibaba.dubbo.remoting.ChannelHandler;
import com.alibaba.dubbo.remoting.Client;
import com.alibaba.dubbo.remoting.RemotingException;
import com.alibaba.dubbo.remoting.transport.dispatcher.ChannelHandlers;

import java.net.InetSocketAddress;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * AbstractClient
 */
public abstract class AbstractClient extends AbstractEndpoint implements Client {

    protected static final String CLIENT_THREAD_POOL_NAME = "DubboClientHandler";
    private static final Logger logger = LoggerFactory.getLogger(AbstractClient.class);
    private static final AtomicInteger CLIENT_THREAD_POOL_ID = new AtomicInteger();

    /**
     * 重连定时任务执行器，在客户端连接服务端时，会创建后台任务，定时检查连接，若断开，会进行重连。
     */
    private static final ScheduledThreadPoolExecutor reconnectExecutorService = new ScheduledThreadPoolExecutor(2, new NamedThreadFactory("DubboClientReconnectTimer", true));

    private final Lock connectLock = new ReentrantLock();

    /**
     * 发送消息时，若断开，是否重连
     */
    private final boolean send_reconnect;

    /**
     * 重连次数
     */
    private final AtomicInteger reconnect_count = new AtomicInteger(0);

    /**
     * 重连时，是否已经打印过错误日志。
     */
    // Reconnection error log has been called before?
    private final AtomicBoolean reconnect_error_log_flag = new AtomicBoolean(false);

    /**
     * 重连警告周期，多少次重连之后打印一次警告日志
     */
    // reconnect warning period. Reconnect warning interval (log warning after how many times) //for test
    private final int reconnect_warning_period;

    /**
     * 关闭超时时间
     */
    private final long shutdown_timeout;

    /**
     * 线程池
     */
    protected volatile ExecutorService executor;

    /**
     * 重连执行任务 Future
     */
    private volatile ScheduledFuture<?> reconnectExecutorFuture = null;

    /**
     * 最后成功连接时间
     */
    // the last successed connected time
    private long lastConnectedTime = System.currentTimeMillis();


    public AbstractClient(URL url, ChannelHandler handler) throws RemotingException {
        //此处的handler的真实类型是MultiMessageHandler
        super(url, handler);

        // 从 URL 中，获得重连相关配置项
        send_reconnect = url.getParameter(Constants.SEND_RECONNECT_KEY, false);

        shutdown_timeout = url.getParameter(Constants.SHUTDOWN_TIMEOUT_KEY, Constants.DEFAULT_SHUTDOWN_TIMEOUT);

        // The default reconnection interval is 2s, 1800 means warning interval is 1 hour.
        reconnect_warning_period = url.getParameter("reconnect.waring.period", 1800);

        // 初始化客户端
        try {
            doOpen();
        } catch (Throwable t) {
            // 失败，则关闭
            close();
            throw new RemotingException(url.toInetSocketAddress(), null,
                    "Failed to start " + getClass().getSimpleName() + " " + NetUtils.getLocalAddress()
                            + " connect to the server " + getRemoteAddress() + ", cause: " + t.getMessage(), t);
        }

        // 连接服务器
        try {
            // connect.
            connect();
            if (logger.isInfoEnabled()) {
                logger.info("Start " + getClass().getSimpleName() + " " + NetUtils.getLocalAddress() + " connect to the server " + getRemoteAddress());
            }
        } catch (RemotingException t) {
            if (url.getParameter(Constants.CHECK_KEY, true)) {
                // 失败，则关闭
                close();
                throw t;
            } else {
                logger.warn("Failed to start " + getClass().getSimpleName() + " " + NetUtils.getLocalAddress()
                        + " connect to the server " + getRemoteAddress() + " (check == false, ignore and retry later!), cause: " + t.getMessage(), t);
            }
        } catch (Throwable t) {
            // 失败，则关闭
            close();
            throw new RemotingException(url.toInetSocketAddress(), null,
                    "Failed to start " + getClass().getSimpleName() + " " + NetUtils.getLocalAddress()
                            + " connect to the server " + getRemoteAddress() + ", cause: " + t.getMessage(), t);
        }

        //获取线程池，并且移除SimpleDataStore中存储的线程池，该线程池是在WrappedChannelHandler的构造函数中放入的
        executor = (ExecutorService) ExtensionLoader.getExtensionLoader(DataStore.class)
                .getDefaultExtension().get(Constants.CONSUMER_SIDE, Integer.toString(url.getPort()));
        ExtensionLoader.getExtensionLoader(DataStore.class)
                .getDefaultExtension().remove(Constants.CONSUMER_SIDE, Integer.toString(url.getPort()));
    }

    /**
     * 包装通道处理器
     * @param url
     * @param handler
     * @return
     */
    protected static ChannelHandler wrapChannelHandler(URL url, ChannelHandler handler) {
        //此处的handler 是DecodeHandler
        // 设置线程名
        url = ExecutorUtil.setThreadName(url, CLIENT_THREAD_POOL_NAME);
        // 设置使用的线程池类型，客户端默认是用的cached类型，这个和Server是不同的
        url = url.addParameterIfAbsent(Constants.THREADPOOL_KEY, Constants.DEFAULT_CLIENT_THREADPOOL);
        // 包装通道处理器
        return ChannelHandlers.wrap(handler, url);
    }

    /**
     * @param url
     * @return 0-false
     */
    private static int getReconnectParam(URL url) {
        int reconnect;
        //从url获取重连的频率
        String param = url.getParameter(Constants.RECONNECT_KEY);
        if (param == null || param.length() == 0 || "true".equalsIgnoreCase(param)) {
            //如果没有配置，或者是指定默认，那就用默认值
            reconnect = Constants.DEFAULT_RECONNECT_PERIOD;
        } else if ("false".equalsIgnoreCase(param)) {
            //如果是false那就是代表不需要重连
            reconnect = 0;
        } else {
            //否则初始化配置的重连次数
            try {
                reconnect = Integer.parseInt(param);
            } catch (Exception e) {
                throw new IllegalArgumentException("reconnect param must be nonnegative integer or false/true. input is:" + param);
            }
            if (reconnect < 0) {
                throw new IllegalArgumentException("reconnect param must be nonnegative integer or false/true. input is:" + param);
            }
        }
        return reconnect;
    }

    /**
     * init reconnect thread
     */
    private synchronized void initConnectStatusCheckCommand() {
        //reconnect=false to close reconnect
        int reconnect = getReconnectParam(getUrl());
        //如果开启重连，并且没有正在重连或者之前的重连被取消了
        if (reconnect > 0 && (reconnectExecutorFuture == null || reconnectExecutorFuture.isCancelled())) {
            Runnable connectStatusCheckCommand = new Runnable() {
                @Override
                public void run() {
                    try {
                        // 未连接，重连
                        if (!isConnected()) {
                            connect();
                        // 已连接，记录最后连接时间
                        } else {
                            lastConnectedTime = System.currentTimeMillis();
                        }
                    } catch (Throwable t) {
                        // 超过一定时间未连接上，才打印异常日志。并且，仅打印一次。默认，15 分钟。
                        String errorMsg = "client reconnect to " + getUrl().getAddress() + " find error . url: " + getUrl();
                        // wait registry sync provider list
                        if (System.currentTimeMillis() - lastConnectedTime > shutdown_timeout) {
                            if (!reconnect_error_log_flag.get()) {
                                reconnect_error_log_flag.set(true);
                                logger.error(errorMsg, t);
                                return;
                            }
                        }
                        // 每一定次发现未重连，才打印告警日志。默认，1800 次，1 小时。
                        if (reconnect_count.getAndIncrement() % reconnect_warning_period == 0) {
                            logger.warn(errorMsg, t);
                        }
                    }
                }
            };
            // 发起定时任务
            reconnectExecutorFuture = reconnectExecutorService.scheduleWithFixedDelay(connectStatusCheckCommand, reconnect, reconnect, TimeUnit.MILLISECONDS);
        }
    }

    private synchronized void destroyConnectStatusCheckCommand() {
        try {
            if (reconnectExecutorFuture != null && !reconnectExecutorFuture.isDone()) {
                reconnectExecutorFuture.cancel(true);
                reconnectExecutorService.purge();
            }
        } catch (Throwable e) {
            logger.warn(e.getMessage(), e);
        }
    }

    protected ExecutorService createExecutor() {
        return Executors.newCachedThreadPool(new NamedThreadFactory(CLIENT_THREAD_POOL_NAME + CLIENT_THREAD_POOL_ID.incrementAndGet() + "-" + getUrl().getAddress(), true));
    }

    public InetSocketAddress getConnectAddress() {
        return new InetSocketAddress(NetUtils.filterLocalHost(getUrl().getHost()), getUrl().getPort());
    }

    @Override
    public InetSocketAddress getRemoteAddress() {
        Channel channel = getChannel();
        if (channel == null)
            return getUrl().toInetSocketAddress();
        return channel.getRemoteAddress();
    }

    @Override
    public InetSocketAddress getLocalAddress() {
        Channel channel = getChannel();
        if (channel == null)
            return InetSocketAddress.createUnresolved(NetUtils.getLocalHost(), 0);
        return channel.getLocalAddress();
    }

    @Override
    public boolean isConnected() {
        Channel channel = getChannel();
        if (channel == null)
            return false;
        return channel.isConnected();
    }

    @Override
    public Object getAttribute(String key) {
        Channel channel = getChannel();
        if (channel == null)
            return null;
        return channel.getAttribute(key);
    }

    @Override
    public void setAttribute(String key, Object value) {
        Channel channel = getChannel();
        if (channel == null)
            return;
        channel.setAttribute(key, value);
    }

    @Override
    public void removeAttribute(String key) {
        Channel channel = getChannel();
        if (channel == null)
            return;
        channel.removeAttribute(key);
    }

    @Override
    public boolean hasAttribute(String key) {
        Channel channel = getChannel();
        if (channel == null)
            return false;
        return channel.hasAttribute(key);
    }

    @Override
    public void send(Object message, boolean sent) throws RemotingException {
        //如果是发送时重连并且是未连接的，需要先发起连接
        if (send_reconnect && !isConnected()) {
            connect();
        }
        // 获取 Channel，getChannel 是一个抽象方法，具体由子类实现
        Channel channel = getChannel();
        //TODO Can the value returned by getChannel() be null? need improvement.
        if (channel == null || !channel.isConnected()) {
            throw new RemotingException(this, "message can not send, because channel is closed . url:" + getUrl());
        }
        // 继续向下调用，此处的channel的真实类型是NettyChannel
        channel.send(message, sent);
    }

    /**
     * 连接锁，用于实现发起连接和断开连接互斥，避免并发。
     */
    protected void connect() throws RemotingException {
        // 获得锁
        connectLock.lock();
        try {
            // 已连接
            if (isConnected()) {
                return;
            }
            // 初始化重连线程
            initConnectStatusCheckCommand();

            // 执行连接
            doConnect();

            // 连接失败，抛出异常
            if (!isConnected()) {
                throw new RemotingException(this, "Failed connect to server " + getRemoteAddress() + " from " + getClass().getSimpleName() + " "
                        + NetUtils.getLocalHost() + " using dubbo version " + Version.getVersion()
                        + ", cause: Connect wait timeout: " + getTimeout() + "ms.");

                // 连接成功，打印日志
            } else {
                if (logger.isInfoEnabled()) {
                    logger.info("Successed connect to server " + getRemoteAddress() + " from " + getClass().getSimpleName() + " "
                            + NetUtils.getLocalHost() + " using dubbo version " + Version.getVersion()
                            + ", channel is " + this.getChannel());
                }
            }
            // 设置重连次数归零
            reconnect_count.set(0);
            // 设置未打印过错误日志
            reconnect_error_log_flag.set(false);
        } catch (RemotingException e) {
            throw e;
        } catch (Throwable e) {
            throw new RemotingException(this, "Failed connect to server " + getRemoteAddress() + " from " + getClass().getSimpleName() + " "
                    + NetUtils.getLocalHost() + " using dubbo version " + Version.getVersion()
                    + ", cause: " + e.getMessage(), e);
        } finally {
            connectLock.unlock();
        }
    }

    public void disconnect() {
        connectLock.lock();
        try {
            destroyConnectStatusCheckCommand();
            try {
                Channel channel = getChannel();
                if (channel != null) {
                    channel.close();
                }
            } catch (Throwable e) {
                logger.warn(e.getMessage(), e);
            }
            try {
                doDisConnect();
            } catch (Throwable e) {
                logger.warn(e.getMessage(), e);
            }
        } finally {
            connectLock.unlock();
        }
    }

    @Override
    public void reconnect() throws RemotingException {
        disconnect();
        connect();
    }

    @Override
    public void close() {
        try {
            if (executor != null) {
                ExecutorUtil.shutdownNow(executor, 100);
            }
        } catch (Throwable e) {
            logger.warn(e.getMessage(), e);
        }
        try {
            super.close();
        } catch (Throwable e) {
            logger.warn(e.getMessage(), e);
        }
        try {
            disconnect();
        } catch (Throwable e) {
            logger.warn(e.getMessage(), e);
        }
        try {
            doClose();
        } catch (Throwable e) {
            logger.warn(e.getMessage(), e);
        }
    }

    @Override
    public void close(int timeout) {
        ExecutorUtil.gracefulShutdown(executor, timeout);
        close();
    }

    @Override
    public String toString() {
        return getClass().getName() + " [" + getLocalAddress() + " -> " + getRemoteAddress() + "]";
    }

    /**
     * Open client.
     *
     * @throws Throwable
     */
    protected abstract void doOpen() throws Throwable;

    /**
     * Close client.
     *
     * @throws Throwable
     */
    protected abstract void doClose() throws Throwable;

    /**
     * Connect to server.
     *
     * @throws Throwable
     */
    protected abstract void doConnect() throws Throwable;

    /**
     * disConnect to server.
     *
     * @throws Throwable
     */
    protected abstract void doDisConnect() throws Throwable;

    /**
     * Get the connected channel.
     *
     * @return channel
     */
    protected abstract Channel getChannel();

}
