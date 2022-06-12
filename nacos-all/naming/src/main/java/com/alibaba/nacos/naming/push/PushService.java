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

package com.alibaba.nacos.naming.push;

import com.alibaba.nacos.api.naming.utils.NamingUtils;
import com.alibaba.nacos.common.utils.JacksonUtils;
import com.alibaba.nacos.naming.core.Service;
import com.alibaba.nacos.naming.misc.GlobalExecutor;
import com.alibaba.nacos.naming.misc.Loggers;
import com.alibaba.nacos.naming.misc.SwitchDomain;
import com.alibaba.nacos.naming.misc.UtilsAndCommons;
import com.alibaba.nacos.naming.pojo.Subscriber;
import org.apache.commons.collections.MapUtils;
import org.apache.commons.lang3.StringUtils;
import org.codehaus.jackson.util.VersionUtil;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.context.ApplicationListener;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.GZIPOutputStream;

/**
 * Push service.
 * PushService 实现了 ApplicationListener 接口，监听 ServiceChangeEvent 服务变更事件
 * @author nacos
 */
@Component
@SuppressWarnings("PMD.ThreadPoolCreationRule")
public class PushService implements ApplicationContextAware, ApplicationListener<ServiceChangeEvent> {

    @Autowired
    private SwitchDomain switchDomain;

    private ApplicationContext applicationContext;

    private static final long ACK_TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(10L);

    private static final int MAX_RETRY_TIMES = 1;

    private static volatile ConcurrentMap<String, Receiver.AckEntry> ackMap = new ConcurrentHashMap<>();

    /**
     * 保存了所有已订阅服务变更的可推送目标客户端
     * key：namespaceId##groupId@@微服务名，以具体的某个服务作为维度进行区分
     * clientMap 是一个双层 map，外层 map 的 key："namespaceId##groupId@@微服务名称"；value：为内层 map
     * 内层 map 的 key：代表 Instance 的字符串；value：该 Instance 对应的 UDP Client，即 PushClient，可推送目标客户端对象，
     * 整个 map 结构就表示了每一个服务都会对应多个已订阅的可推送目标客户端。
     */
    private static ConcurrentMap<String, ConcurrentMap<String, PushClient>> clientMap = new ConcurrentHashMap<>();

    private static volatile ConcurrentMap<String, Long> udpSendTimeMap = new ConcurrentHashMap<>();

    public static volatile ConcurrentMap<String, Long> pushCostMap = new ConcurrentHashMap<>();

    private static int totalPush = 0;

    private static int failedPush = 0;

    private static DatagramSocket udpSocket;

    private static ConcurrentMap<String, Future> futureMap = new ConcurrentHashMap<>();

