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

package com.alibaba.nacos.client.naming;

import com.alibaba.nacos.api.PropertyKeyConst;
import com.alibaba.nacos.api.common.Constants;
import com.alibaba.nacos.api.exception.NacosException;
import com.alibaba.nacos.api.naming.NamingService;
import com.alibaba.nacos.api.naming.listener.EventListener;
import com.alibaba.nacos.api.naming.pojo.Instance;
import com.alibaba.nacos.api.naming.pojo.ListView;
import com.alibaba.nacos.api.naming.pojo.ServiceInfo;
import com.alibaba.nacos.api.naming.utils.NamingUtils;
import com.alibaba.nacos.api.selector.AbstractSelector;
import com.alibaba.nacos.client.naming.beat.BeatInfo;
import com.alibaba.nacos.client.naming.beat.BeatReactor;
import com.alibaba.nacos.client.naming.core.Balancer;
import com.alibaba.nacos.client.naming.core.HostReactor;
import com.alibaba.nacos.client.naming.net.NamingProxy;
import com.alibaba.nacos.client.naming.utils.CollectionUtils;
import com.alibaba.nacos.client.naming.utils.InitUtils;
import com.alibaba.nacos.client.naming.utils.UtilAndComs;
import com.alibaba.nacos.client.utils.ValidatorUtils;
import com.alibaba.nacos.common.utils.ConvertUtils;
import com.alibaba.nacos.common.utils.StringUtils;

import java.io.File;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Properties;

/**
 * Nacos Naming Service.
 *
 * @author nkorange
 *
 * 可以完成 Client 与 Server 间的通信
 * 提供了以下功能："注册/取消注册"、"订阅/取消订阅"、"获取 Server 状态"、"获取 Server 中指定的 Instance"……
 * 但心跳功能不是通过此类实现的。
 */
@SuppressWarnings("PMD.ServiceOrDaoClassShouldEndWithImplRule")
public class NacosNamingService implements NamingService {

    /**
     * Each Naming service should have different namespace.
     */
    private String namespace;

    private String endpoint;

    private String serverList;

    private String cacheDir;

    private String logName;

    private HostReactor hostReactor;

    private BeatReactor beatReactor;

    private NamingProxy serverProxy;

    public NacosNamingService(String serverList) throws NacosException {
        Properties properties = new Properties();
        properties.setProperty(PropertyKeyConst.SERVER_ADDR, serverList);
        init(properties);
    }

    public NacosNamingService(Properties properties) throws NacosException {
        init(properties);
    }

    private void init(Properties properties) throws NacosException {
        ValidatorUtils.checkInitParam(properties);
        this.namespace = InitUtils.initNamespaceForNaming(properties);
        InitUtils.initSerialization();
        initServerAddr(properties);
        InitUtils.initWebRootContext(properties);
        initCacheDir();
        initLogName(properties);

        // 创建 nacos 的 api 请求客户端。
        this.serverProxy = new NamingProxy(this.namespace, this.endpoint, this.serverList, properties);
        this.beatReactor = new BeatReactor(this.serverProxy, initClientBeatThreadCount(properties));
        this.hostReactor = new HostReactor(this.serverProxy, beatReactor, this.cacheDir, isLoadCacheAtStart(properties),
                isPushEmptyProtect(properties), initPollingThreadCount(properties));
    }

    private int initClientBeatThreadCount(Properties properties) {
        if (properties == null) {
            return UtilAndComs.DEFAULT_CLIENT_BEAT_THREAD_COUNT;
        }

        return ConvertUtils.toInt(properties.getProperty(PropertyKeyConst.NAMING_CLIENT_BEAT_THREAD_COUNT),
                UtilAndComs.DEFAULT_CLIENT_BEAT_THREAD_COUNT);
    }

    private int initPollingThreadCount(Properties properties) {
        if (properties == null) {

            return UtilAndComs.DEFAULT_POLLING_THREAD_COUNT;
        }

        return ConvertUtils.toInt(properties.getProperty(PropertyKeyConst.NAMING_POLLING_THREAD_COUNT),
                UtilAndComs.DEFAULT_POLLING_THREAD_COUNT);
    }

