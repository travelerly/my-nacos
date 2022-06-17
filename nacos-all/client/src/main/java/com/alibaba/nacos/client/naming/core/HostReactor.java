/*
 * Copyright 1999-2018 Alibaba Group Holding Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.alibaba.nacos.client.naming.core;

import com.alibaba.nacos.api.exception.NacosException;
import com.alibaba.nacos.api.naming.listener.EventListener;
import com.alibaba.nacos.api.naming.pojo.Instance;
import com.alibaba.nacos.api.naming.pojo.ServiceInfo;
import com.alibaba.nacos.client.monitor.MetricsMonitor;
import com.alibaba.nacos.client.naming.backups.FailoverReactor;
import com.alibaba.nacos.client.naming.beat.BeatInfo;
import com.alibaba.nacos.client.naming.beat.BeatReactor;
import com.alibaba.nacos.client.naming.cache.DiskCache;
import com.alibaba.nacos.client.naming.event.InstancesChangeEvent;
import com.alibaba.nacos.client.naming.event.InstancesChangeNotifier;
import com.alibaba.nacos.client.naming.net.NamingProxy;
import com.alibaba.nacos.client.naming.utils.CollectionUtils;
import com.alibaba.nacos.client.naming.utils.UtilAndComs;
import com.alibaba.nacos.common.lifecycle.Closeable;
import com.alibaba.nacos.common.notify.NotifyCenter;
import com.alibaba.nacos.common.utils.JacksonUtils;
import com.alibaba.nacos.common.utils.StringUtils;
import com.alibaba.nacos.common.utils.ThreadUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

import static com.alibaba.nacos.client.utils.LogUtils.NAMING_LOGGER;

/**
 * Host reactor.
 *
 * @author xuanyin
 */
public class HostReactor implements Closeable {

    private static final long DEFAULT_DELAY = 1000L;

    private static final long UPDATE_HOLD_INTERVAL = 5000L;

    // futureMap 是一个缓存 map，其 key 为 groupId@@微服务名称@@clusters，value 是一个定时异步操作对象「ScheduledFuture」
    private final Map<String, ScheduledFuture<?>> futureMap = new HashMap<String, ScheduledFuture<?>>();

    // serviceInfoMap：客户端本地注册表，key 为 groupId@@微服务名称@@cluster名称，value 为 ServiceInfo。
    private final Map<String, ServiceInfo> serviceInfoMap;

    /**
     * 用于存放当前正在发生变更的服务，key：serviceName，groupId@@微服务名称；value：new Object()，没有实际意义。
     * 其就是利用了 map 中 key 的唯一性特征，标记某个服务的 ServiceInfo 发生了变更
     */
    private final Map<String, Object> updatingMap;

    private final PushReceiver pushReceiver;

    // 用于处理心跳相关功能
    private final BeatReactor beatReactor;

    // 用于处理 Client 向 Server 端发送请求
    private final NamingProxy serverProxy;

    private final FailoverReactor failoverReactor;

    private final String cacheDir;

    private final boolean pushEmptyProtection;

    private final ScheduledExecutorService executor;

    private final InstancesChangeNotifier notifier;

    public HostReactor(NamingProxy serverProxy, BeatReactor beatReactor, String cacheDir) {
        this(serverProxy, beatReactor, cacheDir, false, false, UtilAndComs.DEFAULT_POLLING_THREAD_COUNT);
    }

