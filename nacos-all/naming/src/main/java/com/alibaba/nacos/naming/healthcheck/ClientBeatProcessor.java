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

package com.alibaba.nacos.naming.healthcheck;

import com.alibaba.nacos.sys.utils.ApplicationUtils;
import com.alibaba.nacos.naming.core.Cluster;
import com.alibaba.nacos.naming.core.Instance;
import com.alibaba.nacos.naming.core.Service;
import com.alibaba.nacos.naming.misc.Loggers;
import com.alibaba.nacos.naming.misc.UtilsAndCommons;
import com.alibaba.nacos.naming.push.PushService;
import com.fasterxml.jackson.annotation.JsonIgnore;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 客户端心跳检测器。Thread to update ephemeral instance triggered by client beat.
 *
 * @author nkorange
 */
public class ClientBeatProcessor implements Runnable {

    public static final long CLIENT_BEAT_TIMEOUT = TimeUnit.SECONDS.toMillis(15);

    /**
     * 客户端发送过来的心跳包
     */
    private RsInfo rsInfo;

    /**
     * 当前心跳检测器所检测的服务
     */
    private Service service;

    @JsonIgnore
    public PushService getPushService() {
        return ApplicationUtils.getBean(PushService.class);
    }

    public RsInfo getRsInfo() {
        return rsInfo;
    }

    public void setRsInfo(RsInfo rsInfo) {
        this.rsInfo = rsInfo;
    }

    public Service getService() {
        return service;
    }

    public void setService(Service service) {
        this.service = service;
    }

    // 执行心跳任务
    @Override
    public void run() {
        Service service = this.service;
        if (Loggers.EVT_LOG.isDebugEnabled()) {
            Loggers.EVT_LOG.debug("[CLIENT-BEAT] processing beat: {}", rsInfo.toString());
        }

        // 获取心跳包中的 ip、集群名称、端口号等数据
        String ip = rsInfo.getIp();
        String clusterName = rsInfo.getCluster();
        int port = rsInfo.getPort();
        // 根据集群名称获取到对应的集群对象
        Cluster cluster = service.getClusterMap().get(clusterName);
        // 获取当前服务集群下的所有临时实例数据。ephemeral=true：临时实例
        List<Instance> instances = cluster.allIPs(true);
        // 遍历这些临时实例数据
        for (Instance instance : instances) {
            // 筛选需发送心跳的实例的条件是：ip、port 与当前心跳的实例数据相同，即说明当前实例就是要续约的实例
            if (instance.getIp().equals(ip) && instance.getPort() == port) {
                if (Loggers.EVT_LOG.isDebugEnabled()) {
                    Loggers.EVT_LOG.debug("[CLIENT-BEAT] refresh beat: {}", rsInfo.toString());
                }
                // 修改心跳时间戳
                instance.setLastBeat(System.currentTimeMillis());
                if (!instance.isMarked()) {
                    // 修改该实例的健康状态。当 instance 的 marked 属性值为 true 时，表示其为持久实例；临时实例的健康状态标识是 healthy 属性。
                    if (!instance.isHealthy()) {
                        // 不健康的实例因发送了心跳，变为了健康实例
                        instance.setHealthy(true);
                        Loggers.EVT_LOG
                                .info("service: {} {POS} {IP-ENABLED} valid: {}:{}@{}, region: {}, msg: client beat ok",
                                        cluster.getService().getName(), ip, port, cluster.getName(),
                                        UtilsAndCommons.LOCALHOST_SITE);
                        /**
                         * 服务的健康状态发生了变更，发布服务变更事件，推送最新的服务实例信息给客户端
                         * PushService 监听到事件，就会触发其 onApplicationEvent 方法，向客户端发送 UDP 通信
                         * UDP 通信通知该服务的所有订阅者来更新该服务的数据
                         */
                        getPushService().serviceChanged(service);
                    }
                }
            }
        }
    }
}