    private boolean isLoadCacheAtStart(Properties properties) {
        boolean loadCacheAtStart = false;
        if (properties != null && StringUtils
                .isNotEmpty(properties.getProperty(PropertyKeyConst.NAMING_LOAD_CACHE_AT_START))) {
            loadCacheAtStart = ConvertUtils
                    .toBoolean(properties.getProperty(PropertyKeyConst.NAMING_LOAD_CACHE_AT_START));
        }

        return loadCacheAtStart;
    }

    private boolean isPushEmptyProtect(Properties properties) {
        boolean pushEmptyProtection = false;
        if (properties != null && StringUtils
                .isNotEmpty(properties.getProperty(PropertyKeyConst.NAMING_PUSH_EMPTY_PROTECTION))) {
            pushEmptyProtection = ConvertUtils
                    .toBoolean(properties.getProperty(PropertyKeyConst.NAMING_PUSH_EMPTY_PROTECTION));
        }
        return pushEmptyProtection;
    }

    private void initServerAddr(Properties properties) {
        serverList = properties.getProperty(PropertyKeyConst.SERVER_ADDR);
        endpoint = InitUtils.initEndpoint(properties);
        if (StringUtils.isNotEmpty(endpoint)) {
            serverList = "";
        }
    }

    private void initLogName(Properties properties) {
        logName = System.getProperty(UtilAndComs.NACOS_NAMING_LOG_NAME);
        if (StringUtils.isEmpty(logName)) {

            if (properties != null && StringUtils
                    .isNotEmpty(properties.getProperty(UtilAndComs.NACOS_NAMING_LOG_NAME))) {
                logName = properties.getProperty(UtilAndComs.NACOS_NAMING_LOG_NAME);
            } else {
                logName = "naming.log";
            }
        }
    }

    private void initCacheDir() {
        String jmSnapshotPath = System.getProperty("JM.SNAPSHOT.PATH");
        if (!StringUtils.isBlank(jmSnapshotPath)) {
            cacheDir =
                    jmSnapshotPath + File.separator + "nacos" + File.separator + "naming" + File.separator + namespace;
        } else {
            cacheDir = System.getProperty("user.home") + File.separator + "nacos" + File.separator + "naming"
                    + File.separator + namespace;
        }
    }

    @Override
    public void registerInstance(String serviceName, String ip, int port) throws NacosException {
        registerInstance(serviceName, ip, port, Constants.DEFAULT_CLUSTER_NAME);
    }

    @Override
    public void registerInstance(String serviceName, String groupName, String ip, int port) throws NacosException {
        registerInstance(serviceName, groupName, ip, port, Constants.DEFAULT_CLUSTER_NAME);
    }

    @Override
    public void registerInstance(String serviceName, String ip, int port, String clusterName) throws NacosException {
        registerInstance(serviceName, Constants.DEFAULT_GROUP, ip, port, clusterName);
    }

    @Override
    public void registerInstance(String serviceName, String groupName, String ip, int port, String clusterName)
            throws NacosException {

        Instance instance = new Instance();
        instance.setIp(ip);
        instance.setPort(port);
        instance.setWeight(1.0);
        instance.setClusterName(clusterName);

        registerInstance(serviceName, groupName, instance);
    }

    @Override
    public void registerInstance(String serviceName, Instance instance) throws NacosException {
        registerInstance(serviceName, Constants.DEFAULT_GROUP, instance);
    }

    // Nacos Client 发起注册请求(包括注册与心跳)
    @Override
    public void registerInstance(String serviceName, String groupName, Instance instance) throws NacosException {
        // 检测超时参数是否异常，心跳超时时间（默认 15s）必须大于心跳周期（默认 15s）
        NamingUtils.checkInstanceIsLegal(instance);
        // 生成格式：groupName@@serviceId，例如：my_group@@colin-nacos-consumer
        String groupedServiceName = NamingUtils.getGroupedName(serviceName, groupName);
        // 判断当前实例是否为临时实例「默认为临时实例」，临时实例基于心跳的方式做健康检测，永久实例则是由 nacos 主动探测实例的状态
        if (instance.isEphemeral()) {
            // 构建心跳信息数据
            BeatInfo beatInfo = beatReactor.buildBeatInfo(groupedServiceName, instance);
            // 临时实例，向服务端发送心跳请求。「定时任务」。beatReactor 内部维护了一个线程池
            beatReactor.addBeatInfo(groupedServiceName, beatInfo);
        }
        /**
         * 向服务端发送注册请求，最终由 NacosProxy 的 registerService 方法处理
         * 提交一个 POST 的注册请求。url：/nacos/v1/ns/instance
         */
        serverProxy.registerService(groupedServiceName, groupName, instance);
    }

