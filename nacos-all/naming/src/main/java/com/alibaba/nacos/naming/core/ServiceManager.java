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

package com.alibaba.nacos.naming.core;

import com.alibaba.nacos.api.common.Constants;
import com.alibaba.nacos.api.exception.NacosException;
import com.alibaba.nacos.api.naming.utils.NamingUtils;
import com.alibaba.nacos.common.utils.IPUtil;
import com.alibaba.nacos.common.utils.JacksonUtils;
import com.alibaba.nacos.core.cluster.Member;
import com.alibaba.nacos.core.cluster.ServerMemberManager;
import com.alibaba.nacos.naming.consistency.ConsistencyService;
import com.alibaba.nacos.naming.consistency.Datum;
import com.alibaba.nacos.naming.consistency.KeyBuilder;
import com.alibaba.nacos.naming.consistency.RecordListener;
import com.alibaba.nacos.naming.consistency.persistent.raft.RaftPeer;
import com.alibaba.nacos.naming.consistency.persistent.raft.RaftPeerSet;
import com.alibaba.nacos.naming.misc.GlobalExecutor;
import com.alibaba.nacos.naming.misc.Loggers;
import com.alibaba.nacos.naming.misc.Message;
import com.alibaba.nacos.naming.misc.NetUtils;
import com.alibaba.nacos.naming.misc.ServiceStatusSynchronizer;
import com.alibaba.nacos.naming.misc.SwitchDomain;
import com.alibaba.nacos.naming.misc.Synchronizer;
import com.alibaba.nacos.naming.misc.UtilsAndCommons;
import com.alibaba.nacos.naming.pojo.InstanceOperationContext;
import com.alibaba.nacos.naming.pojo.InstanceOperationInfo;
import com.alibaba.nacos.naming.push.PushService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.google.common.collect.Sets;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.alibaba.nacos.naming.misc.UtilsAndCommons.UPDATE_INSTANCE_METADATA_ACTION_REMOVE;
import static com.alibaba.nacos.naming.misc.UtilsAndCommons.UPDATE_INSTANCE_METADATA_ACTION_UPDATE;

/**
 * Core manager storing all services in Nacos.
 *
 * @author nkorange
 *
 * Nacos 中所有 Service 的核心管理者。
 * 其中一个很重要的属性是 serviceMap，就是 Nacos 中的服务注册表。
 * 该类中有很多的方法，这些方法可以完成在 Nacos 集群中相关操作的同步。
 */
@Component
public class ServiceManager implements RecordListener<Service> {

    // 服务端本地注册表，结构为：Map(namespace, Map(group::serviceName, Service)).
    private final Map<String, Map<String, Service>> serviceMap = new ConcurrentHashMap<>();
    // 存放的是来自于其它 Server 端的服务状态发生变更的服务
    private final LinkedBlockingDeque<ServiceKey> toBeUpdatedServicesQueue = new LinkedBlockingDeque<>(1024 * 1024);
    // Server 状态同步器。
    private final Synchronizer synchronizer = new ServiceStatusSynchronizer();

    private final Lock lock = new ReentrantLock();

    // 一致性服务
    @Resource(name = "consistencyDelegate")
    private ConsistencyService consistencyService;

    private final SwitchDomain switchDomain;

    private final DistroMapper distroMapper;

    private final ServerMemberManager memberManager;

    private final PushService pushService;

    private final RaftPeerSet raftPeerSet;

    private int maxFinalizeCount = 3;

    private final Object putServiceLock = new Object();

    @Value("${nacos.naming.empty-service.auto-clean:false}")
    private boolean emptyServiceAutoClean;

    @Value("${nacos.naming.empty-service.clean.initial-delay-ms:60000}")
    private int cleanEmptyServiceDelay;

    @Value("${nacos.naming.empty-service.clean.period-time-ms:20000}")
    private int cleanEmptyServicePeriod;

    public ServiceManager(SwitchDomain switchDomain, DistroMapper distroMapper, ServerMemberManager memberManager,
            PushService pushService, RaftPeerSet raftPeerSet) {
        this.switchDomain = switchDomain;
        this.distroMapper = distroMapper;
        this.memberManager = memberManager;
        this.pushService = pushService;
        this.raftPeerSet = raftPeerSet;
    }

    /**
     * Nacos Server 间通信。Init service maneger.
     */
    @PostConstruct
    public void init() {
        // 启动一个定时任务：当前 Server 每 60s 会向其他 Nacos Server 发送一次本地注册表
        // 本机注册表是以各个服务的 checkSum（字符串拼接）的形式被发送的
        GlobalExecutor.scheduleServiceReporter(new ServiceReporter(), 60000, TimeUnit.MILLISECONDS);
        // 从其他 Naocs Server 获取到注册表中的所有实例 Instance 数据的最新的状态并更新到本地注册表。（方法中使用 while-ture 的无限循环）
        GlobalExecutor.submitServiceUpdateManager(new UpdatedServiceProcessor());

        if (emptyServiceAutoClean) {
            // emptyServiceAutoClean 默认配置 nacos-console：nacos.naming.empty-service.auto-clean=true
            Loggers.SRV_LOG.info("open empty service auto clean job, initialDelay : {} ms, period : {} ms",
                    cleanEmptyServiceDelay, cleanEmptyServicePeriod);

            // delay 60s, period 20s;

            // This task is not recommended to be performed frequently in order to avoid
            // the possibility that the service cache information may just be deleted
            // and then created due to the heartbeat mechanism
            // 启动了一个定时任务：每隔 30s 清理一次注册表中的空 Service。（空 Service 即为没有任何 Instance 的 Service）
            GlobalExecutor.scheduleServiceAutoClean(new EmptyServiceAutoClean(), cleanEmptyServiceDelay,
                    cleanEmptyServicePeriod);
        }

        try {
            Loggers.SRV_LOG.info("listen for service meta change");
            consistencyService.listen(KeyBuilder.SERVICE_META_KEY_PREFIX, this);
        } catch (NacosException e) {
            Loggers.SRV_LOG.error("listen for service meta change failed!");
        }
    }

    public Map<String, Service> chooseServiceMap(String namespaceId) {
        return serviceMap.get(namespaceId);
    }

