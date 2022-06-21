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

package com.alibaba.nacos.naming.controllers;

import com.alibaba.nacos.api.common.Constants;
import com.alibaba.nacos.api.exception.NacosException;
import com.alibaba.nacos.api.naming.CommonParams;
import com.alibaba.nacos.api.naming.NamingResponseCode;
import com.alibaba.nacos.api.naming.PreservedMetadataKeys;
import com.alibaba.nacos.api.naming.utils.NamingUtils;
import com.alibaba.nacos.auth.annotation.Secured;
import com.alibaba.nacos.auth.common.ActionTypes;
import com.alibaba.nacos.common.utils.JacksonUtils;
import com.alibaba.nacos.core.utils.WebUtils;
import com.alibaba.nacos.naming.core.Instance;
import com.alibaba.nacos.naming.core.Service;
import com.alibaba.nacos.naming.core.ServiceManager;
import com.alibaba.nacos.naming.healthcheck.RsInfo;
import com.alibaba.nacos.naming.misc.Loggers;
import com.alibaba.nacos.naming.misc.SwitchDomain;
import com.alibaba.nacos.naming.misc.SwitchEntry;
import com.alibaba.nacos.naming.misc.UtilsAndCommons;
import com.alibaba.nacos.naming.pojo.InstanceOperationContext;
import com.alibaba.nacos.naming.pojo.InstanceOperationInfo;
import com.alibaba.nacos.naming.push.ClientInfo;
import com.alibaba.nacos.naming.push.DataSource;
import com.alibaba.nacos.naming.push.PushService;
import com.alibaba.nacos.naming.web.CanDistro;
import com.alibaba.nacos.naming.web.NamingResourceParser;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang3.BooleanUtils;
import org.apache.commons.lang3.StringUtils;
import org.codehaus.jackson.util.VersionUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static com.alibaba.nacos.naming.misc.UtilsAndCommons.DEFAULT_CLUSTER_NAME;
import static com.alibaba.nacos.naming.misc.UtilsAndCommons.EPHEMERAL;
import static com.alibaba.nacos.naming.misc.UtilsAndCommons.PERSIST;
import static com.alibaba.nacos.naming.misc.UtilsAndCommons.UPDATE_INSTANCE_METADATA_ACTION_REMOVE;
import static com.alibaba.nacos.naming.misc.UtilsAndCommons.UPDATE_INSTANCE_METADATA_ACTION_UPDATE;

/**
 * Instance operation controller.
 *
 * @author nkorange
 *
 * 该类为一个处理器，用于处理 Nacos Client 发送过来的心跳、注册等请求
 */
@RestController
@RequestMapping(UtilsAndCommons.NACOS_NAMING_CONTEXT + "/instance")
public class InstanceController {

    @Autowired
    private SwitchDomain switchDomain;

    @Autowired
    private PushService pushService;

    @Autowired
    private ServiceManager serviceManager;

    private DataSource pushDataSource = new DataSource() {

        @Override
        public String getData(PushService.PushClient client) {

            ObjectNode result = JacksonUtils.createEmptyJsonNode();
            try {
                // Nacos Server 对订阅请求进行详细处理。获取指定服务的最新数据，并且向 clientMap 中添加一个 PushClient
                result = doSrvIpxt(client.getNamespaceId(), client.getServiceName(), client.getAgent(),
                        client.getClusters(), client.getSocketAddr().getAddress().getHostAddress(), 0,
                        StringUtils.EMPTY, false, StringUtils.EMPTY, StringUtils.EMPTY, false);
            } catch (Exception e) {
                Loggers.SRV_LOG.warn("PUSH-SERVICE: service is not modified", e);
            }

            // overdrive the cache millis to push mode
            result.put("cacheMillis", switchDomain.getPushCacheMillis(client.getServiceName()));

            return result.toString();
        }
    };

    /**
     * 服务端处理客户端注册请求，注册新实例。Register new instance.
     *
     * @param request http request
     * @return 'ok' if success，注册成功返回 ok
     * @throws Exception any error during register
     */
    @CanDistro
    @PostMapping
    @Secured(parser = NamingResourceParser.class, action = ActionTypes.WRITE)
    public String register(HttpServletRequest request) throws Exception {
        // 从请求域中获取指定属性的值：namespaceId，名称空间，如果获取不到，则返回值为 public
        final String namespaceId = WebUtils
                .optional(request, CommonParams.NAMESPACE_ID, Constants.DEFAULT_NAMESPACE_ID);
        // 从请求域中获取指定属性的值：serviceName，服务名称，如果获取不到会抛异常
        final String serviceName = WebUtils.required(request, CommonParams.SERVICE_NAME);
        // 检查 serviceName 是否合法。「必须以"@@"连接两个字符串」
        NamingUtils.checkServiceNameFormat(serviceName);
        // 将请求参数解析成 Instance 数据，根据解析请求域参数得到对应参数值填充到服务实例对象中
        final Instance instance = parseInstance(request);
        // 注册服务实例，即将 Instance 数据注册到注册表中
        serviceManager.registerInstance(namespaceId, serviceName, instance);
        return "ok";
    }

