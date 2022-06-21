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

import com.alibaba.nacos.common.utils.JacksonUtils;
import com.alibaba.nacos.sys.utils.ApplicationUtils;
import com.alibaba.nacos.common.utils.MD5Utils;
import com.alibaba.nacos.api.common.Constants;
import com.alibaba.nacos.naming.consistency.KeyBuilder;
import com.alibaba.nacos.naming.consistency.RecordListener;
import com.alibaba.nacos.naming.healthcheck.ClientBeatCheckTask;
import com.alibaba.nacos.naming.healthcheck.ClientBeatProcessor;
import com.alibaba.nacos.naming.healthcheck.HealthCheckReactor;
import com.alibaba.nacos.naming.healthcheck.RsInfo;
import com.alibaba.nacos.naming.misc.Loggers;
import com.alibaba.nacos.naming.misc.UtilsAndCommons;
import com.alibaba.nacos.naming.pojo.Record;
import com.alibaba.nacos.naming.push.PushService;
import com.alibaba.nacos.naming.selector.NoneSelector;
import com.alibaba.nacos.naming.selector.Selector;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;

import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.collections.ListUtils;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

/**
 * Service of Nacos server side
 *
 * <p>We introduce a 'service --> cluster --> instance' model, in which service stores a list of clusters, which
 * contain
 * a list of instances.
 *
 * <p>his class inherits from Service in API module and stores some fields that do not have to expose to client.
 *
 * @author nkorange
 *
 * Nacos Server 端服务{其类似于客户端的 ServiceInfo}
 */
@JsonInclude(Include.NON_NULL)
public class Service extends com.alibaba.nacos.api.naming.pojo.Service implements Record, RecordListener<Instances> {

    private static final String SERVICE_NAME_SYNTAX = "[0-9a-zA-Z@\\.:_-]+";

    @JsonIgnore
    private ClientBeatCheckTask clientBeatCheckTask = new ClientBeatCheckTask(this);

    /**
     * Identify the information used to determine how many isEmpty judgments the service has experienced.
     */
    private int finalizeCount = 0;

    private String token;

    private List<String> owners = new ArrayList<>();

    private Boolean resetWeight = false;

    /**
     * 上线状态，标记该实例是否接受流量，优先级大于权重和健康状态，用于运维人员在不变动实例本身的情况下，快速地将某个实例从服务中移除
     */
    private Boolean enabled = true;

    /**
     * 实例选择器，用于在获取服务下的实例列表时，过滤和筛选实例。
     * 该选择器也被称为路由器，目前 Nacos 支持通过将实例的部分信息存储在外部元数据管理 CMDB 中，
     * 并在发现服务时使用 CMDB 中存储的元数据标签来进行筛选的能力。
     */
    private Selector selector = new NoneSelector();

    private String namespaceId;

    /**
     * IP will be deleted if it has not send beat for some time, default timeout is 30 seconds.
     */
    private long ipDeleteTimeout = 30 * 1000;

    private volatile long lastModifiedMillis = 0L;
    // 校验和，是当前 Service 的所有 SCI「'service --> cluster --> instance' model」 信息的字符串拼接。
    private volatile String checksum;

    /**
     * TODO set customized push expire time.
     */
    private long pushCacheMillis = 0L;
    // key 为 clusterName；value 为 Cluster，即存储所有 Instance，包括临时实例和持久实例。
    private Map<String, Cluster> clusterMap = new HashMap<>();

    public Service() {
    }

    public Service(String name) {
        super(name);
    }

    @JsonIgnore
    public PushService getPushService() {
        return ApplicationUtils.getBean(PushService.class);
    }

    public long getIpDeleteTimeout() {
        return ipDeleteTimeout;
    }

    public void setIpDeleteTimeout(long ipDeleteTimeout) {
        this.ipDeleteTimeout = ipDeleteTimeout;
    }

    /**
     * 执行客户端发送过来的实例心跳。Process client beat.
     * 将心跳信息封装到 ClientBeatProcessor(心跳检测器对象) 中，由 HealthCheckReactor 处理，运行 ClientBeatProcessor.run() 方法。
     * HealthCheckReactor 就是对线程池的封装。
     * @param rsInfo metrics info of server
     */
    public void processClientBeat(final RsInfo rsInfo) {
        // 创建一个客户端心跳检测器对象
        ClientBeatProcessor clientBeatProcessor = new ClientBeatProcessor();
        clientBeatProcessor.setService(this);
        clientBeatProcessor.setRsInfo(rsInfo);
        // 将 ClientBeatProcessor 交给 HealthCheckReactor 处理，HealthCheckReactor 就是对线程池的封装。
        HealthCheckReactor.scheduleNow(clientBeatProcessor);
    }