    /**
     * Add a service into queue to update.
     *
     * @param namespaceId namespace
     * @param serviceName service name
     * @param serverIP    target server ip
     * @param checksum    checksum of service
     */
    public void addUpdatedServiceToQueue(String namespaceId, String serviceName, String serverIP, String checksum) {
        lock.lock();
        try {
            toBeUpdatedServicesQueue
                    .offer(new ServiceKey(namespaceId, serviceName, serverIP, checksum), 5, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            toBeUpdatedServicesQueue.poll();
            toBeUpdatedServicesQueue.add(new ServiceKey(namespaceId, serviceName, serverIP, checksum));
            Loggers.SRV_LOG.error("[DOMAIN-STATUS] Failed to add service to be updated to queue.", e);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public boolean interests(String key) {
        return KeyBuilder.matchServiceMetaKey(key) && !KeyBuilder.matchSwitchKey(key);
    }

    @Override
    public boolean matchUnlistenKey(String key) {
        return KeyBuilder.matchServiceMetaKey(key) && !KeyBuilder.matchSwitchKey(key);
    }

    @Override
    public void onChange(String key, Service service) throws Exception {
        try {
            if (service == null) {
                Loggers.SRV_LOG.warn("received empty push from raft, key: {}", key);
                return;
            }

            if (StringUtils.isBlank(service.getNamespaceId())) {
                service.setNamespaceId(Constants.DEFAULT_NAMESPACE_ID);
            }

            Loggers.RAFT.info("[RAFT-NOTIFIER] datum is changed, key: {}, value: {}", key, service);

            Service oldDom = getService(service.getNamespaceId(), service.getName());

            if (oldDom != null) {
                oldDom.update(service);
                // re-listen to handle the situation when the underlying listener is removed:
                consistencyService
                        .listen(KeyBuilder.buildInstanceListKey(service.getNamespaceId(), service.getName(), true),
                                oldDom);
                consistencyService
                        .listen(KeyBuilder.buildInstanceListKey(service.getNamespaceId(), service.getName(), false),
                                oldDom);
            } else {
                putServiceAndInit(service);
            }
        } catch (Throwable e) {
            Loggers.SRV_LOG.error("[NACOS-SERVICE] error while processing service update", e);
        }
    }

    @Override
    public void onDelete(String key) throws Exception {
        String namespace = KeyBuilder.getNamespace(key);
        String name = KeyBuilder.getServiceName(key);
        Service service = chooseServiceMap(namespace).get(name);
        Loggers.RAFT.info("[RAFT-NOTIFIER] datum is deleted, key: {}", key);

        if (service != null) {
            service.destroy();
            String ephemeralInstanceListKey = KeyBuilder.buildInstanceListKey(namespace, name, true);
            String persistInstanceListKey = KeyBuilder.buildInstanceListKey(namespace, name, false);
            consistencyService.remove(ephemeralInstanceListKey);
            consistencyService.remove(persistInstanceListKey);

            // remove listeners of key to avoid mem leak
            consistencyService.unListen(ephemeralInstanceListKey, service);
            consistencyService.unListen(persistInstanceListKey, service);
            consistencyService.unListen(KeyBuilder.buildServiceMetaKey(namespace, name), service);
            Loggers.SRV_LOG.info("[DEAD-SERVICE] {}", service.toJson());
        }

        chooseServiceMap(namespace).remove(name);
    }

    private class UpdatedServiceProcessor implements Runnable {

        // 从其他 Naocs Server 获取到注册表中的所有实例数据最新的状态并更新到本地注册表
        // get changed service from other server asynchronously
        @Override
        public void run() {
            ServiceKey serviceKey = null;

            try {
                // 开启无限循环
                while (true) {
                    try {
                        // toBeUpdatedServicesQueue 中存放的是来自于其它 Nacos Server 的服务状态发生变更的服务
                        // 从这个队里中取出一个元素
                        serviceKey = toBeUpdatedServicesQueue.take();
                    } catch (Exception e) {
                        Loggers.EVT_LOG.error("[UPDATE-DOMAIN] Exception while taking item from LinkedBlockingDeque.");
                    }

                    if (serviceKey == null) {
                        continue;
                    }

                    // 另起新线程来执行 ServiceUpdater 任务
                    GlobalExecutor.submitServiceUpdate(new ServiceUpdater(serviceKey));
                }
            } catch (Exception e) {
                Loggers.EVT_LOG.error("[UPDATE-DOMAIN] Exception while update service: {}", serviceKey, e);
            }
        }
    }

    private class ServiceUpdater implements Runnable {

        String namespaceId;

        String serviceName;

        String serverIP;

        public ServiceUpdater(ServiceKey serviceKey) {
            this.namespaceId = serviceKey.getNamespaceId();
            this.serviceName = serviceKey.getServiceName();
            this.serverIP = serviceKey.getServerIP();
        }

        @Override
        public void run() {
            try {
                // 更新健康状态
                updatedHealthStatus(namespaceId, serviceName, serverIP);
            } catch (Exception e) {
                Loggers.SRV_LOG
                        .warn("[DOMAIN-UPDATER] Exception while update service: {} from {}, error: {}", serviceName,
                                serverIP, e);
            }
        }
    }

    public RaftPeer getMySelfClusterState() {
        return raftPeerSet.local();
    }

    /**
     * Update health status of instance in service.
     *
     * @param namespaceId namespace
     * @param serviceName service name
     * @param serverIP    source server Ip
     */
    public void updatedHealthStatus(String namespaceId, String serviceName, String serverIP) {
        // 从其它 Nacos Server 获取指定服务的数据
        Message msg = synchronizer.get(serverIP, UtilsAndCommons.assembleFullServiceName(namespaceId, serviceName));
        JsonNode serviceJson = JacksonUtils.toObj(msg.getData());

        ArrayNode ipList = (ArrayNode) serviceJson.get("ips");

        // ipsMap 存放的是来自于其它 Nacos Server 的指定服务所包含的所有 instance 的健康状态
        // ipsMap 的 key 为 ip:port，value 为 healthy
        Map<String, String> ipsMap = new HashMap<>(ipList.size());
        for (int i = 0; i < ipList.size(); i++) {
            // ip 的格式是：ip:port_healthy
            String ip = ipList.get(i).asText();
            String[] strings = ip.split("_");
            // 将当前遍历到的 instance 数据存入到 ipsMap 中，key 为 ip:port，value 为 healthy
            ipsMap.put(strings[0], strings[1]);
        }

        // 从本地注册表中获取到当前（指定的）服务
        Service service = getService(namespaceId, serviceName);

        if (service == null) {
            return;
        }

        boolean changed = false;
        // 获取到本地注册表中当前服务的所有 instance
        List<Instance> instances = service.allIPs();
        // 遍历本地注册表中当前服务的所有 instance
        for (Instance instance : instances) {
            // 获取当前遍历的 instance 在其它 Nacos Server 中的健康状态
            boolean valid = Boolean.parseBoolean(ipsMap.get(instance.toIpAddr()));
            // 若当前 instance 在本地注册表中的健康状态与其它 Nacos Server 中的健康状态不一致，表示发生了变更，则以其它 Nacos Server 中的健康状态为准
            if (valid != instance.isHealthy()) {
                // 标记发生了变更
                changed = true;
                // 使用其它 Nacos Server 中的标记的健康状态替换掉本地注册表中的健康状态
                instance.setHealthy(valid);
                Loggers.EVT_LOG.info("{} {SYNC} IP-{} : {}:{}@{}", serviceName,
                        (instance.isHealthy() ? "ENABLED" : "DISABLED"), instance.getIp(), instance.getPort(),
                        instance.getClusterName());
            }
        }

        // 只要由一个 Instance 的状态发生了变更，那么这个 changed 的值就为 true
        if (changed) {
            // 发布状态变更事件
            pushService.serviceChanged(service);
            if (Loggers.EVT_LOG.isDebugEnabled()) {
                StringBuilder stringBuilder = new StringBuilder();
                List<Instance> allIps = service.allIPs();
                for (Instance instance : allIps) {
                    stringBuilder.append(instance.toIpAddr()).append("_").append(instance.isHealthy()).append(",");
                }
                Loggers.EVT_LOG
                        .debug("[HEALTH-STATUS-UPDATED] namespace: {}, service: {}, ips: {}", service.getNamespaceId(),
                                service.getName(), stringBuilder.toString());
            }
        }

    }

    public Set<String> getAllServiceNames(String namespaceId) {
        return serviceMap.get(namespaceId).keySet();
    }

    public Map<String, Set<String>> getAllServiceNames() {
        // 存放当前本地注册表中所有服务的名称。key：namespaceId；value：存放当前 namespace 中所有 Service 名称的 Set 集合。
        Map<String, Set<String>> namesMap = new HashMap<>(16);
        for (String namespaceId : serviceMap.keySet()) {
            // serviceMap.get(namespaceId) 是注册表的内层 map，其 keySet 为所有服务的名称（groupId@@微服务名称）
            namesMap.put(namespaceId, serviceMap.get(namespaceId).keySet());
        }
        return namesMap;
    }

    public Set<String> getAllNamespaces() {
        return serviceMap.keySet();
    }

    public List<String> getAllServiceNameList(String namespaceId) {
        if (chooseServiceMap(namespaceId) == null) {
            return new ArrayList<>();
        }
        return new ArrayList<>(chooseServiceMap(namespaceId).keySet());
    }

    public Map<String, Set<Service>> getResponsibleServices() {
        Map<String, Set<Service>> result = new HashMap<>(16);
        for (String namespaceId : serviceMap.keySet()) {
            result.put(namespaceId, new HashSet<>());
            for (Map.Entry<String, Service> entry : serviceMap.get(namespaceId).entrySet()) {
                Service service = entry.getValue();
                if (distroMapper.responsible(entry.getKey())) {
                    result.get(namespaceId).add(service);
                }
            }
        }
        return result;
    }

    public int getResponsibleServiceCount() {
        int serviceCount = 0;
        for (String namespaceId : serviceMap.keySet()) {
            for (Map.Entry<String, Service> entry : serviceMap.get(namespaceId).entrySet()) {
                if (distroMapper.responsible(entry.getKey())) {
                    serviceCount++;
                }
            }
        }
        return serviceCount;
    }

    public int getResponsibleInstanceCount() {
        Map<String, Set<Service>> responsibleServices = getResponsibleServices();
        int count = 0;
        for (String namespaceId : responsibleServices.keySet()) {
            for (Service service : responsibleServices.get(namespaceId)) {
                count += service.allIPs().size();
            }
        }

        return count;
    }

    /**
     * Fast remove service.
     *
     * <p>Remove service bu async.
     *
     * @param namespaceId namespace
     * @param serviceName service name
     * @throws Exception exception
     *
     * 执行清除服务
     */
    public void easyRemoveService(String namespaceId, String serviceName) throws Exception {

        Service service = getService(namespaceId, serviceName);
        if (service == null) {
            throw new IllegalArgumentException("specified service not exist, serviceName : " + serviceName);
        }
        // 利用一致性服务，在所有 Nacos Server 中删除这个服务
        consistencyService.remove(KeyBuilder.buildServiceMetaKey(namespaceId, serviceName));
    }

    public void addOrReplaceService(Service service) throws NacosException {
        // 将这个 Server 的数据同步到所有 Nacos Server 上。「保持数据一致」
        consistencyService.put(KeyBuilder.buildServiceMetaKey(service.getNamespaceId(), service.getName()), service);
    }

    public void createEmptyService(String namespaceId, String serviceName, boolean local) throws NacosException {
        // local 为 true，表示当前实例为临时实例
        createServiceIfAbsent(namespaceId, serviceName, local, null);
    }

    /**
     * Create service if not exist.
     *
     * @param namespaceId namespace
     * @param serviceName service name
     * @param local local 为 true，表示当前实例为临时实例。whether create service by local
     * @param cluster     cluster
     * @throws NacosException nacos exception
     *
     * 如果注册表中没有当前将要注册的服务，则创建这个服务对应的 Service ，并添加进注册表中，
     * 此时 Service 实例仅保存了该服务的 namespaceId 和 serviceName，不包含服务的具体主机的 Instance 数据。
     */
    public void createServiceIfAbsent(String namespaceId, String serviceName, boolean local, Cluster cluster)
            throws NacosException {
        // 根据 namespaceId 和 serviceName 在本地注册表中查找当前准备注册的服务对应的 Service
        Service service = getService(namespaceId, serviceName);
        if (service == null) {
            // 若当前要注册的服务的 Instance 是这个服务的第一个实例，则注册表中没有该服务的 Service
            // 此时要创建一个当前服务的 Service，并封装该服务的 serviceName、namespaceId 等信息
            Loggers.SRV_LOG.info("creating empty service {}:{}", namespaceId, serviceName);
            service = new Service();
            service.setName(serviceName);
            service.setNamespaceId(namespaceId);
            service.setGroupName(NamingUtils.getGroupName(serviceName));
            // now validate the service. if failed, exception will be thrown
            service.setLastModifiedMillis(System.currentTimeMillis());
            // 重新计算校验和，即当前 Service 的所有 SCI「'service --> cluster --> instance' model」 信息的字符串拼接。
            service.recalculateChecksum();
            if (cluster != null) {// 当属于注册请求时，cluster 为 null
                // Cluster 与 Service 互相建立联系
                cluster.setService(service);
                service.getClusterMap().put(cluster.getName(), cluster);
            }
            service.validate();
            // 将上述创建好的 Service 写入到注册表中。
            putServiceAndInit(service);
            // local 为 true，表示当前服务实例为临时实例
            if (!local) {
                // 同步持久实例数据「将当前 Nacos Server 中的数据同步到其他 Nacos Server 上」
                addOrReplaceService(service);
            }
        }
    }

    /**
     * Register an instance to a service in AP mode.
     *
     * <p>This method creates service or cluster silently if they don't exist.
     *
     * @param namespaceId id of namespace
     * @param serviceName service name
     * @param instance    instance to register
     * @throws Exception any error occurred in the process
     */
    public void registerInstance(String namespaceId, String serviceName, Instance instance) throws NacosException {
        // 如果注册表中没有当前将要注册的服务，则创建这个服务对应的 Service ，并添加进注册表中，
        // 1.此时注册表仅仅保存了该服务的 namespaceId 和 serviceName 等信息，与该服务的具体主机 Instance 实例数据无关。
        // 2.同时开启定时清除过期的服务实例数据任务，开启当前服务所包含的所有 Cluster 的健康检测任务
        // 3.针对当前注册的实例的持久实例、临时实例，在 Nacos Server 集群中添加监听
        createEmptyService(namespaceId, serviceName, instance.isEphemeral());
        // 从注册表中获取当前准备注册的服务对应的 Service。
        // (若当前要注册的服务的 Instance 是这个服务的第一个实例，则上一步已经完成的注册，只注册了该服务的 namespaceId 和 serviceName，并没有具体主机的 Instance 实例数据)
        Service service = getService(namespaceId, serviceName);
        // 检验是否已将服务注册进注册表中，如果此时注册表中没有相关数据，则直接抛出异常
        if (service == null) {
            throw new NacosException(NacosException.INVALID_PARAM,
                    "service not found, namespace: " + namespaceId + ", service: " + serviceName);
        }
        // 利用一致性服务，将当前准备注册的服务的 Instance 实例数据注册到所有 Nacos Server 中
        addInstance(namespaceId, serviceName, instance.isEphemeral(), instance);
    }

    /**
     * Update instance to service.
     *
     * @param namespaceId namespace
     * @param serviceName service name
     * @param instance    instance
     * @throws NacosException nacos exception
     */
    public void updateInstance(String namespaceId, String serviceName, Instance instance) throws NacosException {

        Service service = getService(namespaceId, serviceName);

        if (service == null) {
            throw new NacosException(NacosException.INVALID_PARAM,
                    "service not found, namespace: " + namespaceId + ", service: " + serviceName);
        }

        if (!service.allIPs().contains(instance)) {
            throw new NacosException(NacosException.INVALID_PARAM, "instance not exist: " + instance);
        }

        addInstance(namespaceId, serviceName, instance.isEphemeral(), instance);
    }

    /**
     * Update instance's metadata.
     *
     * @param namespaceId namespace
     * @param serviceName service name
     * @param action      update or remove
     * @param ips         need update instances
     * @param metadata    target metadata
     * @return update succeed instances
     * @throws NacosException nacos exception
     */
    public List<Instance> updateMetadata(String namespaceId, String serviceName, boolean isEphemeral, String action,
            boolean all, List<Instance> ips, Map<String, String> metadata) throws NacosException {

        Service service = getService(namespaceId, serviceName);

        if (service == null) {
            throw new NacosException(NacosException.INVALID_PARAM,
                    "service not found, namespace: " + namespaceId + ", service: " + serviceName);
        }

        List<Instance> locatedInstance = getLocatedInstance(namespaceId, serviceName, isEphemeral, all, ips);

        if (CollectionUtils.isEmpty(locatedInstance)) {
            throw new NacosException(NacosException.INVALID_PARAM, "not locate instances, input instances: " + ips);
        }

        if (UPDATE_INSTANCE_METADATA_ACTION_UPDATE.equals(action)) {
            locatedInstance.forEach(ele -> ele.getMetadata().putAll(metadata));
        } else if (UPDATE_INSTANCE_METADATA_ACTION_REMOVE.equals(action)) {
            Set<String> removeKeys = metadata.keySet();
            for (String removeKey : removeKeys) {
                locatedInstance.forEach(ele -> ele.getMetadata().remove(removeKey));
            }
        }
        Instance[] instances = new Instance[locatedInstance.size()];
        locatedInstance.toArray(instances);

        addInstance(namespaceId, serviceName, isEphemeral, instances);

        return locatedInstance;
    }

    /**
     * locate consistency's datum by all or instances provided.
     *
     * @param namespaceId        namespace
     * @param serviceName        serviceName
     * @param isEphemeral        isEphemeral
     * @param all                get from consistencyService directly
     * @param waitLocateInstance instances provided
     * @return located instances
     * @throws NacosException nacos exception
     */
    public List<Instance> getLocatedInstance(String namespaceId, String serviceName, boolean isEphemeral, boolean all,
            List<Instance> waitLocateInstance) throws NacosException {
        List<Instance> locatedInstance;

        //need the newest data from consistencyService
        Datum datum = consistencyService.get(KeyBuilder.buildInstanceListKey(namespaceId, serviceName, isEphemeral));
        if (datum == null) {
            throw new NacosException(NacosException.NOT_FOUND,
                    "instances from consistencyService not exist, namespace: " + namespaceId + ", service: "
                            + serviceName + ", ephemeral: " + isEphemeral);
        }

        if (all) {
            locatedInstance = ((Instances) datum.value).getInstanceList();
        } else {
            locatedInstance = new ArrayList<>();
            for (Instance instance : waitLocateInstance) {
                Instance located = locateInstance(((Instances) datum.value).getInstanceList(), instance);
                if (located == null) {
                    continue;
                }
                locatedInstance.add(located);
            }
        }

        return locatedInstance;
    }

    private Instance locateInstance(List<Instance> instances, Instance instance) {
        int target = 0;
        while (target >= 0) {
            target = instances.indexOf(instance);
            if (target >= 0) {
                Instance result = instances.get(target);
                if (result.getClusterName().equals(instance.getClusterName())) {
                    return result;
                }
                instances.remove(target);
            }
        }
        return null;
    }

    /**
     * Add instance to service.
     *
     * @param namespaceId namespace
     * @param serviceName service name
     * @param ephemeral   whether instance is ephemeral
     * @param ips         instances
     * @throws NacosException nacos exception
     */
    public void addInstance(String namespaceId, String serviceName, boolean ephemeral, Instance... ips)
            throws NacosException {
        // ips: Instance；key 区分临时实例与持久实例
        String key = KeyBuilder.buildInstanceListKey(namespaceId, serviceName, ephemeral);
        // 从注册表中获取当前准备注册的服务 Service 数据，此时 Service 中没有具体的主机 Instance 实例数据
        // (若当前要注册的服务的 Instance 是这个服务的第一个实例，则该 Service 与具体主机的 Instance 实例数据无关)
        Service service = getService(namespaceId, serviceName);

        synchronized (service) {
            // 将当前准备注册的服务的 Instance 实例数据添加到 Service 中，即写入到了注册表中。ips: 要注册的 Instance
            // 返回的 instanceList 是本次注册的服务相关的所有 Instance 实例集合。
            List<Instance> instanceList = addIpAddresses(service, ephemeral, ips);

            Instances instances = new Instances();
            instances.setInstanceList(instanceList);

            // 利用一致性服务，将当前注册的服务的所有实例数据同步给所有 Nacos Server
            consistencyService.put(key, instances);
        }
    }

    /**
     * Remove instance from service.
     *
     * @param namespaceId namespace
     * @param serviceName service name
     * @param ephemeral   whether instance is ephemeral
     * @param ips         instances
     * @throws NacosException nacos exception
     *
     * 注销（删除）Instance 实例
     */
    public void removeInstance(String namespaceId, String serviceName, boolean ephemeral, Instance... ips)
            throws NacosException {
        // 在本地注册表中获取本次注销服务所对应的 Service 数据
        Service service = getService(namespaceId, serviceName);

        synchronized (service) {
            // 注销（删除）该服务
            removeInstance(namespaceId, serviceName, ephemeral, service, ips);
        }
    }

    private void removeInstance(String namespaceId, String serviceName, boolean ephemeral, Service service,
            Instance... ips) throws NacosException {

        String key = KeyBuilder.buildInstanceListKey(namespaceId, serviceName, ephemeral);
        // 执行变更方法，本次为 REMOVE，返回变更数据
        List<Instance> instanceList = substractIpAddresses(service, ephemeral, ips);

        Instances instances = new Instances();
        instances.setInstanceList(instanceList);
        // 将本次变更同步给集群中所有的 Nacos Server。(一致性服务，一致性算法)
        consistencyService.put(key, instances);
    }

    public Instance getInstance(String namespaceId, String serviceName, String cluster, String ip, int port) {
        Service service = getService(namespaceId, serviceName);
        if (service == null) {
            return null;
        }

        List<String> clusters = new ArrayList<>();
        clusters.add(cluster);

        List<Instance> ips = service.allIPs(clusters);
        if (ips == null || ips.isEmpty()) {
            return null;
        }

        for (Instance instance : ips) {
            if (instance.getIp().equals(ip) && instance.getPort() == port) {
                return instance;
            }
        }

        return null;
    }

    /**
     * batch operate kinds of resources.
     *
     * @param namespace       namespace.
     * @param operationInfo   operation resources description.
     * @param operateFunction some operation defined by kinds of situation.
     */
    public List<Instance> batchOperate(String namespace, InstanceOperationInfo operationInfo,
            Function<InstanceOperationContext, List<Instance>> operateFunction) {
        List<Instance> operatedInstances = new ArrayList<>();
        try {
            String serviceName = operationInfo.getServiceName();
            NamingUtils.checkServiceNameFormat(serviceName);
            // type: ephemeral/persist
            InstanceOperationContext operationContext;
            String type = operationInfo.getConsistencyType();
            if (!StringUtils.isEmpty(type)) {
                switch (type) {
                    case UtilsAndCommons.EPHEMERAL:
                        operationContext = new InstanceOperationContext(namespace, serviceName, true, true);
                        operatedInstances.addAll(operateFunction.apply(operationContext));
                        break;
                    case UtilsAndCommons.PERSIST:
                        operationContext = new InstanceOperationContext(namespace, serviceName, false, true);
                        operatedInstances.addAll(operateFunction.apply(operationContext));
                        break;
                    default:
                        Loggers.SRV_LOG
                                .warn("UPDATE-METADATA: services.all value is illegal, it should be ephemeral/persist. ignore the service '"
                                        + serviceName + "'");
                        break;
                }
            } else {
                List<Instance> instances = operationInfo.getInstances();
                if (!CollectionUtils.isEmpty(instances)) {
                    //ephemeral:instances or persist:instances
                    Map<Boolean, List<Instance>> instanceMap = instances.stream()
                            .collect(Collectors.groupingBy(ele -> ele.isEphemeral()));

                    for (Map.Entry<Boolean, List<Instance>> entry : instanceMap.entrySet()) {
                        operationContext = new InstanceOperationContext(namespace, serviceName, entry.getKey(), false,
                                entry.getValue());
                        operatedInstances.addAll(operateFunction.apply(operationContext));
                    }
                }
            }
        } catch (Exception e) {
            Loggers.SRV_LOG.warn("UPDATE-METADATA: update metadata failed, ignore the service '" + operationInfo
                    .getServiceName() + "'", e);
        }
        return operatedInstances;
    }

    /**
     * Compare and get new instance list.
     * @param service   service
     * @param action    {@link UtilsAndCommons#UPDATE_INSTANCE_ACTION_REMOVE} or {@link UtilsAndCommons#UPDATE_INSTANCE_ACTION_ADD}
     * @param ephemeral whether instance is ephemeral
     * @param ips       instances
     * @return instance list after operation
     * @throws NacosException nacos exception
     *
     * 修改当前 Service 的 Instance 的列表，分为"添加实例"与"删除实例"两种情况。
     * 并返回本次注册的服务相关的所有 Instance 实例集合
     */
    public List<Instance> updateIpAddresses(Service service, String action, boolean ephemeral, Instance... ips)
            throws NacosException {
        // 根据当前要变更的服务的 namespaceId 和 serviceName 从其它 Nacos Server 端中查找对应的数据。即"远程"实例数据
        Datum datum = consistencyService
                .get(KeyBuilder.buildInstanceListKey(service.getNamespaceId(), service.getName(), ephemeral));
        // 获取本地注册表中所有当前服务的主机 Instance 实例数据。（ephemeral=treu：临时实例；ephemeral=false：临时实例+持久实例）
        List<Instance> currentIPs = service.allIPs(ephemeral);
        Map<String, Instance> currentInstances = new HashMap<>(currentIPs.size());

        Set<String> currentInstanceIds = Sets.newHashSet();
        // 遍历从本地注册表中获取到的所有 Instance 实例数据
        for (Instance instance : currentIPs) {
            // currentInstances 保存了本地注册表中当前服务的所有主机实例数据。key：ip:port；value:instance。
            currentInstances.put(instance.toIpAddr(), instance);
            // key:instanceId
            currentInstanceIds.add(instance.getInstanceId());
        }

        // 统计出本地注册表与远程注册表中当前服务的"最新实例"集合（本地有远程数据，则以本地数据为主；若本地没有远程数据，则以远程数据为主）
        Map<String, Instance> instanceMap;
        if (datum != null && null != datum.value) {
            // instanceMap：封装了远程属于与本地数据对比后的最新数据。
            instanceMap = setValid(((Instances) datum.value).getInstanceList(), currentInstances);
        } else {
            instanceMap = new HashMap<>(ips.length);
        }

        // ips：就是要变更的数据。(注册-添加的数据或删除的数据)
        for (Instance instance : ips) {
            if (!service.getClusterMap().containsKey(instance.getClusterName())) {
                // 若当前要变更的 Service 中不包含准备变更的主机实例 Instance 所属的 Cluster，则为这个 Service 创建一个新集群 Cluster。
                // 使用当前遍历到的准备变更的主机实例 Instance 所属的 ClusterName 和当前要变更的 Service 创建新 Cluster
                Cluster cluster = new Cluster(instance.getClusterName(), service);
                // 初始化新 Cluster 的健康检测任务
                cluster.init();
                // 建立要变更的 Instance 实例数据与 Service 之间的关系
                // 即将当前变更的 Instance 实例数据与新 Cluster 存入到当前 Service 中
                service.getClusterMap().put(instance.getClusterName(), cluster);
                Loggers.SRV_LOG
                        .warn("cluster: {} not found, ip: {}, will create new cluster with default configuration.",
                                instance.getClusterName(), instance.toJson());
            }

            if (UtilsAndCommons.UPDATE_INSTANCE_ACTION_REMOVE.equals(action)) {
                // REMOVE。针对删除请求，将当前要变更的 Instance 数据从"最新实例"集合中删除
                instanceMap.remove(instance.getDatumKey());
            } else {
                // ADD。针对添加-注册请求
                Instance oldInstance = instanceMap.get(instance.getDatumKey());
                if (oldInstance != null) {
                    instance.setInstanceId(oldInstance.getInstanceId());
                } else {
                    instance.setInstanceId(instance.generateInstanceId(currentInstanceIds));
                }
                // 将当前需要变更的 Instance 数据添加进"最新实例"集合中
                instanceMap.put(instance.getDatumKey(), instance);
            }

        }

        if (instanceMap.size() <= 0 && UtilsAndCommons.UPDATE_INSTANCE_ACTION_ADD.equals(action)) {
            throw new IllegalArgumentException(
                    "ip list can not be empty, service: " + service.getName() + ", ip list: " + JacksonUtils
                            .toJson(instanceMap.values()));
        }
        // instanceMap：包含本次变更的服务的所有 Instance 的最新数据。
        return new ArrayList<>(instanceMap.values());
    }

    private List<Instance> substractIpAddresses(Service service, boolean ephemeral, Instance... ips)
            throws NacosException {
        // updateIpAddresses 变更分两种情况：1.ADD；2.REMOVE。本次为 REMOVE
        return updateIpAddresses(service, UtilsAndCommons.UPDATE_INSTANCE_ACTION_REMOVE, ephemeral, ips);
    }

    private List<Instance> addIpAddresses(Service service, boolean ephemeral, Instance... ips) throws NacosException {
        // 修改当前 Service 的 Instance 的列表，分两种情况：1.ADD-添加实例；2.REMOVE-删除实例。本次为添加实例 ips
        return updateIpAddresses(service, UtilsAndCommons.UPDATE_INSTANCE_ACTION_ADD, ephemeral, ips);
    }

    private Map<String, Instance> setValid(List<Instance> oldInstances, Map<String, Instance> map) {
        // oldInstances：即"远程"实例数据；map：本地注册表中的主机实例数据
        Map<String, Instance> instanceMap = new HashMap<>(oldInstances.size());
        // 遍历"远程"实例数据
        for (Instance instance : oldInstances) {
            // 根据"远程"实例数据的"ip:port"，从本地注册表中查找当前服务的主机实例数据
            Instance instance1 = map.get(instance.toIpAddr());
            if (instance1 != null) {
                // 若当前遍历到的"远程"主机实例数据在本地注册表中也存在，则以本地注册表中数据为主，将"远程"主机实例数据替换掉。
                instance.setHealthy(instance1.isHealthy());
                instance.setLastBeat(instance1.getLastBeat());
            }
            // 即封装远程与本地数据对比后的最新的数据（本地有远程数据，则以本地数据为主；若本地没有远程数据，则以远程数据为主）
            instanceMap.put(instance.getDatumKey(), instance);
        }
        // 返回最新的数据
        return instanceMap;
    }

    public Service getService(String namespaceId, String serviceName) {
        if (serviceMap.get(namespaceId) == null) {
            return null;
        }
        return chooseServiceMap(namespaceId).get(serviceName);
    }

    public boolean containService(String namespaceId, String serviceName) {
        return getService(namespaceId, serviceName) != null;
    }

    /**
     * Put service into manager.
     *
     * @param service service
     */
    public void putService(Service service) {
        // DCL 双端检索，判断当前服务的 namespaceId 是否存在于注册表中
        if (!serviceMap.containsKey(service.getNamespaceId())) {
            synchronized (putServiceLock) {
                if (!serviceMap.containsKey(service.getNamespaceId())) {
                    // 要注册的服务的 namespaceId 在本地注册表中没有，则将这个 namespaceId 添加进服务端本地注册表中
                    serviceMap.put(service.getNamespaceId(), new ConcurrentSkipListMap<>());
                }
            }
        }
        // 将要注册的服务数据（Service）写入到本地注册表中
        serviceMap.get(service.getNamespaceId()).put(service.getName(), service);
    }

    private void putServiceAndInit(Service service) throws NacosException {
        // 将要注册的 Service 写入到本地注册表中。此时的注册表中仅保存了该服务的 namespaceId 和 serviceName，与该服务的具体主机的 Instance 数据无关。
        putService(service);
        // 初始化 Service 内部健康检测任务（1.开启定时清除过期的临时 Instance 实例数据任务；2.开启了当前服务所包含的所有 Cluster 的健康检测任务）
        service.init();
        // 给针对当前服务（Service）的持久实例、临时实例，在 Nacos Server 集群中添加监听
        consistencyService
                .listen(KeyBuilder.buildInstanceListKey(service.getNamespaceId(), service.getName(), true), service);
        consistencyService
                .listen(KeyBuilder.buildInstanceListKey(service.getNamespaceId(), service.getName(), false), service);
        Loggers.SRV_LOG.info("[NEW-SERVICE] {}", service.toJson());
    }

    /**
     * Search services.
     *
     * @param namespaceId namespace
     * @param regex       search regex
     * @return list of service which searched
     */
    public List<Service> searchServices(String namespaceId, String regex) {
        List<Service> result = new ArrayList<>();
        for (Map.Entry<String, Service> entry : chooseServiceMap(namespaceId).entrySet()) {
            Service service = entry.getValue();
            String key = service.getName() + ":" + ArrayUtils.toString(service.getOwners());
            if (key.matches(regex)) {
                result.add(service);
            }
        }

        return result;
    }

    public int getServiceCount() {
        int serviceCount = 0;
        for (String namespaceId : serviceMap.keySet()) {
            serviceCount += serviceMap.get(namespaceId).size();
        }
        return serviceCount;
    }

    public int getInstanceCount() {
        int total = 0;
        for (String namespaceId : serviceMap.keySet()) {
            for (Service service : serviceMap.get(namespaceId).values()) {
                total += service.allIPs().size();
            }
        }
        return total;
    }

    public int getPagedService(String namespaceId, int startPage, int pageSize, String param, String containedInstance,
            List<Service> serviceList, boolean hasIpCount) {

        List<Service> matchList;

        if (chooseServiceMap(namespaceId) == null) {
            return 0;
        }

        if (StringUtils.isNotBlank(param)) {
            StringJoiner regex = new StringJoiner(Constants.SERVICE_INFO_SPLITER);
            for (String s : param.split(Constants.SERVICE_INFO_SPLITER)) {
                regex.add(StringUtils.isBlank(s) ? Constants.ANY_PATTERN
                        : Constants.ANY_PATTERN + s + Constants.ANY_PATTERN);
            }
            matchList = searchServices(namespaceId, regex.toString());
        } else {
            matchList = new ArrayList<>(chooseServiceMap(namespaceId).values());
        }

        if (!CollectionUtils.isEmpty(matchList) && hasIpCount) {
            matchList = matchList.stream().filter(s -> !CollectionUtils.isEmpty(s.allIPs()))
                    .collect(Collectors.toList());
        }

        if (StringUtils.isNotBlank(containedInstance)) {

            boolean contained;
            for (int i = 0; i < matchList.size(); i++) {
                Service service = matchList.get(i);
                contained = false;
                List<Instance> instances = service.allIPs();
                for (Instance instance : instances) {
                    if (IPUtil.containsPort(containedInstance)) {
                        if (StringUtils.equals(instance.getIp() + IPUtil.IP_PORT_SPLITER + instance.getPort(), containedInstance)) {
                            contained = true;
                            break;
                        }
                    } else {
                        if (StringUtils.equals(instance.getIp(), containedInstance)) {
                            contained = true;
                            break;
                        }
                    }
                }
                if (!contained) {
                    matchList.remove(i);
                    i--;
                }
            }
        }

        if (pageSize >= matchList.size()) {
            serviceList.addAll(matchList);
            return matchList.size();
        }

        for (int i = 0; i < matchList.size(); i++) {
            if (i < startPage * pageSize) {
                continue;
            }

            serviceList.add(matchList.get(i));

            if (serviceList.size() >= pageSize) {
                break;
            }
        }

        return matchList.size();
    }

    public static class ServiceChecksum {

        public String namespaceId;

        // key 为服务名称（groupId@@微服务名称），value 为该服务对应的 checksum
        public Map<String, String> serviceName2Checksum = new HashMap<String, String>();

        public ServiceChecksum() {
            this.namespaceId = Constants.DEFAULT_NAMESPACE_ID;
        }

        public ServiceChecksum(String namespaceId) {
            this.namespaceId = namespaceId;
        }

        /**
         * Add service checksum.
         *
         * @param serviceName service name
         * @param checksum    checksum of service
         */
        public void addItem(String serviceName, String checksum) {
            if (StringUtils.isEmpty(serviceName) || StringUtils.isEmpty(checksum)) {
                Loggers.SRV_LOG.warn("[DOMAIN-CHECKSUM] serviceName or checksum is empty,serviceName: {}, checksum: {}",
                        serviceName, checksum);
                return;
            }
            serviceName2Checksum.put(serviceName, checksum);
        }
    }

    private class EmptyServiceAutoClean implements Runnable {

        // 清理注册表中的空 Service。（空 Service 即为没有任何 Instance 的 Service）
        @Override
        public void run() {

            // Parallel flow opening threshold
            // 并行流阈值，若当一个 namespace 中包含的 Service 数量超过阈值，则会将注册表创建为一个并行流，否则使用串行流
            int parallelSize = 100;

            // 遍历注册表。stringServiceMap 就是注册表的内层 map
            serviceMap.forEach((namespace, stringServiceMap) -> {
                Stream<Map.Entry<String, Service>> stream = null;

                // 若当前遍历到的元素(namespace)中所包含的服务的数量超过阈值，则创建一个并行流，否则创建串行流
                if (stringServiceMap.size() > parallelSize) {
                    // 并行流
                    stream = stringServiceMap.entrySet().parallelStream();
                } else {
                    // 串行流
                    stream = stringServiceMap.entrySet().stream();
                }

                // 筛选出需要当前 Server 处理的服务
                stream.filter(entry -> {
                    final String serviceName = entry.getKey();
                    // 只要当前遍历的服务需要当前 Server  负责，则通过过滤
                    return distroMapper.responsible(serviceName);
                }).forEach(entry -> stringServiceMap.computeIfPresent(entry.getKey(), (serviceName, service) -> {
                    // 空 Service，即没有任何 Instance 的 Service
                    if (service.isEmpty()) {

                        // To avoid violent Service removal, the number of times the Service
                        // experiences Empty is determined by finalizeCnt, and if the specified
                        // value is reached, it is removed
                        // 只有当前服务被标记为"空"的次数超过了最大阈值（3），才能进行删除操作
                        if (service.getFinalizeCount() > maxFinalizeCount) {
                            Loggers.SRV_LOG.warn("namespace : {}, [{}] services are automatically cleaned", namespace,
                                    serviceName);
                            try {
                                // 执行清除服务
                                easyRemoveService(namespace, serviceName);
                            } catch (Exception e) {
                                Loggers.SRV_LOG.error("namespace : {}, [{}] services are automatically clean has "
                                        + "error : {}", namespace, serviceName, e);
                            }
                        }
                        // 计数器加一
                        service.setFinalizeCount(service.getFinalizeCount() + 1);

                        Loggers.SRV_LOG
                                .debug("namespace : {}, [{}] The number of times the current service experiences "
                                                + "an empty instance is : {}", namespace, serviceName,
                                        service.getFinalizeCount());
                    } else {
                        // 将计数器归零
                        service.setFinalizeCount(0);
                    }
                    return service;
                }));
            });
        }
    }

    private class ServiceReporter implements Runnable {

        // 向其他 Nacos Server 发送一次本地注册表
        @Override
        public void run() {
            try {
                // 存放当前注册表中所有服务的名称。key：namespaceId；value：存放当前 namespace 中所有 Service 名称的 Set 集合。
                Map<String, Set<String>> allServiceNames = getAllServiceNames();

                if (allServiceNames.size() <= 0) {
                    //ignore
                    return;
                }
                // 遍历所有的 namespace
                for (String namespaceId : allServiceNames.keySet()) {

                    ServiceChecksum checksum = new ServiceChecksum(namespaceId);
                    // 遍历当前 namespace 中的所有服务名称
                    for (String serviceName : allServiceNames.get(namespaceId)) {
                        // 若当前服务不归当前 Server 负责，则直接跳过
                        if (!distroMapper.responsible(serviceName)) {
                            continue;
                        }
                        // 从注册表中获取到当前遍历到的服务
                        Service service = getService(namespaceId, serviceName);

                        if (service == null || service.isEmpty()) {
                            continue;
                        }
                        // 重新计算当前 Service 的 checksum
                        service.recalculateChecksum();
                        // 将重新计算好的 checksum 存入到 Map<String, String> serviceName2Checksum 中
                        checksum.addItem(serviceName, service.getChecksum());
                    }

                    Message msg = new Message();
                    // 将当前 namespace 中的所有服务对应的 checksum 封装到 msg 中，用于后续步骤发送给其它 Nacos Server
                    msg.setData(JacksonUtils.toJson(checksum));
                    // 获取到所有的 Nacos Server
                    Collection<Member> sameSiteServers = memberManager.allMembers();

                    if (sameSiteServers == null || sameSiteServers.size() <= 0) {
                        return;
                    }

                    // 遍历所有 Nacos Server，发送 msg
                    for (Member server : sameSiteServers) {
                        if (server.getAddress().equals(NetUtils.localServer())) {
                            // 跳过当前 Nacos Server 自身
                            continue;
                        }
                        // 发送 msg。（使用 Nacos 自研的 HttpClient 发送 POST 请求，扩展自 Apache 的 HttpClient）
                        // ServiceStatusSynchronizer.send()
                        synchronizer.send(server.getAddress(), msg);
                    }
                }
            } catch (Exception e) {
                Loggers.SRV_LOG.error("[DOMAIN-STATUS] Exception while sending service status", e);
            } finally {
                // 开启下一次定时任务
                GlobalExecutor.scheduleServiceReporter(this, switchDomain.getServiceStatusSynchronizationPeriodMillis(),
                        TimeUnit.MILLISECONDS);
            }
        }
    }

    private static class ServiceKey {

        private String namespaceId;

        private String serviceName;

        private String serverIP;

        private String checksum;

        public String getChecksum() {
            return checksum;
        }

        public String getServerIP() {
            return serverIP;
        }

        public String getServiceName() {
            return serviceName;
        }

        public String getNamespaceId() {
            return namespaceId;
        }

        public ServiceKey(String namespaceId, String serviceName, String serverIP, String checksum) {
            this.namespaceId = namespaceId;
            this.serviceName = serviceName;
            this.serverIP = serverIP;
            this.checksum = checksum;
        }

        @Override
        public String toString() {
            return JacksonUtils.toJson(this);
        }
    }
}