    /**
     * Deregister instances.
     *
     * @param request http request
     * @return 'ok' if success
     * @throws Exception any error during deregister
     *
     * 处理客户端注销（删除）请求。（服务端删除过期 Instance 时，服务端会给自己发送一个请求，也是由此方法处理）
     */
    @CanDistro
    @DeleteMapping
    @Secured(parser = NamingResourceParser.class, action = ActionTypes.WRITE)
    public String deregister(HttpServletRequest request) throws Exception {
        // 将本次请求参数封装成 Instance 数据
        Instance instance = getIpAddress(request);
        String namespaceId = WebUtils.optional(request, CommonParams.NAMESPACE_ID, Constants.DEFAULT_NAMESPACE_ID);
        String serviceName = WebUtils.required(request, CommonParams.SERVICE_NAME);
        NamingUtils.checkServiceNameFormat(serviceName);
        // 在本地注册表中获取本次注销服务所对应的 Service 数据
        Service service = serviceManager.getService(namespaceId, serviceName);
        if (service == null) {
            Loggers.SRV_LOG.warn("remove instance from non-exist service: {}", serviceName);
            // 本地注册表中不包含本次要注销的服务，直接返回"ok"
            return "ok";
        }
        // 注销该服务
        serviceManager.removeInstance(namespaceId, serviceName, instance.isEphemeral(), instance);
        return "ok";
    }

    /**
     * Update instance.
     *
     * @param request http request
     * @return 'ok' if success
     * @throws Exception any error during update
     */
    @CanDistro
    @PutMapping
    @Secured(parser = NamingResourceParser.class, action = ActionTypes.WRITE)
    public String update(HttpServletRequest request) throws Exception {
        final String namespaceId = WebUtils
                .optional(request, CommonParams.NAMESPACE_ID, Constants.DEFAULT_NAMESPACE_ID);
        final String serviceName = WebUtils.required(request, CommonParams.SERVICE_NAME);
        NamingUtils.checkServiceNameFormat(serviceName);
        final Instance instance = parseInstance(request);

        String agent = WebUtils.getUserAgent(request);

        ClientInfo clientInfo = new ClientInfo(agent);

        if (clientInfo.type == ClientInfo.ClientType.JAVA
                && clientInfo.version.compareTo(VersionUtil.parseVersion("1.0.0")) >= 0) {
            serviceManager.updateInstance(namespaceId, serviceName, instance);
        } else {
            serviceManager.registerInstance(namespaceId, serviceName, instance);
        }
        return "ok";
    }

    /**
     * Batch update instance's metadata. old key exist = update, old key not exist = add.
     *
     * @param request http request
     * @return success updated instances. such as '{"updated":["2.2.2.2:8080:unknown:xxxx-cluster:ephemeral"}'.
     * @throws Exception any error during update
     * @since 1.4.0
     */
    @CanDistro
    @PutMapping(value = "/metadata/batch")
    @Secured(parser = NamingResourceParser.class, action = ActionTypes.WRITE)
    public ObjectNode batchUpdateInstanceMatadata(HttpServletRequest request) throws Exception {
        final String namespaceId = WebUtils
                .optional(request, CommonParams.NAMESPACE_ID, Constants.DEFAULT_NAMESPACE_ID);

        String serviceName = WebUtils.required(request, CommonParams.SERVICE_NAME);

        String consistencyType = WebUtils.optional(request, "consistencyType", StringUtils.EMPTY);

        String instances = WebUtils.optional(request, "instances", StringUtils.EMPTY);

        List<Instance> targetInstances = parseBatchInstances(instances);

        String metadata = WebUtils.required(request, "metadata");
        Map<String, String> targetMetadata = UtilsAndCommons.parseMetadata(metadata);

        List<Instance> operatedInstances = batchOperateMetadata(namespaceId,
                buildOperationInfo(serviceName, consistencyType, targetInstances), targetMetadata,
                UPDATE_INSTANCE_METADATA_ACTION_UPDATE);

        ObjectNode result = JacksonUtils.createEmptyJsonNode();
        ArrayNode ipArray = JacksonUtils.createEmptyArrayNode();

        for (Instance ip : operatedInstances) {
            ipArray.add(ip.getDatumKey() + ":" + (ip.isEphemeral() ? EPHEMERAL : PERSIST));
        }

        result.replace("updated", ipArray);
        return result;
    }

