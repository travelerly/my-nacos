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

import com.alibaba.nacos.common.utils.IPUtil;
import com.alibaba.nacos.common.http.Callback;
import com.alibaba.nacos.common.model.RestResult;
import com.alibaba.nacos.common.utils.JacksonUtils;
import com.alibaba.nacos.sys.env.EnvUtil;
import com.alibaba.nacos.sys.utils.ApplicationUtils;
import com.alibaba.nacos.naming.consistency.KeyBuilder;
import com.alibaba.nacos.naming.core.DistroMapper;
import com.alibaba.nacos.naming.core.Instance;
import com.alibaba.nacos.naming.core.Service;
import com.alibaba.nacos.naming.healthcheck.events.InstanceHeartbeatTimeoutEvent;
import com.alibaba.nacos.naming.misc.GlobalConfig;
import com.alibaba.nacos.naming.misc.HttpClient;
import com.alibaba.nacos.naming.misc.Loggers;
import com.alibaba.nacos.naming.misc.NamingProxy;
import com.alibaba.nacos.naming.misc.SwitchDomain;
import com.alibaba.nacos.naming.misc.UtilsAndCommons;
import com.alibaba.nacos.naming.push.PushService;
import com.fasterxml.jackson.annotation.JsonIgnore;

import java.util.List;

/**
 * Check and update statues of ephemeral instances, remove them if they have been expired.
 *
 * @author nkorange
 */
public class ClientBeatCheckTask implements Runnable {

    private Service service;

    public ClientBeatCheckTask(Service service) {
        this.service = service;
    }

    @JsonIgnore
    public PushService getPushService() {
        return ApplicationUtils.getBean(PushService.class);
    }

    @JsonIgnore
    public DistroMapper getDistroMapper() {
        return ApplicationUtils.getBean(DistroMapper.class);
    }

    public GlobalConfig getGlobalConfig() {
        return ApplicationUtils.getBean(GlobalConfig.class);
    }

    public SwitchDomain getSwitchDomain() {
        return ApplicationUtils.getBean(SwitchDomain.class);
    }

    public String taskKey() {
        return KeyBuilder.buildServiceMetaKey(service.getNamespaceId(), service.getName());
    }