    public HostReactor(NamingProxy serverProxy, BeatReactor beatReactor, String cacheDir, boolean loadCacheAtStart,
            boolean pushEmptyProtection, int pollingThreadCount) {
        // 创建一个线程池。init executorService
        this.executor = new ScheduledThreadPoolExecutor(pollingThreadCount, new ThreadFactory() {
            @Override
            public Thread newThread(Runnable r) {
                Thread thread = new Thread(r);
                thread.setDaemon(true);
                thread.setName("com.alibaba.nacos.client.naming.updater");
                return thread;
            }
        });

        this.beatReactor = beatReactor;
        this.serverProxy = serverProxy;
        this.cacheDir = cacheDir;
        if (loadCacheAtStart) {
            this.serviceInfoMap = new ConcurrentHashMap<String, ServiceInfo>(DiskCache.read(this.cacheDir));
        } else {
            this.serviceInfoMap = new ConcurrentHashMap<String, ServiceInfo>(16);
        }
        this.pushEmptyProtection = pushEmptyProtection;
        this.updatingMap = new ConcurrentHashMap<String, Object>();
        this.failoverReactor = new FailoverReactor(this, cacheDir);

        /**
         * Nacos还支持服务列表变更时的主动推送功能，基本思路：
         * 1.通过 PushReceiver 监听服务端推送的变更数据
         * 2.解析数据后，通过 Notifier 发布服务变更的事件
         * 3.InstancesChangeNotifier 监听服务变更事件，并触发对服务列表的更新
         */
        // 创建接收 nacos 服务端推送信息的组件。创建一个 PushReceiver 实例，用于 UDP 通信。服务端推送变更的接收器
        this.pushReceiver = new PushReceiver(this);
        // InstancesChangeNotifier：变更事件的监听器，notifier：订阅变更事件
        this.notifier = new InstancesChangeNotifier();

        // 给 InstanceChangeEvent 事件绑定对应的事件发布者
        NotifyCenter.registerToPublisher(InstancesChangeEvent.class, 16384);
        // 注册一个 InstanceChangeEvent 事件的事件订阅者
        NotifyCenter.registerSubscriber(notifier);
    }

    public Map<String, ServiceInfo> getServiceInfoMap() {
        return serviceInfoMap;
    }

    public synchronized ScheduledFuture<?> addTask(UpdateTask task) {
        return executor.schedule(task, DEFAULT_DELAY, TimeUnit.MILLISECONDS);
    }

    /**
     * 通过客户端对象去订阅一个服务，当这个服务发生变更时，就会回调 EventListener 监听器的 onEvent() 方法。
     * subscribe instancesChangeEvent.
     *
     * @param serviceName   combineServiceName, such as 'xxx@@xxx'
     * @param clusters      clusters, concat by ','. such as 'xxx,yyy'
     * @param eventListener custom listener
     */
    public void subscribe(String serviceName, String clusters, EventListener eventListener) {
        // 注册监听器
        notifier.registerListener(serviceName, clusters, eventListener);

        /**
         * 定时从 Nacos Server 端获取当前服务的所有实例并更新到本地
         * 获取目标服务(列表)，订阅服务信息
         */
        getServiceInfo(serviceName, clusters);
    }

    /**
     * unsubscribe instancesChangeEvent.
     *
     * @param serviceName   combineServiceName, such as 'xxx@@xxx'
     * @param clusters      clusters, concat by ','. such as 'xxx,yyy'
     * @param eventListener custom listener
     */
    public void unSubscribe(String serviceName, String clusters, EventListener eventListener) {
        notifier.deregisterListener(serviceName, clusters, eventListener);
    }

    public List<ServiceInfo> getSubscribeServices() {
        return notifier.getSubscribeServices();
    }