    @Override
    public void deregisterInstance(String serviceName, String ip, int port) throws NacosException {
        deregisterInstance(serviceName, ip, port, Constants.DEFAULT_CLUSTER_NAME);
    }

    @Override
    public void deregisterInstance(String serviceName, String groupName, String ip, int port) throws NacosException {
        deregisterInstance(serviceName, groupName, ip, port, Constants.DEFAULT_CLUSTER_NAME);
    }

    @Override
    public void deregisterInstance(String serviceName, String ip, int port, String clusterName) throws NacosException {
        deregisterInstance(serviceName, Constants.DEFAULT_GROUP, ip, port, clusterName);
    }

    @Override
    public void deregisterInstance(String serviceName, String groupName, String ip, int port, String clusterName)
            throws NacosException {
        Instance instance = new Instance();
        instance.setIp(ip);
        instance.setPort(port);
        instance.setClusterName(clusterName);

        deregisterInstance(serviceName, groupName, instance);
    }

    @Override
    public void deregisterInstance(String serviceName, Instance instance) throws NacosException {
        deregisterInstance(serviceName, Constants.DEFAULT_GROUP, instance);
    }

    @Override
    public void deregisterInstance(String serviceName, String groupName, Instance instance) throws NacosException {
        if (instance.isEphemeral()) {
            beatReactor.removeBeatInfo(NamingUtils.getGroupedName(serviceName, groupName), instance.getIp(),
                    instance.getPort());
        }
        serverProxy.deregisterService(NamingUtils.getGroupedName(serviceName, groupName), instance);
    }

    @Override
    public List<Instance> getAllInstances(String serviceName) throws NacosException {
        return getAllInstances(serviceName, new ArrayList<String>());
    }

    @Override
    public List<Instance> getAllInstances(String serviceName, String groupName) throws NacosException {
        return getAllInstances(serviceName, groupName, new ArrayList<String>());
    }

    @Override
    public List<Instance> getAllInstances(String serviceName, boolean subscribe) throws NacosException {
        return getAllInstances(serviceName, new ArrayList<String>(), subscribe);
    }

    @Override
    public List<Instance> getAllInstances(String serviceName, String groupName, boolean subscribe)
            throws NacosException {
        return getAllInstances(serviceName, groupName, new ArrayList<String>(), subscribe);
    }

    @Override
    public List<Instance> getAllInstances(String serviceName, List<String> clusters) throws NacosException {
        return getAllInstances(serviceName, clusters, true);
    }

    @Override
    public List<Instance> getAllInstances(String serviceName, String groupName, List<String> clusters)
            throws NacosException {
        return getAllInstances(serviceName, groupName, clusters, true);
    }

    @Override
    public List<Instance> getAllInstances(String serviceName, List<String> clusters, boolean subscribe)
            throws NacosException {
        return getAllInstances(serviceName, Constants.DEFAULT_GROUP, clusters, subscribe);
    }

    /**
     * 获取指定服务下面的指定集群的所有实例
     * @param serviceName 服务名称。name of service
     * @param groupName   服务组名称。group of service
     * @param clusters    集群名称集合。list of cluster
     * @param subscribe   该客户端是否对指定的服务进行订阅。if subscribe the service
     * @return
     * @throws NacosException
     */
    @Override
    public List<Instance> getAllInstances(String serviceName, String groupName, List<String> clusters,
            boolean subscribe) throws NacosException {

        ServiceInfo serviceInfo;
        // 判断是否需要订阅服务信息（默认为 true）
        if (subscribe) {
            // 订阅服务信息
            serviceInfo = hostReactor.getServiceInfo(NamingUtils.getGroupedName(serviceName, groupName),
                    StringUtils.join(clusters, ","));
        } else {
            // 该客户端不订阅服务，每次都会请求服务器获取最新的服务实例，即直接取 nacos 拉取服务实例
            serviceInfo = hostReactor
                    .getServiceInfoDirectlyFromServer(NamingUtils.getGroupedName(serviceName, groupName),
                            StringUtils.join(clusters, ","));
        }

        // 从服务信息中获取实例列表并返回
        List<Instance> list;
        if (serviceInfo == null || CollectionUtils.isEmpty(list = serviceInfo.getHosts())) {
            return new ArrayList<Instance>();
        }
        return list;
    }

