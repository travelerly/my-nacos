/*
 * Copyright 2013-2018 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.alibaba.cloud.nacos.refresh;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import com.alibaba.cloud.nacos.NacosConfigManager;
import com.alibaba.cloud.nacos.NacosConfigProperties;
import com.alibaba.cloud.nacos.NacosPropertySourceRepository;
import com.alibaba.cloud.nacos.client.NacosPropertySource;
import com.alibaba.nacos.api.config.ConfigService;
import com.alibaba.nacos.api.config.listener.AbstractSharedListener;
import com.alibaba.nacos.api.config.listener.Listener;
import com.alibaba.nacos.api.exception.NacosException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.cloud.endpoint.event.RefreshEvent;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.context.ApplicationListener;

/**
 * On application start up, NacosContextRefresher add nacos listeners to all application
 * level dataIds, when there is a change in the data, listeners will refresh
 * configurations.
 *
 * @author juven.xuxb
 * @author pbting
 */
public class NacosContextRefresher
		implements ApplicationListener<ApplicationReadyEvent>, ApplicationContextAware {

	private final static Logger log = LoggerFactory
			.getLogger(NacosContextRefresher.class);

	private static final AtomicLong REFRESH_COUNT = new AtomicLong(0);

	private NacosConfigProperties nacosConfigProperties;

	private final boolean isRefreshEnabled;

	private final NacosRefreshHistory nacosRefreshHistory;

	private final ConfigService configService;

	private ApplicationContext applicationContext;

	private AtomicBoolean ready = new AtomicBoolean(false);

	private Map<String, Listener> listenerMap = new ConcurrentHashMap<>(16);

	public NacosContextRefresher(NacosConfigManager nacosConfigManager,
			NacosRefreshHistory refreshHistory) {
		this.nacosConfigProperties = nacosConfigManager.getNacosConfigProperties();
		this.nacosRefreshHistory = refreshHistory;
		this.configService = nacosConfigManager.getConfigService();
		this.isRefreshEnabled = this.nacosConfigProperties.isRefreshEnabled();
	}

	/**
	 * recommend to use
	 * {@link NacosContextRefresher#NacosContextRefresher(NacosConfigManager, NacosRefreshHistory)}.
	 * @param refreshProperties refreshProperties
	 * @param refreshHistory refreshHistory
	 * @param configService configService
	 */
	@Deprecated
	public NacosContextRefresher(NacosRefreshProperties refreshProperties,
			NacosRefreshHistory refreshHistory, ConfigService configService) {
		this.isRefreshEnabled = refreshProperties.isEnabled();
		this.nacosRefreshHistory = refreshHistory;
		this.configService = configService;
	}

	@Override
	public void onApplicationEvent(ApplicationReadyEvent event) {
		// many Spring context
		// 设置 ready 状态为 true
		if (this.ready.compareAndSet(false, true)) {
			// 注册监听
			this.registerNacosListenersForApplications();
		}
	}

	@Override
	public void setApplicationContext(ApplicationContext applicationContext) {
		this.applicationContext = applicationContext;
	}

	/**
	 * register Nacos Listeners.
	 */
	private void registerNacosListenersForApplications() {
		if (isRefreshEnabled()) {
			// 遍历当前应用所需要的所有配置文件
			for (NacosPropertySource propertySource : NacosPropertySourceRepository
					.getAll()) {
				if (!propertySource.isRefreshable()) {
					// 跳过不会自动刷新的配置文件
					continue;
				}
				// 获取当前遍历到的配置文件的名称
				String dataId = propertySource.getDataId();
				// 添加监听器（为当前遍历到的配置文件对应的 CacheData 添加监听器）
				registerNacosListener(propertySource.getGroup(), dataId);
			}
		}
	}

	private void registerNacosListener(final String groupKey, final String dataKey) {
		// 构建出配置文件的 key，格式为："配置文件名称,groupId"，例如："colin-nacos-config-resource,DEFAULT_GROUP"
		String key = NacosPropertySourceRepository.getMapKey(dataKey, groupKey);
		// 若指定的文件 key 没有响应的监听器，则创建一个监听器并写入到缓存 listenrtMap 中（这个 listenrtMap 的 key 为文件 key，value 为其对应的监听器），并返回这个监听器
		// 若指定的文件 key 有响应的监听器，则不再创建监听器，直接返回这个监听器
		Listener listener = listenerMap.computeIfAbsent(key,
				lst -> new AbstractSharedListener() {
					@Override
					public void innerReceive(String dataId, String group,
							String configInfo) {

						// 当其监听器配置文件对应的 CacheData 发生了变更，就会触发该方法调用至此
						// 计数器加一
						refreshCountIncrement();
						// 将本次更新记录到刷新历史缓存中
						nacosRefreshHistory.addRefreshRecord(dataId, group, configInfo);
						// todo feature: support single refresh for listening

						// 发布一个 RefreshEvent 事件
						applicationContext.publishEvent(
								new RefreshEvent(this, null, "Refresh Nacos config"));
						if (log.isDebugEnabled()) {
							log.debug(String.format(
									"Refresh Nacos config group=%s,dataId=%s,configInfo=%s",
									group, dataId, configInfo));
						}
					}
				});
		try {
			// 将监听器注册到 configService 中
			configService.addListener(dataKey, groupKey, listener);
		}
		catch (NacosException e) {
			log.warn(String.format(
					"register fail for nacos listener ,dataId=[%s],group=[%s]", dataKey,
					groupKey), e);
		}
	}

	public NacosConfigProperties getNacosConfigProperties() {
		return nacosConfigProperties;
	}

	public NacosContextRefresher setNacosConfigProperties(
			NacosConfigProperties nacosConfigProperties) {
		this.nacosConfigProperties = nacosConfigProperties;
		return this;
	}

	public boolean isRefreshEnabled() {
		if (null == nacosConfigProperties) {
			return isRefreshEnabled;
		}
		// Compatible with older configurations
		if (nacosConfigProperties.isRefreshEnabled() && !isRefreshEnabled) {
			return false;
		}
		return isRefreshEnabled;
	}

	public static long getRefreshCount() {
		return REFRESH_COUNT.get();
	}

	public static void refreshCountIncrement() {
		REFRESH_COUNT.incrementAndGet();
	}

}