    /**
     * 将来自于 Nacos 服务端推送过来的变更的服务数据更新到当前 Nacos Client 的本地注册表中。
     * 处理从 nacos 服务端获取到的服务最新实例结果，该方法有两种情况会被调用：
     * 1.主动调用 nacos 服务端接口，服务端返回结果后调用；
     * 2.nacos 服务端推送服务最新的实例结果，在客户端接口到推送的时候会去调用。
     * Process service json.
     *
     * @param json service json
     * @return service info
     *
     * 此方法的前提是：服务端返回的数据为最新数据
     */
    public ServiceInfo processServiceJson(String json) {
        // 解析服务端返回的 ServiceInfo 数据。将数据序列化成一个 ServiceInfo 对象
        ServiceInfo serviceInfo = JacksonUtils.toObj(json, ServiceInfo.class);

        // 获取本地注册表(serviceInfoMap)中目标服务的 ServiceInfo 数据，即本地注册表中的旧数据
        ServiceInfo oldService = serviceInfoMap.get(serviceInfo.getKey());

        if (pushEmptyProtection && !serviceInfo.validate()) {
            // 忽略掉返回参数为空，或返回错误数据的情况。empty or error push, just ignore。
            return oldService;
        }

        boolean changed = false;

        // 如果缓存存在，则需要校验哪些数据需要更新
        if (oldService != null) { // 说明之前客户端有查找过该服务下的实例
            // 判断拉取的数据是否已经过期。极端情况记录日志，几乎不可能发生此种情况
            if (oldService.getLastRefTime() > serviceInfo.getLastRefTime()) {
                NAMING_LOGGER.warn("out of date data received, old-t: " + oldService.getLastRefTime() + ", new-t: "
                        + serviceInfo.getLastRefTime());
            }

            // 将服务端返回的新数据替换掉本地注册表中对应的旧数据
            serviceInfoMap.put(serviceInfo.getKey(), serviceInfo);

            /**
             * 遍历本地注册表中当前服务的所有实例的旧数据，并存入到一个旧实例数据缓存 Map 中。
             * oldHostMap，ip:port 作为 key，Instance 作为 value
             */
            Map<String, Instance> oldHostMap = new HashMap<String, Instance>(oldService.getHosts().size());
            for (Instance host : oldService.getHosts()) {
                // 将当前遍历的 Instance 主机的 ip:port 作为 key，Instance 作为 value 存入到新建的缓存 map 中「oldHostMap」。
                oldHostMap.put(host.toInetAddr(), host);
            }

            /**
             * 遍历服务端返回的当前服务的所有实例的新数据，并存入到一个新实例数据缓存 Map 中。
             * newHostMap，ip:port 作为 key，Instance 作为 value
             */
            Map<String, Instance> newHostMap = new HashMap<String, Instance>(serviceInfo.getHosts().size());
            for (Instance host : serviceInfo.getHosts()) {
                // 将当前遍历的 Instance 主机的 ip:port 作为 key，Instance 作为 value 存入到新建的缓存 map 中「newHostMap」。
                newHostMap.put(host.toInetAddr(), host);
            }

            // 存储两个 map（oldHostMap 与 newHostMap）中都存在的 key 所对应的新实例数据，即需要修改的实例数据
            Set<Instance> modHosts = new HashSet<Instance>();
            // 存储只在 newHostMap 中存储的 Instance 数据，即新增的实例数据
            Set<Instance> newHosts = new HashSet<Instance>();
            // 存储只在 oldHostMap 中存储的 Instance 数据，即待删除的实例数据
            Set<Instance> remvHosts = new HashSet<Instance>();
            // 将从服务端返回的实例数据封装到集合 newServiceHosts 中
            List<Map.Entry<String, Instance>> newServiceHosts = new ArrayList<Map.Entry<String, Instance>>(
                    newHostMap.entrySet());

            // 遍历服务端返回的主机数据，新实例数据
            for (Map.Entry<String, Instance> entry : newServiceHosts) {
                Instance host = entry.getValue();
                String key = entry.getKey();
                /**
                 * 在注册表中存在该 ip:port 的 key，说明是同一个实例，但新的实例与旧的实例之间的信息系不同，说明实例被修改过，
                 * 则将这个实例存入到 modHosts 中，即存入到被修改集合中。
                 */
                if (oldHostMap.containsKey(key) && !StringUtils
                        .equals(host.toString(), oldHostMap.get(key).toString())) {
                    modHosts.add(host);
                    continue;
                }

                /**
                 * 在注册表中不存在该 ip:port 的 key，说明这个主机是新增的，则将其存入到 newHosts 中，即加入到新增集合中。
                 * 若服务端返回的主机数据的 key 在旧缓存数据中不存在，则说明这个主机是新增的，则将其存入到 newHosts 中。
                 */
                if (!oldHostMap.containsKey(key)) {
                    newHosts.add(host);
                }
            }

            // 遍历本地注册表中的主机数据，旧实例数据
            for (Map.Entry<String, Instance> entry : oldHostMap.entrySet()) {
                Instance host = entry.getValue();
                String key = entry.getKey();
                if (newHostMap.containsKey(key)) {
                    continue;
                }

                /**
                 * 本地注册表中存在，但服务端返回的数据中不存在，说明这个 Instance 需要被删除，即将其存入到 remvHosts 中，即加入到被移除集合中。
                 * 若新实例数据缓存中不包含旧实例数据，则说明该实例需要被删除，所以将该数据存入到删除缓存中。「remvHosts」
                 */
                if (!newHostMap.containsKey(key)) {
                    remvHosts.add(host);
                }

            }

            if (newHosts.size() > 0) {
                changed = true;
                NAMING_LOGGER.info("new ips(" + newHosts.size() + ") service: " + serviceInfo.getKey() + " -> "
                        + JacksonUtils.toJson(newHosts));
            }

            if (remvHosts.size() > 0) {
                changed = true;
                NAMING_LOGGER.info("removed ips(" + remvHosts.size() + ") service: " + serviceInfo.getKey() + " -> "
                        + JacksonUtils.toJson(remvHosts));
            }

            if (modHosts.size() > 0) {
                changed = true;
                // 变更心跳信息 BeatInfo。即更新合并后的数据(modHosts)的心跳信息 BeatInfo。
                updateBeatInfo(modHosts);
                NAMING_LOGGER.info("modified ips(" + modHosts.size() + ") service: " + serviceInfo.getKey() + " -> "
                        + JacksonUtils.toJson(modHosts));
            }

            serviceInfo.setJsonFromServer(json);

            // 只要发生了变更，就将这个发生了变更的 ServiceInfo 记录到一个缓存队列中。
            if (newHosts.size() > 0 || remvHosts.size() > 0 || modHosts.size() > 0) {
            /**
             * 通过 InstancesChangeEvent 事件对应的事件发布者去发布一个 InstanceChangeEvent 事件，即发布一个实例变更的事件
             * 发布完之后，该事件发布者对应的事件订阅者就能够进行监听回调。
             */
            NotifyCenter.publishEvent(new InstancesChangeEvent(serviceInfo.getName(), serviceInfo.getGroupName(),
                    serviceInfo.getClusters(), serviceInfo.getHosts()));
                DiskCache.write(serviceInfo, cacheDir);
            }

        } else { // 本地注册表中没有当前服务的相关数据，说明是第一次请求该服务实例，则将服务端返回的服务数据，直接保存进本地注册表中。
            changed = true;
            NAMING_LOGGER.info("init new ips(" + serviceInfo.ipCount() + ") service: " + serviceInfo.getKey() + " -> "
                    + JacksonUtils.toJson(serviceInfo.getHosts()));
            // 将服务端返回的 ServiceInfo 存储到本地注册表中
            serviceInfoMap.put(serviceInfo.getKey(), serviceInfo);

            /**
             * 通过 InstancesChangeEvent 事件对应的事件发布者去发布一个 InstanceChangeEvent 事件，即发布一个实例变更的事件
             * 发布完之后，该事件发布者对应的事件订阅者就能够进行监听回调。
             */
            NotifyCenter.publishEvent(new InstancesChangeEvent(serviceInfo.getName(), serviceInfo.getGroupName(),
                    serviceInfo.getClusters(), serviceInfo.getHosts()));
            serviceInfo.setJsonFromServer(json);
            DiskCache.write(serviceInfo, cacheDir);
        }

        MetricsMonitor.getServiceInfoMapSizeMonitor().set(serviceInfoMap.size());

        if (changed) {
            NAMING_LOGGER.info("current ips:(" + serviceInfo.ipCount() + ") service: " + serviceInfo.getKey() + " -> "
                    + JacksonUtils.toJson(serviceInfo.getHosts()));
        }

        return serviceInfo;
    }