    public Boolean getEnabled() {
        return enabled;
    }

    public void setEnabled(Boolean enabled) {
        this.enabled = enabled;
    }

    public long getLastModifiedMillis() {
        return lastModifiedMillis;
    }

    public void setLastModifiedMillis(long lastModifiedMillis) {
        this.lastModifiedMillis = lastModifiedMillis;
    }

    public Boolean getResetWeight() {
        return resetWeight;
    }

    public void setResetWeight(Boolean resetWeight) {
        this.resetWeight = resetWeight;
    }

    public Selector getSelector() {
        return selector;
    }

    public void setSelector(Selector selector) {
        this.selector = selector;
    }

    @Override
    public boolean interests(String key) {
        return KeyBuilder.matchInstanceListKey(key, namespaceId, getName());
    }

    @Override
    public boolean matchUnlistenKey(String key) {
        return KeyBuilder.matchInstanceListKey(key, namespaceId, getName());
    }

    /**
     * 当该服务下的实例发生变化时，就会回调该监听方法
     * @param key   名称空间 id + 服务名称，例如：com.alibaba.nacos.naming.iplist.ephemeral.namespaceId##serviceName
     * @param value 变化之后该服务下最新的实例集合。data of the key
     * @throws Exception
     */
    @Override
    public void onChange(String key, Instances value) throws Exception {

        Loggers.SRV_LOG.info("[NACOS-RAFT] datum is changed, key: {}, value: {}", key, value);

        // 遍历最新的实例集合
        for (Instance instance : value.getInstanceList()) {

            if (instance == null) {
                // 若实例为 null，则抛出异常。Reject this abnormal instance list:
                throw new RuntimeException("got null instance " + key);
            }

            if (instance.getWeight() > 10000.0D) {
                // 设置权重最大只能是 10000
                instance.setWeight(10000.0D);
            }

            if (instance.getWeight() < 0.01D && instance.getWeight() > 0.0D) {
                // 若设置的权重小于 0.01，并大于 0，则就将其强制设置为 0.01
                instance.setWeight(0.01D);
            }
        }

        // 更新该服务下最新的实例
        updateIPs(value.getInstanceList(), KeyBuilder.matchEphemeralInstanceListKey(key));

        // 重新计算该服务的校验和
        recalculateChecksum();
    }

    @Override
    public void onDelete(String key) throws Exception {
        // ignore
    }

    /**
     * Get count of healthy instance in service.
     *
     * @return count of healthy instance
     */
    public int healthyInstanceCount() {

        int healthyCount = 0;
        for (Instance instance : allIPs()) {
            if (instance.isHealthy()) {
                healthyCount++;
            }
        }
        return healthyCount;
    }

    public boolean triggerFlag() {
        return (healthyInstanceCount() * 1.0 / allIPs().size()) <= getProtectThreshold();
    }

