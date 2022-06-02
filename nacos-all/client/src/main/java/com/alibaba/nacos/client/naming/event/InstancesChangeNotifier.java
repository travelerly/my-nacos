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

package com.alibaba.nacos.client.naming.event;

import com.alibaba.nacos.api.naming.listener.AbstractEventListener;
import com.alibaba.nacos.api.naming.listener.EventListener;
import com.alibaba.nacos.api.naming.listener.NamingEvent;
import com.alibaba.nacos.api.naming.pojo.ServiceInfo;
import com.alibaba.nacos.common.notify.Event;
import com.alibaba.nacos.common.notify.listener.Subscriber;
import com.alibaba.nacos.common.utils.CollectionUtils;
import com.alibaba.nacos.common.utils.ConcurrentHashSet;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A subscriber to notify eventListener callback.
 *
 * @author horizonzy
 * @since 1.4.1
 */
public class InstancesChangeNotifier extends Subscriber<InstancesChangeEvent> {
    
    private final Map<String, ConcurrentHashSet<EventListener>> listenerMap = new ConcurrentHashMap<String, ConcurrentHashSet<EventListener>>();
    
    private final Object lock = new Object();
    
    /**
     * 注册监听器。register listener.
     *
     * @param serviceName 服务名称，'组名@@服务名'。combineServiceName, such as 'xxx@@xxx'
     * @param clusters    集群名称，逗号分割。clusters, concat by ','. such as 'xxx,yyy'
     * @param listener    事件监听器。custom listener
     */
    public void registerListener(String serviceName, String clusters, EventListener listener) {
        // key：serviceName@@clusters
        String key = ServiceInfo.getKey(serviceName, clusters);
        ConcurrentHashSet<EventListener> eventListeners = listenerMap.get(key);
        if (eventListeners == null) {
            synchronized (lock) {
                eventListeners = listenerMap.get(key);
                if (eventListeners == null) {
                    eventListeners = new ConcurrentHashSet<EventListener>();
                    listenerMap.put(key, eventListeners);
                }
            }
        }
        // 给指定的集群注册一个事件监听器。
        eventListeners.add(listener);
    }
    
    /**
     * deregister listener.
     *
     * @param serviceName combineServiceName, such as 'xxx@@xxx'
     * @param clusters    clusters, concat by ','. such as 'xxx,yyy'
     * @param listener    custom listener
     */
    public void deregisterListener(String serviceName, String clusters, EventListener listener) {
        String key = ServiceInfo.getKey(serviceName, clusters);
        ConcurrentHashSet<EventListener> eventListeners = listenerMap.get(key);
        if (eventListeners == null) {
            return;
        }
        eventListeners.remove(listener);
        if (CollectionUtils.isEmpty(eventListeners)) {
            listenerMap.remove(key);
        }
    }
    
    /**
     * check serviceName,clusters is subscribed.
     *
     * @param serviceName combineServiceName, such as 'xxx@@xxx'
     * @param clusters    clusters, concat by ','. such as 'xxx,yyy'
     * @return is serviceName,clusters subscribed
     */
    public boolean isSubscribed(String serviceName, String clusters) {
        String key = ServiceInfo.getKey(serviceName, clusters);
        ConcurrentHashSet<EventListener> eventListeners = listenerMap.get(key);
        return CollectionUtils.isNotEmpty(eventListeners);
    }
    
    public List<ServiceInfo> getSubscribeServices() {
        List<ServiceInfo> serviceInfos = new ArrayList<ServiceInfo>();
        for (String key : listenerMap.keySet()) {
            serviceInfos.add(ServiceInfo.fromKey(key));
        }
        return serviceInfos;
    }

    // 订阅者订阅事件的回调
    @Override
    public void onEvent(InstancesChangeEvent event) {
        // key：name@@clusters
        String key = ServiceInfo.getKey(event.getServiceName(), event.getClusters());
        // 根据 key 从 listenerMap 中获取对应的 eventListeners 监听器
        ConcurrentHashSet<EventListener> eventListeners = listenerMap.get(key);
        if (CollectionUtils.isEmpty(eventListeners)) {
            // 跳过没有监听器的场景
            return;
        }

        // 遍历回调所有的监听器
        for (final EventListener listener : eventListeners) {
            final com.alibaba.nacos.api.naming.listener.Event namingEvent = transferToNamingEvent(event);
            // 如果当前监听器的类型是 AbstractEventListener，并且指定了回调的线程池，则调用其 onEvent 方法
            if (listener instanceof AbstractEventListener && ((AbstractEventListener) listener).getExecutor() != null) {
                ((AbstractEventListener) listener).getExecutor().execute(new Runnable() {
                    @Override
                    public void run() {
                        // 在线程池中执行回调逻辑
                        listener.onEvent(namingEvent);
                    }
                });
                continue;
            }

            // 普通类型的监听器直接执行回调方法
            listener.onEvent(namingEvent);
        }
    }
    
    private com.alibaba.nacos.api.naming.listener.Event transferToNamingEvent(
            InstancesChangeEvent instancesChangeEvent) {
        return new NamingEvent(instancesChangeEvent.getServiceName(), instancesChangeEvent.getGroupName(),
                instancesChangeEvent.getClusters(), instancesChangeEvent.getHosts());
    }
    
    @Override
    public Class<? extends Event> subscribeType() {
        return InstancesChangeEvent.class;
    }
    
}