    private void updateBeatInfo(Set<Instance> modHosts) {
        // 遍历处理合并后的数据的心跳信息
        for (Instance instance : modHosts) {
            String key = beatReactor.buildKey(instance.getServiceName(), instance.getIp(), instance.getPort());
            if (beatReactor.dom2Beat.containsKey(key) && instance.isEphemeral()) {
                BeatInfo beatInfo = beatReactor.buildBeatInfo(instance);
                // 发送心跳
                beatReactor.addBeatInfo(instance.getServiceName(), beatInfo);
            }
        }
    }

    private ServiceInfo getServiceInfo0(String serviceName, String clusters) {

        String key = ServiceInfo.getKey(serviceName, clusters);
        // serviceInfoMap：客户端本地注册表，key 为 groupId@@微服务名称@@cluster名称，value 为 ServiceInfo。
        return serviceInfoMap.get(key);
    }

    public ServiceInfo getServiceInfoDirectlyFromServer(final String serviceName, final String clusters)
            throws NacosException {
        String result = serverProxy.queryList(serviceName, clusters, 0, false);
        if (StringUtils.isNotEmpty(result)) {
            return JacksonUtils.toObj(result, ServiceInfo.class);
        }
        return null;
    }

    /**
     * 获取目标服务(列表)，订阅服务信息
     * 调用此方法可以获取到指定服务下指定集群的所有服务实例，并且调用的客户端还会被 nacos 服务端视为已订阅客户端，
     * 该方法用于客户端订阅拉取服务的模式，即订阅
     *
     * 逻辑时先从本地缓存中读，根据结果来进行选择；
     * 1.如果本地缓存中没有，则从服务端拉取；
     * 2.如果本地缓存中有，则先开启定时更新功能，载从本地缓存中读取后返回结果
     *
     * @param serviceName 指定的服务名称
     * @param clusters 指定的集群集合（逗号分隔）
     * @return
     */
    public ServiceInfo getServiceInfo(final String serviceName, final String clusters) {

        NAMING_LOGGER.debug("failover-mode: " + failoverReactor.isFailoverSwitch());
        // 构建 key，格式为：微服务名称@@cluster名称，例如：colin-nacos-consumer@@myCluster
        String key = ServiceInfo.getKey(serviceName, clusters);
        if (failoverReactor.isFailoverSwitch()) {
            return failoverReactor.getService(key);
        }

        // 从当前客户端的本地注册表中获取当前服务，即读取本地服务列表的缓存，缓存是一个 Map<String,ServiceInfo>
        ServiceInfo serviceObj = getServiceInfo0(serviceName, clusters);

        // 判断缓存是否存在
        if (null == serviceObj) { // 第一次获取该服务集群对应的实例

            // 本地注册表中没有该服务，则创建一个空的服务「没有任何提供者实例 Instance 的 ServiceInfo」
            serviceObj = new ServiceInfo(serviceName, clusters);
            // 将空服务放入客户端本地注册表中。serviceInfoMap：客户端本地注册表，key 为 groupId@@微服务名称@@cluster名称，value 为 ServiceInfo。
            serviceInfoMap.put(serviceObj.getKey(), serviceObj);

            /**
             * 临时缓存，待更新的服务列表，利用 map 的 key 不能重复的特性；只要有服务名称存在这个缓存中，就表示当前这个服务正在被更新。
             * 准备要更新 serviceName 的服务了，就先将其名称存入临时缓存 map 中。
             * 对该服务进行占位。
             */
            updatingMap.put(serviceName, new Object());

            // 向 nacos 服务端发送请求，获取指定服务的实例数据，并更新本地注册表。（立即更新服务列表）
            updateServiceNow(serviceName, clusters);

            // 更新完毕，将该 serviceName 服务从临时缓存中删除。即删除占位。
            updatingMap.remove(serviceName);

        } else if (updatingMap.containsKey(serviceName)) {
            // 至此说明当前服务正在被更新，即有服务发生变更，在临时缓存中有占位，即缓存中有该服务名称，但需要更新

            if (UPDATE_HOLD_INTERVAL > 0) {
                // hold a moment waiting for update finish
                synchronized (serviceObj) {
                    try {
                        /**
                         * 若当前注册表中已经存在该服务，则需要先查看一下临时缓存 map 中是否存在该服务
                         * 若临时缓存 map 中存在该服务，则说明这个服务正在被更新，所以本次操作需阻塞一会，
                         * 直到前一个线程获取到实例集合数据并缓存到内存中的时候才会被唤醒，或者超时唤醒，默认的超时时间是 5s。
                         */
                        serviceObj.wait(UPDATE_HOLD_INTERVAL);
                    } catch (InterruptedException e) {
                        NAMING_LOGGER
                                .error("[getServiceInfo] serviceName:" + serviceName + ", clusters:" + clusters, e);
                    }
                }
            }
        }

        // 启动定时任务，定时更新本地注册表中当前服务的数据，即开启定时更新服务列表的功能。
        scheduleUpdateIfAbsent(serviceName, clusters);
        // 返回缓存中的服务信息，serviceInfoMap：客户端本地注册表，key 为 微服务名称@@cluster名称，value 为 ServiceInfo。
        return serviceInfoMap.get(serviceObj.getKey());
    }