    @Override
    public List<Instance> selectInstances(String serviceName, boolean healthy) throws NacosException {
        return selectInstances(serviceName, new ArrayList<String>(), healthy);
    }

    @Override
    public List<Instance> selectInstances(String serviceName, String groupName, boolean healthy) throws NacosException {
        return selectInstances(serviceName, groupName, healthy, true);
    }

    @Override
    public List<Instance> selectInstances(String serviceName, boolean healthy, boolean subscribe)
            throws NacosException {
        return selectInstances(serviceName, new ArrayList<String>(), healthy, subscribe);
    }

    @Override
    public List<Instance> selectInstances(String serviceName, String groupName, boolean healthy, boolean subscribe)
            throws NacosException {
        return selectInstances(serviceName, groupName, new ArrayList<String>(), healthy, subscribe);
    }

    @Override
    public List<Instance> selectInstances(String serviceName, List<String> clusters, boolean healthy)
            throws NacosException {
        return selectInstances(serviceName, clusters, healthy, true);
    }

    @Override
    public List<Instance> selectInstances(String serviceName, String groupName, List<String> clusters, boolean healthy)
            throws NacosException {
        return selectInstances(serviceName, groupName, clusters, healthy, true);
    }

    @Override
    public List<Instance> selectInstances(String serviceName, List<String> clusters, boolean healthy, boolean subscribe)
            throws NacosException {
        return selectInstances(serviceName, Constants.DEFAULT_GROUP, clusters, healthy, subscribe);
    }

    // 客户端获取目标服务(列表)的请求
    @Override
    public List<Instance> selectInstances(String serviceName, String groupName, List<String> clusters, boolean healthy,
            boolean subscribe) throws NacosException {

        ServiceInfo serviceInfo;
        if (subscribe) {
            // 获取目标服务的 serviceInfo
            serviceInfo = hostReactor.getServiceInfo(NamingUtils.getGroupedName(serviceName, groupName),
                    StringUtils.join(clusters, ","));
        } else {
            serviceInfo = hostReactor
                    .getServiceInfoDirectlyFromServer(NamingUtils.getGroupedName(serviceName, groupName),
                            StringUtils.join(clusters, ","));
        }
        // 从 serviceInfo 的所有实例中筛选出所有可用的实例信息
        return selectInstances(serviceInfo, healthy);
    }

    // 从 serviceInfo 的所有实例中筛选出所有可用的实例信息
    private List<Instance> selectInstances(ServiceInfo serviceInfo, boolean healthy) {
        List<Instance> list;
        if (serviceInfo == null || CollectionUtils.isEmpty(list = serviceInfo.getHosts())) {
            return new ArrayList<Instance>();
        }
        // 迭代服务的所有实例数据
        Iterator<Instance> iterator = list.iterator();
        while (iterator.hasNext()) {
            Instance instance = iterator.next();
            if (healthy != instance.isHealthy() || !instance.isEnabled() || instance.getWeight() <= 0) {
                // 若当前实例不是健康的、不可用的、权重小于 0 的，则将其从列表中删除。
                iterator.remove();
            }
        }
        // 返回可用数据的列表
        return list;
    }

    @Override
    public Instance selectOneHealthyInstance(String serviceName) throws NacosException {
        return selectOneHealthyInstance(serviceName, new ArrayList<String>());
    }

    @Override
    public Instance selectOneHealthyInstance(String serviceName, String groupName) throws NacosException {
        return selectOneHealthyInstance(serviceName, groupName, true);
    }

    @Override
    public Instance selectOneHealthyInstance(String serviceName, boolean subscribe) throws NacosException {
        return selectOneHealthyInstance(serviceName, new ArrayList<String>(), subscribe);
    }

    @Override
    public Instance selectOneHealthyInstance(String serviceName, String groupName, boolean subscribe)
            throws NacosException {
        return selectOneHealthyInstance(serviceName, groupName, new ArrayList<String>(), subscribe);
    }

    @Override
    public Instance selectOneHealthyInstance(String serviceName, List<String> clusters) throws NacosException {
        return selectOneHealthyInstance(serviceName, clusters, true);
    }

    @Override
    public Instance selectOneHealthyInstance(String serviceName, String groupName, List<String> clusters)
            throws NacosException {
        return selectOneHealthyInstance(serviceName, groupName, clusters, true);
    }