    static {
        try {
            udpSocket = new DatagramSocket();

            Receiver receiver = new Receiver();

            Thread inThread = new Thread(receiver);
            inThread.setDaemon(true);
            inThread.setName("com.alibaba.nacos.naming.push.receiver");
            inThread.start();

            GlobalExecutor.scheduleRetransmitter(() -> {
                try {
                    removeClientIfZombie();
                } catch (Throwable e) {
                    Loggers.PUSH.warn("[NACOS-PUSH] failed to remove client zombie");
                }
            }, 0, 20, TimeUnit.SECONDS);

        } catch (SocketException e) {
            Loggers.SRV_LOG.error("[NACOS-PUSH] failed to init push service");
        }
    }

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
        this.applicationContext = applicationContext;
    }

    /**
     * PushService 订阅了 ServiceChangeEvent 事件，当服务变更时，回调此方法进行服务变更的推送
     * Server-Client 间的 UDP 通信：Nacos Server 向 Nacos Client 发送 UDP 推送请求
     * @param event
     */
    @Override
    public void onApplicationEvent(ServiceChangeEvent event) {
        // 获取服务变更事件中的服务对象、服务名称、名称空间 id
        Service service = event.getService();
        String serviceName = service.getName();
        String namespaceId = service.getNamespaceId();

        // 启动一个定时操作，异步执行服务变更推送操作，即异步发送 UDP 广播
        Future future = GlobalExecutor.scheduleUdpSender(() -> {
            try {
                Loggers.PUSH.info(serviceName + " is changed, add it to push queue.");
                /**
                 * 获取订阅了这个服务的所有客户端
                 * 从缓存 map 中获取当前服务的内存 map
                 * 内层 map 中存放的是当前服务的所有 Nacos Client 的 UDP 客户端 PushClient
                 */
                ConcurrentMap<String, PushClient> clients = clientMap
                        .get(UtilsAndCommons.assembleFullServiceName(namespaceId, serviceName));
                if (MapUtils.isEmpty(clients)) {
                    // 跳过没有订阅这个服务的客户端
                    return;
                }

                Map<String, Object> cache = new HashMap<>(16);
                // 更新最后引用时间戳
                long lastRefTime = System.nanoTime();
                // 遍历指定服务和集群下所有可推送的目标客户端，向订阅者(Nacos Client)进行 UDP 推送
                for (PushClient client : clients.values()) {
                    if (client.zombie()) {
                        Loggers.PUSH.debug("client is zombie: " + client.toString());
                        // 删除"僵尸" PushClient
                        clients.remove(client.toString());
                        Loggers.PUSH.debug("client is zombie: " + client.toString());
                        continue;
                    }

                    Receiver.AckEntry ackEntry;
                    Loggers.PUSH.debug("push serviceName: {} to client: {}", serviceName, client.toString());
                    // key：serviceName@@@@agent
                    String key = getPushCacheKey(serviceName, client.getIp(), client.getAgent());
                    byte[] compressData = null;
                    Map<String, Object> data = null;
                    if (switchDomain.getDefaultPushCacheMillis() >= 20000 && cache.containsKey(key)) {
                        // 满足判断条件，就使用前面准备的数据，该当前客户端进行推送
                        org.javatuples.Pair pair = (org.javatuples.Pair) cache.get(key);
                        compressData = (byte[]) (pair.getValue0());
                        data = (Map<String, Object>) pair.getValue1();

                        Loggers.PUSH.debug("[PUSH-CACHE] cache hit: {}:{}", serviceName, client.getAddrStr());
                    }

                    if (compressData != null) { // 使用旧的推送数据去推送
                        ackEntry = prepareAckEntry(client, compressData, data, lastRefTime);
                    } else {
                        /**
                         * 创建一个 AckEntry 对象，其包装了推送给客户端的数据
                         * 通过 prepareHostsData() 方法能够获取到可推送客户端订阅的服务的最新实例信息，并且将这个可推送客户端重新注册到 PushService 中。
                         */
                        ackEntry = prepareAckEntry(client, prepareHostsData(client), lastRefTime);
                        if (ackEntry != null) {
                            // 把 key 与推送的数据进行绑定放到缓存中
                            cache.put(key, new org.javatuples.Pair<>(ackEntry.origin.getData(), ackEntry.data));
                        }
                    }

                    Loggers.PUSH.info("serviceName: {} changed, schedule push for: {}, agent: {}, key: {}",
                            client.getServiceName(), client.getAddrStr(), client.getAgent(),
                            (ackEntry == null ? null : ackEntry.key));
                    // UDP 通信，向客户端 Nacos Client 推送数据
                    udpPush(ackEntry);
                }
            } catch (Exception e) {
                Loggers.PUSH.error("[NACOS-PUSH] failed to push serviceName: {} to client, error: {}", serviceName, e);

            } finally {
                // 推送完成之后从 futureMap 中移除这个服务的 key
                futureMap.remove(UtilsAndCommons.assembleFullServiceName(namespaceId, serviceName));
            }

        }, 1000, TimeUnit.MILLISECONDS);

        // 把这个服务的 key 放到 futureMap 中，标识当前服务正在进行推送数据给客户端
        futureMap.put(UtilsAndCommons.assembleFullServiceName(namespaceId, serviceName), future);

    }

    public int getTotalPush() {
        return totalPush;
    }

    public void setTotalPush(int totalPush) {
        PushService.totalPush = totalPush;
    }

    /**
     * 添加一个目标推送客户端
     * Add push target client.
     *
     * @param namespaceId namespace id
     * @param serviceName service name
     * @param clusters    cluster
     * @param agent       agent information
     * @param socketAddr  client address
     * @param dataSource  datasource of push data
     * @param tenant      tenant
     * @param app         app
     */
    public void addClient(String namespaceId, String serviceName, String clusters, String agent,
            InetSocketAddress socketAddr, DataSource dataSource, String tenant, String app) {
        // 创建一个 UDP Client
        PushClient client = new PushClient(namespaceId, serviceName, clusters, agent, socketAddr, dataSource, tenant,
                app);
        // 添加一个目标推送客户端。将这个 UDP Client 添加到缓存 ConcurrentMap<String, PushClient> clients 中
        addClient(client);
    }

    /**
     * 添加一个目标推送客户端
     * Add push target client.
     *
     * @param client 推送目标客户端。push target client
     */
    public void addClient(PushClient client) {
        // client is stored by key 'serviceName' because notify event is driven by serviceName change
        // key == namespaceId##groupId@@微服务名称
        String serviceKey = UtilsAndCommons.assembleFullServiceName(client.getNamespaceId(), client.getServiceName());
        /**
         * 根据 key 从 clientMap 中获取到客户端集合
         * clientMap 缓存当前 Nacos Server 中所有实例 Instance 对应的 UDP Client，即保存了所有已订阅服务变更的可推送目标客户端
         * clientMap 是一个双层 map，外层 map 的 key："namespaceId##groupId@@微服务名称"；value：为内层 map
         * 内层 map 的 key：代表 Instance 的字符串；value：该 Instance 对应的 UDP Client，即 PushClient，可推送目标客户端对象，
         * 整个 map 结构就表示了每一个服务都会对应多个已订阅的可推送目标客户端。
         */
        ConcurrentMap<String, PushClient> clients = clientMap.get(serviceKey);
        if (clients == null) {
            // 当前服务的内层 map 为 null，则创建一个并放入到缓存 clientMap 中
            clientMap.putIfAbsent(serviceKey, new ConcurrentHashMap<>(1024));
            clients = clientMap.get(serviceKey);
        }

        // 从缓存内层 map 中，获取为当前服务所创建的 PushClient
        PushClient oldClient = clients.get(client.toString());
        // 判断为当前服务所创建的 PushClient 在缓存 clientMap 中是否存在，即判断是否已经注册了对应的推送目标客户端。
        if (oldClient != null) {
            // 若 PushClient 存在，则更新最后引用时间戳
            oldClient.refresh();
        } else {
            // 若 PushClient 不存在，则将其存入到缓存 clientMap 中，即没有注册这个推送目标客户端，需要进行注册。
            PushClient res = clients.putIfAbsent(client.toString(), client);
            if (res != null) {
                Loggers.PUSH.warn("client: {} already associated with key {}", res.getAddrStr(), res.toString());
            }
            Loggers.PUSH.debug("client: {} added for serviceName: {}", client.getAddrStr(), client.getServiceName());
        }
    }

    /**
     * Get push target client(subscriber).
     *
     * @param serviceName service name
     * @param namespaceId namespace id
     * @return list of subsriber
     */
    public List<Subscriber> getClients(String serviceName, String namespaceId) {
        String serviceKey = UtilsAndCommons.assembleFullServiceName(namespaceId, serviceName);
        ConcurrentMap<String, PushClient> clientConcurrentMap = clientMap.get(serviceKey);
        if (Objects.isNull(clientConcurrentMap)) {
            return null;
        }
        List<Subscriber> clients = new ArrayList<>();
        clientConcurrentMap.forEach((key, client) -> {
            clients.add(
                    new Subscriber(client.getAddrStr(), client.getAgent(), client.getApp(), client.getIp(), namespaceId,
                            serviceName));
        });
        return clients;
    }

    /**
     * fuzzy search subscriber.
     *
     * @param serviceName service name
     * @param namespaceId namespace id
     * @return list of subscriber
     */
    public List<Subscriber> getClientsFuzzy(String serviceName, String namespaceId) {
        List<Subscriber> clients = new ArrayList<>();
        clientMap.forEach((outKey, clientConcurrentMap) -> {
            //get groupedName from key
            String serviceFullName = outKey.split(UtilsAndCommons.NAMESPACE_SERVICE_CONNECTOR)[1];
            //get groupName
            String groupName = NamingUtils.getGroupName(serviceFullName);
            //get serviceName
            String name = NamingUtils.getServiceName(serviceFullName);
            //fuzzy match
            if (outKey.startsWith(namespaceId) && name.indexOf(NamingUtils.getServiceName(serviceName)) >= 0
                    && groupName.indexOf(NamingUtils.getGroupName(serviceName)) >= 0) {
                clientConcurrentMap.forEach((key, client) -> {
                    clients.add(new Subscriber(client.getAddrStr(), client.getAgent(), client.getApp(), client.getIp(),
                            namespaceId, serviceFullName));
                });
            }
        });
        return clients;
    }

    private static void removeClientIfZombie() {

        int size = 0;
        for (Map.Entry<String, ConcurrentMap<String, PushClient>> entry : clientMap.entrySet()) {
            ConcurrentMap<String, PushClient> clientConcurrentMap = entry.getValue();
            for (Map.Entry<String, PushClient> entry1 : clientConcurrentMap.entrySet()) {
                PushClient client = entry1.getValue();
                if (client.zombie()) {
                    clientConcurrentMap.remove(entry1.getKey());
                }
            }

            size += clientConcurrentMap.size();
        }

        if (Loggers.PUSH.isDebugEnabled()) {
            Loggers.PUSH.debug("[NACOS-PUSH] clientMap size: {}", size);
        }

    }

    private static Receiver.AckEntry prepareAckEntry(PushClient client, Map<String, Object> data, long lastRefTime) {
        if (MapUtils.isEmpty(data)) {
            Loggers.PUSH.error("[NACOS-PUSH] pushing empty data for client is not allowed: {}", client);
            return null;
        }

        data.put("lastRefTime", lastRefTime);

        // we apply lastRefTime as sequence num for further ack
        String key = getAckKey(client.getSocketAddr().getAddress().getHostAddress(), client.getSocketAddr().getPort(),
                lastRefTime);

        String dataStr = JacksonUtils.toJson(data);

        try {
            byte[] dataBytes = dataStr.getBytes(StandardCharsets.UTF_8);
            dataBytes = compressIfNecessary(dataBytes);

            DatagramPacket packet = new DatagramPacket(dataBytes, dataBytes.length, client.socketAddr);

            // we must store the key be fore send, otherwise there will be a chance the
            // ack returns before we put in
            Receiver.AckEntry ackEntry = new Receiver.AckEntry(key, packet);
            ackEntry.data = data;

            return ackEntry;
        } catch (Exception e) {
            Loggers.PUSH.error("[NACOS-PUSH] failed to prepare data: {} to client: {}, error: {}", data,
                    client.getSocketAddr(), e);
            return null;
        }
    }

    private static Receiver.AckEntry prepareAckEntry(PushClient client, byte[] dataBytes, Map<String, Object> data,
            long lastRefTime) {
        String key = getAckKey(client.getSocketAddr().getAddress().getHostAddress(), client.getSocketAddr().getPort(),
                lastRefTime);
        DatagramPacket packet = null;
        try {
            packet = new DatagramPacket(dataBytes, dataBytes.length, client.socketAddr);
            Receiver.AckEntry ackEntry = new Receiver.AckEntry(key, packet);
            // we must store the key be fore send, otherwise there will be a chance the
            // ack returns before we put in
            ackEntry.data = data;

            return ackEntry;
        } catch (Exception e) {
            Loggers.PUSH.error("[NACOS-PUSH] failed to prepare data: {} to client: {}, error: {}", data,
                    client.getSocketAddr(), e);
        }

        return null;
    }

    public static String getPushCacheKey(String serviceName, String clientIP, String agent) {
        return serviceName + UtilsAndCommons.CACHE_KEY_SPLITER + agent;
    }

    /**
     * Service changed.
     * 指定服务下的实例信息有变化的时候会调用此方法，在方法中会去通过 Spring 时间机制发布一个 ServiceChangeEvent 事件
     * 其中 PushService 组件就订阅了 ServiceChangeEvent 事件，会去执行订阅逻辑，在订阅逻辑中会去推送该服务下最新的实例信息给客户端。
     *
     * @param service 指定的服务
     */
    public void serviceChanged(Service service) {
        // merge some change events to reduce the push frequency:
        if (futureMap
                .containsKey(UtilsAndCommons.assembleFullServiceName(service.getNamespaceId(), service.getName()))) {
            return;
        }

        // 发布 ServiceChangeEvent 事件，
        this.applicationContext.publishEvent(new ServiceChangeEvent(this, service));
    }

    /**
     * Judge whether this agent is supported to push.
     *
     * @param agent agent information
     * @return true if agent can be pushed, otherwise false
     */
    public boolean canEnablePush(String agent) {

        if (!switchDomain.isPushEnabled()) {
            return false;
        }

        ClientInfo clientInfo = new ClientInfo(agent);

        if (ClientInfo.ClientType.JAVA == clientInfo.type
                && clientInfo.version.compareTo(VersionUtil.parseVersion(switchDomain.getPushJavaVersion())) >= 0) {
            return true;
        } else if (ClientInfo.ClientType.DNS == clientInfo.type
                && clientInfo.version.compareTo(VersionUtil.parseVersion(switchDomain.getPushPythonVersion())) >= 0) {
            return true;
        } else if (ClientInfo.ClientType.C == clientInfo.type
                && clientInfo.version.compareTo(VersionUtil.parseVersion(switchDomain.getPushCVersion())) >= 0) {
            return true;
        } else if (ClientInfo.ClientType.GO == clientInfo.type
                && clientInfo.version.compareTo(VersionUtil.parseVersion(switchDomain.getPushGoVersion())) >= 0) {
            return true;
        } else if (ClientInfo.ClientType.CSHARP == clientInfo.type
                && clientInfo.version.compareTo(VersionUtil.parseVersion(switchDomain.getPushCSharpVersion())) >= 0) {
            return true;
        }

        return false;
    }

    public static List<Receiver.AckEntry> getFailedPushes() {
        return new ArrayList<Receiver.AckEntry>(ackMap.values());
    }

    public int getFailedPushCount() {
        return ackMap.size() + failedPush;
    }

    public void setFailedPush(int failedPush) {
        PushService.failedPush = failedPush;
    }

    public static void resetPushState() {
        ackMap.clear();
    }

    public class PushClient {

        private String namespaceId;

        private String serviceName;

        private String clusters;

        private String agent;

        private String tenant;

        private String app;

        private InetSocketAddress socketAddr;

        private DataSource dataSource;

        private Map<String, String[]> params;

        public Map<String, String[]> getParams() {
            return params;
        }

        public void setParams(Map<String, String[]> params) {
            this.params = params;
        }

        public long lastRefTime = System.currentTimeMillis();

        public PushClient(String namespaceId, String serviceName, String clusters, String agent,
                InetSocketAddress socketAddr, DataSource dataSource, String tenant, String app) {
            this.namespaceId = namespaceId;
            this.serviceName = serviceName;
            this.clusters = clusters;
            this.agent = agent;
            this.socketAddr = socketAddr;
            this.dataSource = dataSource;
            this.tenant = tenant;
            this.app = app;
        }

        public DataSource getDataSource() {
            return dataSource;
        }

        public boolean zombie() {
            return System.currentTimeMillis() - lastRefTime > switchDomain.getPushCacheMillis(serviceName);
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append("serviceName: ").append(serviceName).append(", clusters: ").append(clusters).append(", address: ")
                    .append(socketAddr).append(", agent: ").append(agent);
            return sb.toString();
        }

        public String getAgent() {
            return agent;
        }

        public String getAddrStr() {
            return socketAddr.getAddress().getHostAddress() + ":" + socketAddr.getPort();
        }

        public String getIp() {
            return socketAddr.getAddress().getHostAddress();
        }

        @Override
        public int hashCode() {
            return Objects.hash(serviceName, clusters, socketAddr);
        }

        @Override
        public boolean equals(Object obj) {
            if (!(obj instanceof PushClient)) {
                return false;
            }

            PushClient other = (PushClient) obj;

            return serviceName.equals(other.serviceName) && clusters.equals(other.clusters) && socketAddr
                    .equals(other.socketAddr);
        }

        public String getClusters() {
            return clusters;
        }

        public void setClusters(String clusters) {
            this.clusters = clusters;
        }

        public String getNamespaceId() {
            return namespaceId;
        }

        public void setNamespaceId(String namespaceId) {
            this.namespaceId = namespaceId;
        }

        public String getServiceName() {
            return serviceName;
        }

        public void setServiceName(String serviceName) {
            this.serviceName = serviceName;
        }

        public String getTenant() {
            return tenant;
        }

        public void setTenant(String tenant) {
            this.tenant = tenant;
        }

        public String getApp() {
            return app;
        }

        public void setApp(String app) {
            this.app = app;
        }

        public InetSocketAddress getSocketAddr() {
            return socketAddr;
        }

        public void refresh() {
            lastRefTime = System.currentTimeMillis();
        }

    }

    private static byte[] compressIfNecessary(byte[] dataBytes) throws IOException {
        // enable compression when data is larger than 1KB
        int maxDataSizeUncompress = 1024;
        if (dataBytes.length < maxDataSizeUncompress) {
            return dataBytes;
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        GZIPOutputStream gzip = new GZIPOutputStream(out);
        gzip.write(dataBytes);
        gzip.close();

        return out.toByteArray();
    }

    private static Map<String, Object> prepareHostsData(PushClient client) throws Exception {
        Map<String, Object> cmd = new HashMap<String, Object>(2);
        cmd.put("type", "dom");
        /**
         * 在 getData(client) 方法中，就会去调用 com.alibaba.nacos.naming.controllers.InstanceController.doSrvIpxt方法
         * 这个方法中会去重新把客户端注册到 PushService 中，目的就是刷新一下注册的时间。
         * dataSource 这个对象是在 PushClient 被创建的时候就穿进去了，所以会看到 PushClient 被创建的地方就能发现这个 DataSource 了。
         */
        cmd.put("data", client.getDataSource().getData(client));

        return cmd;
    }

    /**
     * 向客户端推送 udp 数据
     * @param ackEntry
     * @return
     */
    private static Receiver.AckEntry udpPush(Receiver.AckEntry ackEntry) {
        if (ackEntry == null) {
            Loggers.PUSH.error("[NACOS-PUSH] ackEntry is null.");
            return null;
        }
        // 若 UDP 通信重试次数超出了最大值，则将该 UDP 通信从两个缓存 map 中清除
        if (ackEntry.getRetryTimes() > MAX_RETRY_TIMES) {
            Loggers.PUSH.warn("max re-push times reached, retry times {}, key: {}", ackEntry.retryTimes, ackEntry.key);
            ackMap.remove(ackEntry.key);
            udpSendTimeMap.remove(ackEntry.key);
            // 失败计数器加 1
            failedPush += 1;
            return ackEntry;
        }

        try {
            if (!ackMap.containsKey(ackEntry.key)) {
                // 计数器加 1
                totalPush++;
            }
            ackMap.put(ackEntry.key, ackEntry);
            udpSendTimeMap.put(ackEntry.key, System.currentTimeMillis());

            Loggers.PUSH.info("send udp packet: " + ackEntry.key);
            // 向客户端发送 UDP 请求。通过 udpSocket 原生 api 发送 udp 数据包给客户端。
            udpSocket.send(ackEntry.origin);

            ackEntry.increaseRetryTime();

            // 开启定时任务，用于 UDP 通信失败后的重新推送
            GlobalExecutor.scheduleRetransmitter(new Retransmitter(ackEntry),
                    TimeUnit.NANOSECONDS.toMillis(ACK_TIMEOUT_NANOS), TimeUnit.MILLISECONDS);

            return ackEntry;
        } catch (Exception e) {
            Loggers.PUSH.error("[NACOS-PUSH] failed to push data: {} to client: {}, error: {}", ackEntry.data,
                    ackEntry.origin.getAddress().getHostAddress(), e);
            ackMap.remove(ackEntry.key);
            udpSendTimeMap.remove(ackEntry.key);
            failedPush += 1;

            return null;
        }
    }

    private static String getAckKey(String host, int port, long lastRefTime) {
        return StringUtils.strip(host) + "," + port + "," + lastRefTime;
    }

    public static class Retransmitter implements Runnable {

        Receiver.AckEntry ackEntry;

        public Retransmitter(Receiver.AckEntry ackEntry) {
            this.ackEntry = ackEntry;
        }

        @Override
        public void run() {
            if (ackMap.containsKey(ackEntry.key)) {
                Loggers.PUSH.info("retry to push data, key: " + ackEntry.key);
                udpPush(ackEntry);
            }
        }
    }

    public static class Receiver implements Runnable {

        @Override
        public void run() {
            while (true) {
                byte[] buffer = new byte[1024 * 64];
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);

                try {
                    udpSocket.receive(packet);

                    String json = new String(packet.getData(), 0, packet.getLength(), StandardCharsets.UTF_8).trim();
                    AckPacket ackPacket = JacksonUtils.toObj(json, AckPacket.class);

                    InetSocketAddress socketAddress = (InetSocketAddress) packet.getSocketAddress();
                    String ip = socketAddress.getAddress().getHostAddress();
                    int port = socketAddress.getPort();

                    if (System.nanoTime() - ackPacket.lastRefTime > ACK_TIMEOUT_NANOS) {
                        Loggers.PUSH.warn("ack takes too long from {} ack json: {}", packet.getSocketAddress(), json);
                    }

                    String ackKey = getAckKey(ip, port, ackPacket.lastRefTime);
                    AckEntry ackEntry = ackMap.remove(ackKey);
                    if (ackEntry == null) {
                        throw new IllegalStateException(
                                "unable to find ackEntry for key: " + ackKey + ", ack json: " + json);
                    }

                    long pushCost = System.currentTimeMillis() - udpSendTimeMap.get(ackKey);

                    Loggers.PUSH
                            .info("received ack: {} from: {}:{}, cost: {} ms, unacked: {}, total push: {}", json, ip,
                                    port, pushCost, ackMap.size(), totalPush);

                    pushCostMap.put(ackKey, pushCost);

                    udpSendTimeMap.remove(ackKey);

                } catch (Throwable e) {
                    Loggers.PUSH.error("[NACOS-PUSH] error while receiving ack data", e);
                }
            }
        }

        public static class AckEntry {

            public AckEntry(String key, DatagramPacket packet) {
                this.key = key;
                this.origin = packet;
            }

            public void increaseRetryTime() {
                retryTimes.incrementAndGet();
            }

            public int getRetryTimes() {
                return retryTimes.get();
            }

            public String key;

            public DatagramPacket origin;

            private AtomicInteger retryTimes = new AtomicInteger(0);

            public Map<String, Object> data;
        }

        public static class AckPacket {

            public String type;

            public long lastRefTime;

            public String data;
        }
    }

}