    /**
     * Batch delete instance's metadata. old key exist = delete, old key not exist = not operate
     *
     * @param request http request
     * @return success updated instances. such as '{"updated":["2.2.2.2:8080:unknown:xxxx-cluster:ephemeral"}'.
     * @throws Exception any error during update
     * @since 1.4.0
     */
    @CanDistro
    @DeleteMapping("/metadata/batch")
    @Secured(parser = NamingResourceParser.class, action = ActionTypes.WRITE)
    public ObjectNode batchDeleteInstanceMatadata(HttpServletRequest request) throws Exception {
        final String namespaceId = WebUtils
                .optional(request, CommonParams.NAMESPACE_ID, Constants.DEFAULT_NAMESPACE_ID);

        String serviceName = WebUtils.required(request, CommonParams.SERVICE_NAME);

        String consistencyType = WebUtils.optional(request, "consistencyType", StringUtils.EMPTY);

        String instances = WebUtils.optional(request, "instances", StringUtils.EMPTY);

        List<Instance> targetInstances = parseBatchInstances(instances);

        String metadata = WebUtils.required(request, "metadata");
        Map<String, String> targetMetadata = UtilsAndCommons.parseMetadata(metadata);

        List<Instance> operatedInstances = batchOperateMetadata(namespaceId,
                buildOperationInfo(serviceName, consistencyType, targetInstances), targetMetadata,
                UPDATE_INSTANCE_METADATA_ACTION_REMOVE);

        ObjectNode result = JacksonUtils.createEmptyJsonNode();
        ArrayNode ipArray = JacksonUtils.createEmptyArrayNode();

        for (Instance ip : operatedInstances) {
            ipArray.add(ip.getDatumKey() + ":" + (ip.isEphemeral() ? EPHEMERAL : PERSIST));
        }

        result.replace("updated", ipArray);
        return result;
    }

    private InstanceOperationInfo buildOperationInfo(String serviceName, String consistencyType,
            List<Instance> instances) {
        if (!CollectionUtils.isEmpty(instances)) {
            for (Instance instance : instances) {
                if (StringUtils.isBlank(instance.getClusterName())) {
                    instance.setClusterName(DEFAULT_CLUSTER_NAME);
                }
            }
        }
        return new InstanceOperationInfo(serviceName, consistencyType, instances);
    }

    private List<Instance> parseBatchInstances(String instances) {
        try {
            return JacksonUtils.toObj(instances, new TypeReference<List<Instance>>() {
            });
        } catch (Exception e) {
            Loggers.SRV_LOG.warn("UPDATE-METADATA: Param 'instances' is illegal, ignore this operation", e);
        }
        return null;
    }

    private List<Instance> batchOperateMetadata(String namespace, InstanceOperationInfo instanceOperationInfo,
            Map<String, String> metadata, String action) {
        Function<InstanceOperationContext, List<Instance>> operateFunction = instanceOperationContext -> {
            try {
                return serviceManager.updateMetadata(instanceOperationContext.getNamespace(),
                        instanceOperationContext.getServiceName(), instanceOperationContext.getEphemeral(), action,
                        instanceOperationContext.getAll(), instanceOperationContext.getInstances(), metadata);
            } catch (NacosException e) {
                Loggers.SRV_LOG.warn("UPDATE-METADATA: updateMetadata failed", e);
            }
            return new ArrayList<>();
        };
        return serviceManager.batchOperate(namespace, instanceOperationInfo, operateFunction);
    }

    /**
     * Patch instance.
     *
     * @param request http request
     * @return 'ok' if success
     * @throws Exception any error during patch
     */
    @CanDistro
    @PatchMapping
    @Secured(parser = NamingResourceParser.class, action = ActionTypes.WRITE)
    public String patch(HttpServletRequest request) throws Exception {
        String namespaceId = WebUtils.optional(request, CommonParams.NAMESPACE_ID, Constants.DEFAULT_NAMESPACE_ID);
        String serviceName = WebUtils.required(request, CommonParams.SERVICE_NAME);
        NamingUtils.checkServiceNameFormat(serviceName);
        String ip = WebUtils.required(request, "ip");
        String port = WebUtils.required(request, "port");
        String cluster = WebUtils.optional(request, CommonParams.CLUSTER_NAME, StringUtils.EMPTY);
        if (StringUtils.isBlank(cluster)) {
            cluster = WebUtils.optional(request, "cluster", UtilsAndCommons.DEFAULT_CLUSTER_NAME);
        }

        Instance instance = serviceManager.getInstance(namespaceId, serviceName, cluster, ip, Integer.parseInt(port));
        if (instance == null) {
            throw new IllegalArgumentException("instance not found");
        }

        String metadata = WebUtils.optional(request, "metadata", StringUtils.EMPTY);
        if (StringUtils.isNotBlank(metadata)) {
            instance.setMetadata(UtilsAndCommons.parseMetadata(metadata));
        }
        String app = WebUtils.optional(request, "app", StringUtils.EMPTY);
        if (StringUtils.isNotBlank(app)) {
            instance.setApp(app);
        }
        String weight = WebUtils.optional(request, "weight", StringUtils.EMPTY);
        if (StringUtils.isNotBlank(weight)) {
            instance.setWeight(Double.parseDouble(weight));
        }
        String healthy = WebUtils.optional(request, "healthy", StringUtils.EMPTY);
        if (StringUtils.isNotBlank(healthy)) {
            instance.setHealthy(BooleanUtils.toBoolean(healthy));
        }
        String enabledString = WebUtils.optional(request, "enabled", StringUtils.EMPTY);
        if (StringUtils.isNotBlank(enabledString)) {
            instance.setEnabled(BooleanUtils.toBoolean(enabledString));
        }
        instance.setLastBeat(System.currentTimeMillis());
        instance.validate();
        serviceManager.updateInstance(namespaceId, serviceName, instance);
        return "ok";
    }