    /**
     * 服务端开启心跳检测任务，即服务端对实例进行过期下线检查，清除过期的临时 Instance 实例数据
     * 先判断当前服务是否由当前节点负责
     * 然后再判断 nacos 服务是否开启了健康检查，默认是开启的，如果没有开启，则直接结束，
     * 接着就获取这个服务中所有的实例对象，遍历这些实例对象，对每一个实例对象中的最近一次心跳续约时间与当前时间进行比较，默认相差超过 15s，
     * 就把这个实例的健康状态变更为非健康状态，这里仅仅修改健康状态为"非健康"，并没有把实例进行下线，
     * 然后接着会再一次遍历读物的所有实例，在这一次遍历中，如果当前时间与该实例最近心跳续约的时间差大于 30s，就对该实例进行真正的下线操作，
     * 所谓下线操作，就是将这个实例从注册表中删除。
     */
    @Override
    public void run() {
        try {

            /**
             * 这个判断是与 nacos 集群有关的，因为在 nacos 的 AP 架构中，集群中的每一个 naocs 节点都会具体负责分配给自己的服务，
             * 比如服务 A 是分配给节点 A，那么节点 A 在这里需要对服务 A 的实例是否过期下线进行判断，其它节点就无需判断了，
             * 最终由节点 A 同步这个服务 A 的最新信息给到其它节点即可。
             */
            if (!getDistroMapper().responsible(service.getName())) {
                /**
                 * 若当前 Service 不需要当前 Server 负责，则直接结束
                 * 说明当前节点不需要进行服务实例的心跳检查(由其他节点负责)
                 */
                return;
            }

            if (!getSwitchDomain().isHealthCheckEnabled()) {
                // 若当前服务没有开启健康检测功能，则直接结束，默认开启
                return;
            }

            // 获取当前服务的所有临时实例。（ephemeral=true：获取所有临时实例）
            List<Instance> instances = service.allIPs(true);

            // 遍历当前服务的所有临时实例，若临时实例心跳超时，则将健康状态设置为不健康，并发送服务状态变更事件。first set health status of instances:
            for (Instance instance : instances) {
                // 判断心跳间隔，若当前时间距离上次心跳时间已经超过 15s(默认值)，则将当前实例状态设置为不健康。
                if (System.currentTimeMillis() - instance.getLastBeat() > instance.getInstanceHeartBeatTimeOut()) {
                    // 临时实例的"marked"属性值永久为false，若"marked"值为 true，则该实例为持久实例
                    if (!instance.isMarked()) {
                        if (instance.isHealthy()) {
                            // 当前时间距离上次心跳时间已经超过 15s，则将当前实例状态设置为不健康 healthy = false
                            instance.setHealthy(false);
                            Loggers.EVT_LOG
                                    .info("{POS} {IP-DISABLED} valid: {}:{}@{}@{}, region: {}, msg: client timeout after {}, last beat: {}",
                                            instance.getIp(), instance.getPort(), instance.getClusterName(),
                                            service.getName(), UtilsAndCommons.LOCALHOST_SITE,
                                            instance.getInstanceHeartBeatTimeOut(), instance.getLastBeat());
                            // 当前服务发生了状态变更，发布实例状态变更事件，推送该服务下最新的实例信息给客户端
                            getPushService().serviceChanged(service);
                            // 发送心跳非健康的事件
                            ApplicationUtils.publishEvent(new InstanceHeartbeatTimeoutEvent(this, instance));
                        }
                    }
                }
            }

            // 是否需要判断实例的过期状态，默认需要，如果不需要，就不走下面检查实例过期状态的逻辑了
            if (!getGlobalConfig().isExpireInstance()) {
                return;
            }

            // then remove obsolete instances:
            for (Instance instance : instances) {
                // 临时实例的"marked"属性值永久为false，若"marked"值为 true，则该实例为持久实例
                if (instance.isMarked()) {
                    // 跳过持久实例
                    continue;
                }
                // 判断心跳间隔，若当前时间距离上次心跳时间超过 30s(默认值)，说明该实例已经过期了，则将当前实例"清除"
                if (System.currentTimeMillis() - instance.getLastBeat() > instance.getIpDeleteTimeout()) {
                    // delete instance
                    Loggers.SRV_LOG.info("[AUTO-DELETE-IP] service: {}, ip: {}", service.getName(),
                            JacksonUtils.toJson(instance));
                    // "清除"当前遍历到的过期实例
                    deleteIp(instance);
                }
            }

        } catch (Exception e) {
            Loggers.SRV_LOG.warn("Exception while processing client beat time out.", e);
        }

    }

    // "清除"过期实例
    private void deleteIp(Instance instance) {

        try {
            // 构建并初始化一个 request
            NamingProxy.Request request = NamingProxy.Request.newRequest();
            request.appendParam("ip", instance.getIp()).appendParam("port", String.valueOf(instance.getPort()))
                    .appendParam("ephemeral", "true").appendParam("clusterName", instance.getClusterName())
                    .appendParam("serviceName", service.getName()).appendParam("namespaceId", service.getNamespaceId());

            // 构建一个访问自己的请求 url。（IPUtil.localHostIP()=127.0.0.1）
            String url = "http://" + IPUtil.localHostIP() + IPUtil.IP_PORT_SPLITER + EnvUtil.getPort() + EnvUtil.getContextPath()
                    + UtilsAndCommons.NACOS_NAMING_CONTEXT + "/instance?" + request.toUrl();

            /**
             * delete instance asynchronously:
             * 调用 Nacos 自研的 HttpClient 完成 Server 间的请求提交，该 HttpClient 是对 Apache 的 http 异步 Client 的封装。
             * 该请求最终会由 Nacos Server 的 InstanceController.deregister() 来处理，即与处理客户端提交的注销请求的处理方式相同
             */
            HttpClient.asyncHttpDelete(url, null, null, new Callback<String>() {
                @Override
                public void onReceive(RestResult<String> result) {
                    if (!result.ok()) {
                        Loggers.SRV_LOG
                                .error("[IP-DEAD] failed to delete ip automatically, ip: {}, caused {}, resp code: {}",
                                        instance.toJson(), result.getMessage(), result.getCode());
                    }
                }

                @Override
                public void onError(Throwable throwable) {
                    Loggers.SRV_LOG
                            .error("[IP-DEAD] failed to delete ip automatically, ip: {}, error: {}", instance.toJson(),
                                    throwable);
                }

                @Override
                public void onCancel() {

                }
            });

        } catch (Exception e) {
            Loggers.SRV_LOG
                    .error("[IP-DEAD] failed to delete ip automatically, ip: {}, error: {}", instance.toJson(), e);
        }
    }
}