    @Override
    public Instance selectOneHealthyInstance(String serviceName, List<String> clusters, boolean subscribe)
            throws NacosException {
        return selectOneHealthyInstance(serviceName, Constants.DEFAULT_GROUP, clusters, subscribe);
    }

    @Override
    public Instance selectOneHealthyInstance(String serviceName, String groupName, List<String> clusters,
            boolean subscribe) throws NacosException {

        if (subscribe) {
            return Balancer.RandomByWeight.selectHost(hostReactor
                    .getServiceInfo(NamingUtils.getGroupedName(serviceName, groupName),
                            StringUtils.join(clusters, ",")));
        } else {
            return Balancer.RandomByWeight.selectHost(hostReactor
                    .getServiceInfoDirectlyFromServer(NamingUtils.getGroupedName(serviceName, groupName),
                            StringUtils.join(clusters, ",")));
        }
    }

    @Override
    public void subscribe(String serviceName, EventListener listener) throws NacosException {
        subscribe(serviceName, new ArrayList<String>(), listener);
    }

    @Override
    public void subscribe(String serviceName, String groupName, EventListener listener) throws NacosException {
        subscribe(serviceName, groupName, new ArrayList<String>(), listener);
    }

    @Override
    public void subscribe(String serviceName, List<String> clusters, EventListener listener) throws NacosException {
        subscribe(serviceName, Constants.DEFAULT_GROUP, clusters, listener);
    }

    /**
     * Nacos Client 向 Nacos Server 提交服务订阅请求
     * 定时从 Nacos Server 端获取当前服务的所有实例并更新到本地
     *
     * @param serviceName name of service
     * @param groupName   group of service
     * @param clusters    list of cluster
     * @param listener    event listener
     * @throws NacosException
     */
    @Override
    public void subscribe(String serviceName, String groupName, List<String> clusters, EventListener listener)
            throws NacosException {
        // 通过客户端对象去订阅一个服务，当这个服务发生变更时，就会回调 EventListener 监听器的 onEvent() 方法。
        hostReactor.subscribe(NamingUtils.getGroupedName(serviceName, groupName), StringUtils.join(clusters, ","),
                listener);
    }

    @Override
    public void unsubscribe(String serviceName, EventListener listener) throws NacosException {
        unsubscribe(serviceName, new ArrayList<String>(), listener);
    }

    @Override
    public void unsubscribe(String serviceName, String groupName, EventListener listener) throws NacosException {
        unsubscribe(serviceName, groupName, new ArrayList<String>(), listener);
    }

    @Override
    public void unsubscribe(String serviceName, List<String> clusters, EventListener listener) throws NacosException {
        unsubscribe(serviceName, Constants.DEFAULT_GROUP, clusters, listener);
    }

    @Override
    public void unsubscribe(String serviceName, String groupName, List<String> clusters, EventListener listener)
            throws NacosException {
        hostReactor.unSubscribe(NamingUtils.getGroupedName(serviceName, groupName), StringUtils.join(clusters, ","),
                listener);
    }

    @Override
    public ListView<String> getServicesOfServer(int pageNo, int pageSize) throws NacosException {
        return serverProxy.getServiceList(pageNo, pageSize, Constants.DEFAULT_GROUP);
    }

    @Override
    public ListView<String> getServicesOfServer(int pageNo, int pageSize, String groupName) throws NacosException {
        return getServicesOfServer(pageNo, pageSize, groupName, null);
    }

    @Override
    public ListView<String> getServicesOfServer(int pageNo, int pageSize, AbstractSelector selector)
            throws NacosException {
        return getServicesOfServer(pageNo, pageSize, Constants.DEFAULT_GROUP, selector);
    }

    // 客户端获取所有服务列表
    @Override
    public ListView<String> getServicesOfServer(int pageNo, int pageSize, String groupName, AbstractSelector selector)
            throws NacosException {
        return serverProxy.getServiceList(pageNo, pageSize, groupName, selector);
    }

    @Override
    public List<ServiceInfo> getSubscribeServices() {
        return hostReactor.getSubscribeServices();
    }

    @Override
    public String getServerStatus() {
        return serverProxy.serverHealthy() ? "UP" : "DOWN";
    }

    public BeatReactor getBeatReactor() {
        return beatReactor;
    }

    @Override
    public void shutDown() throws NacosException {
        beatReactor.shutdown();
        hostReactor.shutdown();
        serverProxy.shutdown();
    }
}