    /**
     * Nacos 服务端服务发现接口
     * Get all instance of input service.
     *
     * @param request http request
     * @return list of instance
     * @throws Exception any error during list
     */
    @GetMapping("/list")
    @Secured(parser = NamingResourceParser.class, action = ActionTypes.READ)
    public ObjectNode list(HttpServletRequest request) throws Exception {

        // 从请求中获取各种属性
        String namespaceId = WebUtils.optional(request, CommonParams.NAMESPACE_ID, Constants.DEFAULT_NAMESPACE_ID);
        String serviceName = WebUtils.required(request, CommonParams.SERVICE_NAME);
        // 检验服务名称是否合法
        NamingUtils.checkServiceNameFormat(serviceName);
        // 获取请求域中的 User-Agent 参数：用于指定提交请求的客户端的类型「JAVA、GO……」
        String agent = WebUtils.getUserAgent(request);
        String clusters = WebUtils.optional(request, "clusters", StringUtils.EMPTY);
        String clientIP = WebUtils.optional(request, "clientIP", StringUtils.EMPTY);
        /**
         * 获取到客户端的端口号，用于 UDP 通信
         * 如果 udpPort > 0，则表示客户端订阅了服务，需要进行推送。即把请求的客户端添加为可推送的目标客户端。
         * 如果 udpPort = 0，则表示客户端没有订阅服务，是一个普通的客户端，不需要进行推送。
         */
        int udpPort = Integer.parseInt(WebUtils.optional(request, "udpPort", "0"));
        String env = WebUtils.optional(request, "env", StringUtils.EMPTY);
        boolean isCheck = Boolean.parseBoolean(WebUtils.optional(request, "isCheck", "false"));

        String app = WebUtils.optional(request, "app", StringUtils.EMPTY);

        String tenant = WebUtils.optional(request, "tid", StringUtils.EMPTY);

        boolean healthyOnly = Boolean.parseBoolean(WebUtils.optional(request, "healthyOnly", "false"));

        // 对订阅请求进行详细处理，即获取服务列表
        return doSrvIpxt(namespaceId, serviceName, agent, clusters, clientIP, udpPort, env, isCheck, app, tenant,
                healthyOnly);
    }

    /**
     * Get detail information of specified instance.
     *
     * @param request http request
     * @return detail information of instance
     * @throws Exception any error during get
     */
    @GetMapping
    @Secured(parser = NamingResourceParser.class, action = ActionTypes.READ)
    public ObjectNode detail(HttpServletRequest request) throws Exception {

        String namespaceId = WebUtils.optional(request, CommonParams.NAMESPACE_ID, Constants.DEFAULT_NAMESPACE_ID);
        String serviceName = WebUtils.required(request, CommonParams.SERVICE_NAME);
        NamingUtils.checkServiceNameFormat(serviceName);
        String cluster = WebUtils.optional(request, CommonParams.CLUSTER_NAME, UtilsAndCommons.DEFAULT_CLUSTER_NAME);
        String ip = WebUtils.required(request, "ip");
        int port = Integer.parseInt(WebUtils.required(request, "port"));

        Service service = serviceManager.getService(namespaceId, serviceName);
        if (service == null) {
            throw new NacosException(NacosException.NOT_FOUND, "no service " + serviceName + " found!");
        }

        List<String> clusters = new ArrayList<>();
        clusters.add(cluster);

        List<Instance> ips = service.allIPs(clusters);
        if (ips == null || ips.isEmpty()) {
            throw new NacosException(NacosException.NOT_FOUND,
                    "no ips found for cluster " + cluster + " in service " + serviceName);
        }

        for (Instance instance : ips) {
            if (instance.getIp().equals(ip) && instance.getPort() == port) {
                ObjectNode result = JacksonUtils.createEmptyJsonNode();
                result.put("service", serviceName);
                result.put("ip", ip);
                result.put("port", port);
                result.put("clusterName", cluster);
                result.put("weight", instance.getWeight());
                result.put("healthy", instance.isHealthy());
                result.put("instanceId", instance.getInstanceId());
                result.set("metadata", JacksonUtils.transferToJsonNode(instance.getMetadata()));
                return result;
            }
        }

        throw new NacosException(NacosException.NOT_FOUND, "no matched ip found!");
    }