    /**
     * 更新该服务下最新的实例。
     * 通过写时复制的方式，更新的时候，先去复制出原来的一份实例集合，在复制的这个集合上面进行修改，最后替换掉原来旧的集合。
     * Update instances.
     *
     * @param instances 该服务下最新的实例集合。instances
     * @param ephemeral 是否为临时实例标识。whether is ephemeral instance
     */
    public void updateIPs(Collection<Instance> instances, boolean ephemeral) {
        // 存放该服务下的最新的实例（复制一份实例集合）
        Map<String, List<Instance>> ipMap = new HashMap<>(clusterMap.size());
        for (String clusterName : clusterMap.keySet()) {
            ipMap.put(clusterName, new ArrayList<>());
        }

        // 遍历要更新的实例
        for (Instance instance : instances) {
            try {
                if (instance == null) {
                    Loggers.SRV_LOG.error("[NACOS-DOM] received malformed ip: null");
                    continue;
                }

                // 判断实例是否包含 clusterName，若不包含，则使用默认的 cluster
                if (StringUtils.isEmpty(instance.getClusterName())) {
                    instance.setClusterName(UtilsAndCommons.DEFAULT_CLUSTER_NAME);
                }

                // 如果服务下面没有这个新增实例所在的集群，则在该服务下初始化一个集群
                if (!clusterMap.containsKey(instance.getClusterName())) {
                    Loggers.SRV_LOG
                            .warn("cluster: {} not found, ip: {}, will create new cluster with default configuration.",
                                    instance.getClusterName(), instance.toJson());
                    Cluster cluster = new Cluster(instance.getClusterName(), this);
                    cluster.init();
                    getClusterMap().put(instance.getClusterName(), cluster);
                }

                // 如果该服务下面没有这个新增实例所在的集群，同时也在复制出来的实例集合中初始化一个集群
                List<Instance> clusterIPs = ipMap.get(instance.getClusterName());
                if (clusterIPs == null) {
                    clusterIPs = new LinkedList<>();
                    ipMap.put(instance.getClusterName(), clusterIPs);
                }

                // 把新增的实例放到复制的实例集合中
                clusterIPs.add(instance);
            } catch (Exception e) {
                Loggers.SRV_LOG.error("[NACOS-DOM] failed to process ip: " + instance, e);
            }
        }

        // 遍历最新的实例集合
        for (Map.Entry<String, List<Instance>> entry : ipMap.entrySet()) {
            // 获取这个集群下所有的最新实例。make every ip mine
            List<Instance> entryIPs = entry.getValue();
            // 对该服务下每一个集群更新最新的实例集合，将实例集合更新到 clusterMap 中，即更新注册表
            clusterMap.get(entry.getKey()).updateIps(entryIPs, ephemeral);
        }

        // 更新修改时间
        setLastModifiedMillis(System.currentTimeMillis());
        // 服务变动之后推送最新实例信息给客户端。发送变更事件，触发监听器回调。
        getPushService().serviceChanged(this);
        StringBuilder stringBuilder = new StringBuilder();

        for (Instance instance : allIPs()) {
            stringBuilder.append(instance.toIpAddr()).append("_").append(instance.isHealthy()).append(",");
        }

        Loggers.EVT_LOG.info("[IP-UPDATED] namespace: {}, service: {}, ips: {}", getNamespaceId(), getName(),
                stringBuilder.toString());

    }

    /**
     * 对该服务进行初始化工作。Init service.
     */
    public void init() {
        /**
         * 开启心跳检测任务，即开启定时清除过期的临时 Instance 实例数据任务
         * 往实例健康检查组件中添加了健康检查任务，延迟 5s 开始，每 5s 执行一次
         */
        HealthCheckReactor.scheduleCheck(clientBeatCheckTask);
        // 开启了当前服务所包含的所有 Cluster 的健康检测任务
        for (Map.Entry<String, Cluster> entry : clusterMap.entrySet()) {
            entry.getValue().setService(this);
            /**
             * 完成集群初始化。会进入到 Cluster 的 init 方法中
             * 开启当前遍历到的 Cluster 的健康检测任务。将当前 Cluster 包含的所有持久实例的心跳检测任务定时添加到任务队列 taskQueue 中
             * 即将当前 Cluster 所包含的持久实例的心跳任务添加到任务队列 taskQueue 中
             */
            entry.getValue().init();
        }
    }

    /**
     * Destroy service.
     *
     * @throws Exception exception
     */
    public void destroy() throws Exception {
        for (Map.Entry<String, Cluster> entry : clusterMap.entrySet()) {
            entry.getValue().destroy();
        }
        HealthCheckReactor.cancelCheck(clientBeatCheckTask);
    }

    /**
     * Judge whether service has instance.
     *
     * @return true if no instance, otherwise false
     */
    public boolean isEmpty() {
        for (Map.Entry<String, Cluster> entry : clusterMap.entrySet()) {
            final Cluster cluster = entry.getValue();
            if (!cluster.isEmpty()) {
                return false;
            }
        }
        return true;
    }

    /**
     * Get all instance.
     *
     * @return list of all instance
     */
    public List<Instance> allIPs() {
        List<Instance> result = new ArrayList<>();
        for (Map.Entry<String, Cluster> entry : clusterMap.entrySet()) {
            result.addAll(entry.getValue().allIPs());
        }

        return result;
    }