    private void updateServiceNow(String serviceName, String clusters) {
        try {
            // 从服务端获取到指定服务下的所有实例，并且当前客户端还会被服务端所绑定作为推送的目标客户端。
            updateService(serviceName, clusters);
        } catch (NacosException e) {
            NAMING_LOGGER.error("[NA] failed to update serviceName: " + serviceName, e);
        }
    }

    /**
     * Schedule update if absent.
     *
     * @param serviceName service name
     * @param clusters    clusters
     *
     * 此方法采用了双端检测锁机制（DCL：Double Check Lock），避免并发多线程情况下数据的重复写入。
     */
    public void scheduleUpdateIfAbsent(String serviceName, String clusters) {
        // futureMap 是一个缓存 map，其 key 为 groupId@@微服务名称@@clusters，value 是一个定时异步操作对象「ScheduledFuture」
        if (futureMap.get(ServiceInfo.getKey(serviceName, clusters)) != null) {
            return;
        }

        synchronized (futureMap) {
            if (futureMap.get(ServiceInfo.getKey(serviceName, clusters)) != null) {
                return;
            }
            // 创建一个定时异步操作对象，并启动这个定时任务。
            ScheduledFuture<?> future = addTask(new UpdateTask(serviceName, clusters));
            // 将上一步创建好的异步操作对象封装到缓存 map 中。
            futureMap.put(ServiceInfo.getKey(serviceName, clusters), future);
        }
    }