    /**
     * 处理客户端心跳请求，给某个实例发送心跳。Create a beat for instance.
     *
     * @param request http request
     * @return 实例详细信息。detail information of instance
     * @throws Exception any error during handle
     */
    @CanDistro
    @PutMapping("/beat")
    @Secured(parser = NamingResourceParser.class, action = ActionTypes.WRITE)
    public ObjectNode beat(HttpServletRequest request) throws Exception {
        // 解析心跳请求参数，创建一个 JsonNode，该方法的返回值就是这个对象，以下操作就是对这个对象的初始化。
        ObjectNode result = JacksonUtils.createEmptyJsonNode();
        result.put(SwitchEntry.CLIENT_BEAT_INTERVAL, switchDomain.getClientBeatInterval());
        // 从请求中获取 beat，即客户端的心跳内容数据「beatInfo」
        String beat = WebUtils.optional(request, "beat", StringUtils.EMPTY);
        RsInfo clientBeat = null;
        if (StringUtils.isNotBlank(beat)) {
            // 将 beat 构建为 clientBeat「心跳内容数据」
            clientBeat = JacksonUtils.toObj(beat, RsInfo.class);
        }
        String clusterName = WebUtils
                .optional(request, CommonParams.CLUSTER_NAME, UtilsAndCommons.DEFAULT_CLUSTER_NAME);
        String ip = WebUtils.optional(request, "ip", StringUtils.EMPTY);
        // 获取客户端传递过来的端口「port」，其将用于"UDP"通信
        int port = Integer.parseInt(WebUtils.optional(request, "port", "0"));
        if (clientBeat != null) {
            // 如果心跳内容中指定了集群名称「clusterName」，则使用该名称，否则使用默认集群名称
            if (StringUtils.isNotBlank(clientBeat.getCluster())) {
                clusterName = clientBeat.getCluster();
            } else {
                // fix #2533
                clientBeat.setCluster(clusterName);
            }
            // 如果心跳内容中指定了实例的 ip 和端口，那么就以该指定数据为准
            ip = clientBeat.getIp();
            port = clientBeat.getPort();
        }
        String namespaceId = WebUtils.optional(request, CommonParams.NAMESPACE_ID, Constants.DEFAULT_NAMESPACE_ID);
        String serviceName = WebUtils.required(request, CommonParams.SERVICE_NAME);
        // 校验服务名称是否合法
        NamingUtils.checkServiceNameFormat(serviceName);
        Loggers.SRV_LOG.debug("[CLIENT-BEAT] full arguments: beat: {}, serviceName: {}", clientBeat, serviceName);
        // 从本地注册表中获取当前发送心跳请求的客户端对应的实例数据
        Instance instance = serviceManager.getInstance(namespaceId, serviceName, clusterName, ip, port);

        if (instance == null) { // 如果获取失败，说明对应的实例还尚未注册，需从新注册一个实例
            if (clientBeat == null) {
                result.put(CommonParams.CODE, NamingResponseCode.RESOURCE_NOT_FOUND);
                return result;
            }

            Loggers.SRV_LOG.warn("[CLIENT-BEAT] The instance has been removed for health mechanism, "
                    + "perform data compensation operations, beat: {}, serviceName: {}", clientBeat, serviceName);
            /**
             * 下面处理的情况是：注册表中没有该客户端的实例数据，但其发送的请求中含有心跳数据。
             * 这个客户端的注册请求没有到达时（网络抖动等原因造成的），第一次注销心跳请求到达了服务器，就会出现这个情况
             * 则使用功能心跳数据构建出一个服务实例 Instance，将其注册到注册表中
             */
            instance = new Instance();
            instance.setPort(clientBeat.getPort());
            instance.setIp(clientBeat.getIp());
            instance.setWeight(clientBeat.getWeight());
            instance.setMetadata(clientBeat.getMetadata());
            instance.setClusterName(clusterName);
            instance.setServiceName(serviceName);
            instance.setInstanceId(instance.getInstanceId());
            instance.setEphemeral(clientBeat.isEphemeral());
            // 发起注册请求
            serviceManager.registerInstance(namespaceId, serviceName, instance);
        }
        // 再次从本地注册表中获取当前服务数据
        Service service = serviceManager.getService(namespaceId, serviceName);

        if (service == null) { // 如果再次获取失败，则说明服务不存在，返回 404
            throw new NacosException(NacosException.SERVER_ERROR,
                    "service not found: " + serviceName + "@" + namespaceId);
        }
        if (clientBeat == null) {
            clientBeat = new RsInfo();
            clientBeat.setIp(ip);
            clientBeat.setPort(port);
            clientBeat.setCluster(clusterName);
        }

        // 执行本次心跳，开始处理心跳结果
        service.processClientBeat(clientBeat);

        result.put(CommonParams.CODE, NamingResponseCode.OK);
        if (instance.containsMetadata(PreservedMetadataKeys.HEART_BEAT_INTERVAL)) {
            result.put(SwitchEntry.CLIENT_BEAT_INTERVAL, instance.getInstanceHeartBeatInterval());
        }
        result.put(SwitchEntry.LIGHT_BEAT_ENABLED, switchDomain.isLightBeatEnabled());
        return result;
    }