    /**
     * Get all instance of ephemeral or consistency.
     *
     * @param ephemeral whether ephemeral instance
     * @return all instance of ephemeral if @param ephemeral = true, otherwise all instance of consistency
     *
     * ephemeral=true：获取所有临时实例
     * ephemeral=false：获取所有实例（包括临时实例和持久实例）
     */
    public List<Instance> allIPs(boolean ephemeral) {
        List<Instance> result = new ArrayList<>();
        for (Map.Entry<String, Cluster> entry : clusterMap.entrySet()) {
            result.addAll(entry.getValue().allIPs(ephemeral));
        }

        return result;
    }

    /**
     * Get all instance from input clusters.
     *
     * @param clusters cluster names
     * @return all instance from input clusters.
     */
    public List<Instance> allIPs(List<String> clusters) {
        List<Instance> result = new ArrayList<>();
        for (String cluster : clusters) {
            Cluster clusterObj = clusterMap.get(cluster);
            if (clusterObj == null) {
                continue;
            }

            result.addAll(clusterObj.allIPs());
        }
        return result;
    }

    /**
     * Get all instance from input clusters.
     *
     * @param clusters cluster names
     * @return all instance from input clusters, if clusters is empty, return all cluster
     */
    public List<Instance> srvIPs(List<String> clusters) {
        if (CollectionUtils.isEmpty(clusters)) {
            clusters = new ArrayList<>();
            clusters.addAll(clusterMap.keySet());
        }
        // 获取当前服务的所有 Cluster 中的所有 Instance 列表
        return allIPs(clusters);
    }

    public String toJson() {
        return JacksonUtils.toJson(this);
    }

    @JsonIgnore
    public String getServiceString() {
        Map<Object, Object> serviceObject = new HashMap<Object, Object>(10);
        Service service = this;

        serviceObject.put("name", service.getName());

        List<Instance> ips = service.allIPs();
        int invalidIpCount = 0;
        int ipCount = 0;
        for (Instance ip : ips) {
            if (!ip.isHealthy()) {
                invalidIpCount++;
            }

            ipCount++;
        }

        serviceObject.put("ipCount", ipCount);
        serviceObject.put("invalidIPCount", invalidIpCount);

        serviceObject.put("owners", service.getOwners());
        serviceObject.put("token", service.getToken());

        serviceObject.put("protectThreshold", service.getProtectThreshold());

        List<Object> clustersList = new ArrayList<Object>();

        for (Map.Entry<String, Cluster> entry : service.getClusterMap().entrySet()) {
            Cluster cluster = entry.getValue();

            Map<Object, Object> clusters = new HashMap<Object, Object>(10);
            clusters.put("name", cluster.getName());
            clusters.put("healthChecker", cluster.getHealthChecker());
            clusters.put("defCkport", cluster.getDefCkport());
            clusters.put("defIPPort", cluster.getDefIPPort());
            clusters.put("useIPPort4Check", cluster.isUseIPPort4Check());
            clusters.put("sitegroup", cluster.getSitegroup());

            clustersList.add(clusters);
        }

        serviceObject.put("clusters", clustersList);

        try {
            return JacksonUtils.toJson(serviceObject);
        } catch (Exception e) {
            throw new RuntimeException("Service toJson failed", e);
        }
    }

    public String getToken() {
        return token;
    }

    public void setToken(String token) {
        this.token = token;
    }

    public List<String> getOwners() {
        return owners;
    }

    public void setOwners(List<String> owners) {
        this.owners = owners;
    }

    public Map<String, Cluster> getClusterMap() {
        return clusterMap;
    }

    public void setClusterMap(Map<String, Cluster> clusterMap) {
        this.clusterMap = clusterMap;
    }

    public String getNamespaceId() {
        return namespaceId;
    }

    public void setNamespaceId(String namespaceId) {
        this.namespaceId = namespaceId;
    }