    /**
     * 从服务端获取到指定服务下的所有实例，并且当前客户端还会被服务端所绑定作为推送的目标客户端。
     * Update service now.
     *
     * @param serviceName service name 指定的服务端名称
     * @param clusters    clusters 指定的集群集合
     */
    public void updateService(String serviceName, String clusters) throws NacosException {
        // 从本地注册表中获取目标服务的数据
        ServiceInfo oldService = getServiceInfo0(serviceName, clusters);
        try {
            /**
             * 基于 ServerProxy 发起远程调用，拉取对应服务和集群下所有实例
             * 向 server 提交一个"GET"请求，获取指定服务所有实例，同时当前客户端还会订阅该指定的服务，返回结果是 JSON 格式
             */
            String result = serverProxy.queryList(serviceName, clusters, pushReceiver.getUdpPort(), false);

            if (StringUtils.isNotEmpty(result)) {
                // 处理查询结果，解析服务端返回的 JSON 格式的 serviceInfo，将其更新到本地注册表中「serviceInfoMap」
                processServiceJson(result);
            }
        } finally {
            if (oldService != null) {
                synchronized (oldService) {
                    // 解除上层线程的阻塞。
                    oldService.notifyAll();
                }
            }
        }
    }

    /**
     * Refresh only.
     *
     * @param serviceName service name
     * @param clusters    cluster
     */
    public void refreshOnly(String serviceName, String clusters) {
        try {
            serverProxy.queryList(serviceName, clusters, pushReceiver.getUdpPort(), false);
        } catch (Exception e) {
            NAMING_LOGGER.error("[NA] failed to update serviceName: " + serviceName, e);
        }
    }