    /**
     * List all instance with health status.
     *
     * @param key (namespace##)?serviceName
     * @return list of instance
     * @throws NacosException any error during handle
     */
    @RequestMapping("/statuses")
    public ObjectNode listWithHealthStatus(@RequestParam String key) throws NacosException {

        String serviceName;
        String namespaceId;

        if (key.contains(UtilsAndCommons.NAMESPACE_SERVICE_CONNECTOR)) {
            namespaceId = key.split(UtilsAndCommons.NAMESPACE_SERVICE_CONNECTOR)[0];
            serviceName = key.split(UtilsAndCommons.NAMESPACE_SERVICE_CONNECTOR)[1];
        } else {
            namespaceId = Constants.DEFAULT_NAMESPACE_ID;
            serviceName = key;
        }
        NamingUtils.checkServiceNameFormat(serviceName);
        Service service = serviceManager.getService(namespaceId, serviceName);

        if (service == null) {
            throw new NacosException(NacosException.NOT_FOUND, "service: " + serviceName + " not found.");
        }

        List<Instance> ips = service.allIPs();

        ObjectNode result = JacksonUtils.createEmptyJsonNode();
        ArrayNode ipArray = JacksonUtils.createEmptyArrayNode();

        for (Instance ip : ips) {
            ipArray.add(ip.toIpAddr() + "_" + ip.isHealthy());
        }

        result.replace("ips", ipArray);
        return result;
    }

    private Instance parseInstance(HttpServletRequest request) throws Exception {

        String serviceName = WebUtils.required(request, CommonParams.SERVICE_NAME);
        String app = WebUtils.optional(request, "app", "DEFAULT");
        // 将请求参数解析成 Instance 类型数据
        Instance instance = getIpAddress(request);
        instance.setApp(app);
        instance.setServiceName(serviceName);
        // Generate simple instance id first. This value would be updated according to
        // INSTANCE_ID_GENERATOR.
        instance.setInstanceId(instance.generateInstanceId());
        instance.setLastBeat(System.currentTimeMillis());
        String metadata = WebUtils.optional(request, "metadata", StringUtils.EMPTY);
        if (StringUtils.isNotEmpty(metadata)) {
            instance.setMetadata(UtilsAndCommons.parseMetadata(metadata));
        }

        instance.validate();

        return instance;
    }

    private Instance getIpAddress(HttpServletRequest request) {

        String enabledString = WebUtils.optional(request, "enabled", StringUtils.EMPTY);
        boolean enabled;
        if (StringUtils.isBlank(enabledString)) {
            enabled = BooleanUtils.toBoolean(WebUtils.optional(request, "enable", "true"));
        } else {
            enabled = BooleanUtils.toBoolean(enabledString);
        }

        String weight = WebUtils.optional(request, "weight", "1");
        // 获取本次请求对应的服务的健康状态
        boolean healthy = BooleanUtils.toBoolean(WebUtils.optional(request, "healthy", "true"));
        // 将本次请求参数封装成 Instance 数据
        Instance instance = getBasicIpAddress(request);
        instance.setWeight(Double.parseDouble(weight));
        instance.setHealthy(healthy);
        instance.setEnabled(enabled);

        return instance;
    }

    private Instance getBasicIpAddress(HttpServletRequest request) {

        final String ip = WebUtils.required(request, "ip");
        final String port = WebUtils.required(request, "port");
        String cluster = WebUtils.optional(request, CommonParams.CLUSTER_NAME, StringUtils.EMPTY);
        if (StringUtils.isBlank(cluster)) {
            cluster = WebUtils.optional(request, "cluster", UtilsAndCommons.DEFAULT_CLUSTER_NAME);
        }
        boolean ephemeral = BooleanUtils.toBoolean(
                WebUtils.optional(request, "ephemeral", String.valueOf(switchDomain.isDefaultInstanceEphemeral())));

        Instance instance = new Instance();
        instance.setPort(Integer.parseInt(port));
        instance.setIp(ip);
        instance.setEphemeral(ephemeral);
        instance.setClusterName(cluster);

        return instance;
    }

    private void checkIfDisabled(Service service) throws Exception {
        if (!service.getEnabled()) {
            throw new Exception("service is disabled now.");
        }
    }