    /**
     * Update from other service.
     *
     * @param vDom other service
     */
    public void update(Service vDom) {

        if (!StringUtils.equals(token, vDom.getToken())) {
            Loggers.SRV_LOG.info("[SERVICE-UPDATE] service: {}, token: {} -> {}", getName(), token, vDom.getToken());
            token = vDom.getToken();
        }

        if (!ListUtils.isEqualList(owners, vDom.getOwners())) {
            Loggers.SRV_LOG.info("[SERVICE-UPDATE] service: {}, owners: {} -> {}", getName(), owners, vDom.getOwners());
            owners = vDom.getOwners();
        }

        if (getProtectThreshold() != vDom.getProtectThreshold()) {
            Loggers.SRV_LOG
                    .info("[SERVICE-UPDATE] service: {}, protectThreshold: {} -> {}", getName(), getProtectThreshold(),
                            vDom.getProtectThreshold());
            setProtectThreshold(vDom.getProtectThreshold());
        }

        if (resetWeight != vDom.getResetWeight().booleanValue()) {
            Loggers.SRV_LOG.info("[SERVICE-UPDATE] service: {}, resetWeight: {} -> {}", getName(), resetWeight,
                    vDom.getResetWeight());
            resetWeight = vDom.getResetWeight();
        }

        if (enabled != vDom.getEnabled().booleanValue()) {
            Loggers.SRV_LOG
                    .info("[SERVICE-UPDATE] service: {}, enabled: {} -> {}", getName(), enabled, vDom.getEnabled());
            enabled = vDom.getEnabled();
        }

        selector = vDom.getSelector();

        setMetadata(vDom.getMetadata());

        updateOrAddCluster(vDom.getClusterMap().values());
        remvDeadClusters(this, vDom);

        Loggers.SRV_LOG.info("cluster size, new: {}, old: {}", getClusterMap().size(), vDom.getClusterMap().size());

        recalculateChecksum();
    }

    @Override
    public String getChecksum() {
        if (StringUtils.isEmpty(checksum)) {
            recalculateChecksum();
        }

        return checksum;
    }

    /**
     * Re-calculate checksum of service.
     */
    public synchronized void recalculateChecksum() {
        // 获取当前 Service 所包含的所有 Instance 列表（包含了临时实例集合与持久实例集合）
        List<Instance> ips = allIPs();

        StringBuilder ipsString = new StringBuilder();
        ipsString.append(getServiceString());

        if (Loggers.SRV_LOG.isDebugEnabled()) {
            Loggers.SRV_LOG.debug("service to json: " + getServiceString());
        }

        if (CollectionUtils.isNotEmpty(ips)) {
            Collections.sort(ips);
        }

        for (Instance ip : ips) {
            String string = ip.getIp() + ":" + ip.getPort() + "_" + ip.getWeight() + "_" + ip.isHealthy() + "_" + ip
                    .getClusterName();
            ipsString.append(string);
            ipsString.append(",");
        }
        // 最终获取到当前 Service 的所有数据，经 MD5 加密后赋值给 checksum
        checksum = MD5Utils.md5Hex(ipsString.toString(), Constants.ENCODE);
    }

    private void updateOrAddCluster(Collection<Cluster> clusters) {
        for (Cluster cluster : clusters) {
            Cluster oldCluster = clusterMap.get(cluster.getName());
            if (oldCluster != null) {
                oldCluster.setService(this);
                oldCluster.update(cluster);
            } else {
                cluster.init();
                cluster.setService(this);
                clusterMap.put(cluster.getName(), cluster);
            }
        }
    }

    private void remvDeadClusters(Service oldDom, Service newDom) {
        Collection<Cluster> oldClusters = oldDom.getClusterMap().values();
        Collection<Cluster> newClusters = newDom.getClusterMap().values();
        List<Cluster> deadClusters = (List<Cluster>) CollectionUtils.subtract(oldClusters, newClusters);
        for (Cluster cluster : deadClusters) {
            oldDom.getClusterMap().remove(cluster.getName());

            cluster.destroy();
        }
    }

    public int getFinalizeCount() {
        return finalizeCount;
    }

    public void setFinalizeCount(int finalizeCount) {
        this.finalizeCount = finalizeCount;
    }

    public void addCluster(Cluster cluster) {
        clusterMap.put(cluster.getName(), cluster);
    }

    /**
     * Judge whether service is validate.
     *
     * @throws IllegalArgumentException if service is not validate
     */
    public void validate() {
        if (!getName().matches(SERVICE_NAME_SYNTAX)) {
            throw new IllegalArgumentException(
                    "dom name can only have these characters: 0-9a-zA-Z-._:, current: " + getName());
        }
        for (Cluster cluster : clusterMap.values()) {
            cluster.validate();
        }
    }
}