    @Override
    public void shutdown() throws NacosException {
        String className = this.getClass().getName();
        NAMING_LOGGER.info("{} do shutdown begin", className);
        ThreadUtils.shutdownThreadPool(executor, NAMING_LOGGER);
        pushReceiver.shutdown();
        failoverReactor.shutdown();
        NotifyCenter.deregisterSubscriber(notifier);
        NAMING_LOGGER.info("{} do shutdown stop", className);
    }

    // 客户端更新任务
    public class UpdateTask implements Runnable {

        long lastRefTime = Long.MAX_VALUE;

        private final String clusters;

        private final String serviceName;

        /**
         * the fail situation. 1:can't connect to server 2:serviceInfo's hosts is empty
         */
        private int failCount = 0;

        public UpdateTask(String serviceName, String clusters) {
            this.serviceName = serviceName;
            this.clusters = clusters;
        }

        private void incFailCount() {
            int limit = 6;
            if (failCount == limit) {
                return;
            }
            failCount++;
        }

        private void resetFailCount() {
            failCount = 0;
        }

        @Override
        public void run() {
            long delayTime = DEFAULT_DELAY;

            try {
                // 从本地注册表中获取当前服务
                ServiceInfo serviceObj = serviceInfoMap.get(ServiceInfo.getKey(serviceName, clusters));

                if (serviceObj == null) {
                    // 本地注册表中不包含当前服务，则向服务端获取当前服务数据，并更新到本地注册表
                    updateService(serviceName, clusters);
                    return;
                }
                /**
                 * 本地注册表包含有当前服务
                 * （1）serviceObj.getLastRefTime()：获取到的是当前服务被最后访问的时间，这个时间来自于本地注册表，其记录的是所有
                 *   提供这个服务的实例中最后一个实例被访问的时间
                 * （2）lastRefTime 记录的是当前实例被最后访问的时间
                 *
                 * 若（1）的时间小于（2）的时间，则说明当前注册表需要更新
                 */
                if (serviceObj.getLastRefTime() <= lastRefTime) {
                    updateService(serviceName, clusters);
                    serviceObj = serviceInfoMap.get(ServiceInfo.getKey(serviceName, clusters));
                } else {
                    // if serviceName already updated by push, we should not override it
                    // since the push data may be different from pull through force push
                    refreshOnly(serviceName, clusters);
                }
                // 将来自与注册表的这个最后时间更新到当前客户端的缓存中。
                lastRefTime = serviceObj.getLastRefTime();

                if (!notifier.isSubscribed(serviceName, clusters) && !futureMap
                        .containsKey(ServiceInfo.getKey(serviceName, clusters))) {
                    // abort the update task
                    NAMING_LOGGER.info("update task is stopped, service:" + serviceName + ", clusters:" + clusters);
                    return;
                }
                if (CollectionUtils.isEmpty(serviceObj.getHosts())) {
                    incFailCount();
                    return;
                }
                delayTime = serviceObj.getCacheMillis();
                resetFailCount();
            } catch (Throwable e) {
                incFailCount();
                NAMING_LOGGER.warn("[NA] failed to update serviceName: " + serviceName, e);
            } finally {
                // 开启下一次的定时任务
                executor.schedule(this, Math.min(delayTime << failCount, DEFAULT_DELAY * 60), TimeUnit.MILLISECONDS);
            }
        }
    }
}