    /**
     * Nacos Server 对订阅请求进行详细处理，即获取服务列表
     * Get service full information with instances.
     *
     * @param namespaceId namespace id
     * @param serviceName service name
     * @param agent       agent infor string
     * @param clusters    cluster names
     * @param clientIP    client ip
     * @param udpPort     push udp port
     * @param env         env
     * @param isCheck     is check request
     * @param app         app name
     * @param tid         tenant
     * @param healthyOnly whether only for healthy check
     * @return service full information with instances
     * @throws Exception any error during handle
     */
    public ObjectNode doSrvIpxt(String namespaceId, String serviceName, String agent, String clusters, String clientIP,
            int udpPort, String env, boolean isCheck, String app, String tid, boolean healthyOnly) throws Exception {

        // 不同的 agent，生成不同的 ClientInfo。（agent{JAVA,C,GO……}）
        ClientInfo clientInfo = new ClientInfo(agent);
        // 创建一个 JsonNode，该方法的返回值就是这个对象，以下操作就是对这个对象的初始化。
        ObjectNode result = JacksonUtils.createEmptyJsonNode();

        // 先从注册表中获取当前服务列表信息（根据名称空间 id 和服务名称获取）
        Service service = serviceManager.getService(namespaceId, serviceName);
        long cacheMillis = switchDomain.getDefaultCacheMillis();

        // now try to enable the push
        try {
            /**
             * 如果 udpPort > 0，则表示客户端订阅了服务，需要进行推送。即把请求的客户端添加为可推送的目标客户端。
             * 如果 udpPort = 0，则表示客户端没有订阅服务，是一个普通的客户端，不需要进行推送。
             */
            if (udpPort > 0 && pushService.canEnablePush(agent)) {
                /**
                 * 当客户端订阅了服务之后，就会作为可推送的目标客户端添加给推送服务组件
                 * 创建当前发起请阅请求的 Nacos Client 的 UDP Client，即 PushClient。
                 * 并判断 PushClient 是否存在与 clientMap 中，若存在，则更新其最后引用时间戳，若不存在，则将其存入缓存 clientMap 中。
                 * 注意，在 Nacos 的 UDP 通信中，Nacos Server 充当的是 UDP Client，Nacos Client 充当的是 UDP Server。
                 * 其实是把客户端的 UDP 端口、IP 等信息封装为一个 PushClient 对象，存储 PushService 中，方便以后服务变更后推送消息。
                 */
                pushService
                        .addClient(namespaceId, serviceName, clusters, agent, new InetSocketAddress(clientIP, udpPort),
                                pushDataSource, tid, app);
                cacheMillis = switchDomain.getPushCacheMillis(serviceName);
            }
        } catch (Exception e) {
            Loggers.SRV_LOG
                    .error("[NACOS-API] failed to added push client {}, {}:{}", clientInfo, clientIP, udpPort, e);
            cacheMillis = switchDomain.getDefaultCacheMillis();
        }

        // 若当前注册表中没有该服务，即指定的服务不存在，则直接结束，简单封装下对象并返回给客户端
        if (service == null) {
            if (Loggers.SRV_LOG.isDebugEnabled()) {
                Loggers.SRV_LOG.debug("no instance to serve for service: {}", serviceName);
            }
            result.put("name", serviceName);
            result.put("clusters", clusters);
            result.put("cacheMillis", cacheMillis);
            // 此处 hosts 为空
            result.replace("hosts", JacksonUtils.createEmptyArrayNode());
            return result;
        }

        // 至此，说明注册表中存在该服务


        // 判断该服务是否被禁用，若被禁用，则直接抛出异常
        checkIfDisabled(service);

        List<Instance> srvedIPs;
        // 获取当前服务的所有 Instance 列表，包含持久实例与临时实例
        srvedIPs = service.srvIPs(Arrays.asList(StringUtils.split(clusters, ",")));

        /**
         * 如果要查询的服务有 selector 并且 clientIP 不为空，那么就需要经过 selector 对该客户端进行负载均衡去获取实例。
         * 此处可以自定义选择器，来筛选 Instance 列表，默认没有做任何筛选。filter ips using selector:
         */
        if (service.getSelector() != null && StringUtils.isNotBlank(clientIP)) {
            srvedIPs = service.getSelector().select(clientIP, srvedIPs);
        }

        // 若得到的 Instance 列表为空，则直接返回。即该服务下没有实例，或者过滤后没有实例，简单封装下对象并返回给客户端。
            if (CollectionUtils.isEmpty(srvedIPs)) {

                if (Loggers.SRV_LOG.isDebugEnabled()) {
                    Loggers.SRV_LOG.debug("no instance to serve for service: {}", serviceName);
                }

                if (clientInfo.type == ClientInfo.ClientType.JAVA
                        && clientInfo.version.compareTo(VersionUtil.parseVersion("1.0.0")) >= 0) {
                    result.put("dom", serviceName);
                } else {
                    result.put("dom", NamingUtils.getServiceName(serviceName));
                }

                result.put("name", serviceName);
                result.put("cacheMillis", cacheMillis);
                result.put("lastRefTime", System.currentTimeMillis());
                result.put("checksum", service.getChecksum());
                result.put("useSpecifiedURL", false);
                result.put("clusters", clusters);
                result.put("env", env);
                // 此处 hosts 为空
                result.set("hosts", JacksonUtils.createEmptyArrayNode());
                result.set("metadata", JacksonUtils.transferToJsonNode(service.getMetadata()));
                return result;
            }

        // 至此，说明当前服务的"可用的" Instance 列表不为空，即获取到该服务下的实例数据了。

        Map<Boolean, List<Instance>> ipMap = new HashMap<>(2);
        // key=true:用于存放健康的 Instance
        ipMap.put(Boolean.TRUE, new ArrayList<>());
        // key=false:用于存放不健康的 Instance
        ipMap.put(Boolean.FALSE, new ArrayList<>());

        // 根据 Instance 的健康状态，将所有 Instance 分流存入对应的缓存 map 中，区分一下健康实例和非健康实例。
        for (Instance ip : srvedIPs) {
            // 此处的分流用法和好！！！！
            ipMap.get(ip.isHealthy()).add(ip);
        }

        // isCheck=true：表示需要检测 Instance 的保护阈值，默认时 false。
        if (isCheck) {
            // reachProtectThreshold：是否达到了保护阈值
            result.put("reachProtectThreshold", false);
        }

        // 获取服务的保护阈值
        double threshold = service.getProtectThreshold();

        // 判断该服务下的健康实例占总实例数的百分比小于等于服务保护阈值
        if ((float) ipMap.get(Boolean.TRUE).size() / srvedIPs.size() <= threshold) {
            // 若健康实例比例小于保护阈值，则说明此时需要启动保护机制了，防止所有流量全部命中健康实例，给健康实例造成流量压力。
            // 即调用服务时，需从所有服务实例中调用，有可能会命中到不健康的实例。
            Loggers.SRV_LOG.warn("protect threshold reached, return all ips, service: {}", serviceName);
            if (isCheck) {
                result.put("reachProtectThreshold", true);
            }
            /**
             * 开启服务保护机制，把非健康的实例也放到健康实例的集合中
             * 将所有不健康的 instance 添加到 key 为 true 的 instance 列表中；
             * 即此时，key 为 true 的 instance 列表中，存放的时所有 instance 实例，既包含健康的也包含不健康的。
             */
            ipMap.get(Boolean.TRUE).addAll(ipMap.get(Boolean.FALSE));
            // 清空不健康实例的列表数据
            ipMap.get(Boolean.FALSE).clear();
        }

        if (isCheck) {
            result.put("protectThreshold", service.getProtectThreshold());
            result.put("reachLocalSiteCallThreshold", false);

            return JacksonUtils.createEmptyJsonNode();
        }

        // 至此，说明没有开启服务保护机制，即没有达到保护阈值，则直接返回健康实例列表。

        ArrayNode hosts = JacksonUtils.createEmptyArrayNode();
        // ipMap 中存放着所有的 Instance，包括健康的和不健康的
        for (Map.Entry<Boolean, List<Instance>> entry : ipMap.entrySet()) {
            List<Instance> ips = entry.getValue();

            if (healthyOnly && !entry.getKey()) {
                // 若客户端指定了只要获取健康的实例，那么就跳过不健康的实例
                continue;
            }

            // ips 可能是健康实例集合，也可能是不健康实例集合，也可能是包含健康与不健康实例的集合
            for (Instance instance : ips) {

                // remove disabled instance:
                if (!instance.isEnabled()) {
                    // 过滤掉被禁用(没开启)的实例
                    continue;
                }

                ObjectNode ipObj = JacksonUtils.createEmptyJsonNode();
                // 将当前遍历到的 Instance 转换为 JSON
                ipObj.put("ip", instance.getIp());
                ipObj.put("port", instance.getPort());
                // deprecated since nacos 1.0.0:
                ipObj.put("valid", entry.getKey());
                ipObj.put("healthy", entry.getKey());
                ipObj.put("marked", instance.isMarked());
                ipObj.put("instanceId", instance.getInstanceId());
                ipObj.set("metadata", JacksonUtils.transferToJsonNode(instance.getMetadata()));
                ipObj.put("enabled", instance.isEnabled());
                ipObj.put("weight", instance.getWeight());
                ipObj.put("clusterName", instance.getClusterName());
                if (clientInfo.type == ClientInfo.ClientType.JAVA
                        && clientInfo.version.compareTo(VersionUtil.parseVersion("1.0.0")) >= 0) {
                    ipObj.put("serviceName", instance.getServiceName());
                } else {
                    ipObj.put("serviceName", NamingUtils.getServiceName(instance.getServiceName()));
                }

                ipObj.put("ephemeral", instance.isEphemeral());
                hosts.add(ipObj);

            }
        }

        // 组装实例数据
        result.replace("hosts", hosts);
        if (clientInfo.type == ClientInfo.ClientType.JAVA
                && clientInfo.version.compareTo(VersionUtil.parseVersion("1.0.0")) >= 0) {
            result.put("dom", serviceName);
        } else {
            result.put("dom", NamingUtils.getServiceName(serviceName));
        }
        result.put("name", serviceName);
        result.put("cacheMillis", cacheMillis);
        result.put("lastRefTime", System.currentTimeMillis());
        result.put("checksum", service.getChecksum());
        result.put("useSpecifiedURL", false);
        result.put("clusters", clusters);
        result.put("env", env);
        result.replace("metadata", JacksonUtils.transferToJsonNode(service.getMetadata()));
        return result;
    }
}
