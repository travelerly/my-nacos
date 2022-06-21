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

package com.alibaba.nacos.api.naming.pojo;

import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;

/**
 * Service of Nacos.
 *
 * <p>We introduce a 'service --> cluster --> instance' model, in which service stores a list of clusters, which contains a
 * list of instances.
 *
 * <p>Typically we put some unique properties between instances to service level.
 *
 * @author nkorange
 */
public class Service implements Serializable {

    private static final long serialVersionUID = -3470985546826874460L;

    /**
     * service name.
     */
    private String name;

    /**
     * 健康保护阈值。protect threshold.
     * 为了防止因过多实例故障，导致所有流量全部流入剩余实例，继而造成流量压力将剩余实例压垮，形成雪崩效应。
     * 应将健康保护阈值定于为一个 0~1 之间的浮点数。
     * 当域名健康实例数占总服务实例数的比例小于该值时，无论实例是否健康，都会将这个实例返回给客户端。
     * 这样虽然损失了一部分流量，但保证了集群中剩余健康实例能正常工作。
     *
     * 与 Eureka 中的保护阈值对比：
     * 1.相同点：都是一个 0-1 的数值，标识健康实例占所有实例的比例。
     * 2.保护方式不同：
     * Eureka:  一旦健康实例比例小于阈值，则不再从注册表中清除不健康的实例；
     * Nacos:   一旦健康实例比例数量大于阈值，则消费者调用到的都是健康实例；
     *          一旦健康实例比例小于阈值，则消费者会从所有实例中进行选择调用，所以有可能会调用到不健康实例，这样可以保护健康的实例不会被压崩溃。
     * 3.范围不同：
     * Eureka: 这个阈值是针对所有的服务实例；
     * Nacos:  这个阈值是针对当前 Service 中的服务实例。
     */
    private float protectThreshold = 0.0F;

    /**
     * application name of this service.
     */
    private String appName;

    /**
     * Service group to classify services into different sets.
     */
    private String groupName;

    private Map<String, String> metadata = new HashMap<String, String>();

    public Service() {
    }

    public Service(String name) {
        this.name = name;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public float getProtectThreshold() {
        return protectThreshold;
    }

    public void setProtectThreshold(float protectThreshold) {
        this.protectThreshold = protectThreshold;
    }

    public String getAppName() {
        return appName;
    }

    public void setAppName(String appName) {
        this.appName = appName;
    }

    public String getGroupName() {
        return groupName;
    }

    public void setGroupName(String groupName) {
        this.groupName = groupName;
    }

    public Map<String, String> getMetadata() {
        return metadata;
    }

    public void setMetadata(Map<String, String> metadata) {
        this.metadata = metadata;
    }

    public void addMetadata(String key, String value) {
        this.metadata.put(key, value);
    }

    @Override
    public String toString() {
        return "Service{" + "name='" + name + '\'' + ", protectThreshold=" + protectThreshold + ", appName='" + appName
                + '\'' + ", groupName='" + groupName + '\'' + ", metadata=" + metadata + '}';
    }
}
