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

package com.alibaba.cloud.nacos.client;

import java.util.List;

import com.alibaba.cloud.nacos.NacosConfigManager;
import com.alibaba.cloud.nacos.NacosConfigProperties;
import com.alibaba.cloud.nacos.NacosPropertySourceRepository;
import com.alibaba.cloud.nacos.parser.NacosDataParserHandler;
import com.alibaba.cloud.nacos.refresh.NacosContextRefresher;
import com.alibaba.nacos.api.config.ConfigService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.cloud.bootstrap.config.PropertySourceLocator;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.CompositePropertySource;
import org.springframework.core.env.Environment;
import org.springframework.core.env.PropertySource;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

/**
 * @author xiaojing
 * @author pbting
 */
@Order(0)
public class NacosPropertySourceLocator implements PropertySourceLocator {

	private static final Logger log = LoggerFactory
			.getLogger(NacosPropertySourceLocator.class);

	private static final String NACOS_PROPERTY_SOURCE_NAME = "NACOS";

	private static final String SEP1 = "-";

	private static final String DOT = ".";

	private NacosPropertySourceBuilder nacosPropertySourceBuilder;

	private NacosConfigProperties nacosConfigProperties;

	private NacosConfigManager nacosConfigManager;

	/**
	 * recommend to use
	 * {@link NacosPropertySourceLocator#NacosPropertySourceLocator(com.alibaba.cloud.nacos.NacosConfigManager)}.
	 * @param nacosConfigProperties nacosConfigProperties
	 */
	@Deprecated
	public NacosPropertySourceLocator(NacosConfigProperties nacosConfigProperties) {
		this.nacosConfigProperties = nacosConfigProperties;
	}

	public NacosPropertySourceLocator(NacosConfigManager nacosConfigManager) {
		this.nacosConfigManager = nacosConfigManager;
		this.nacosConfigProperties = nacosConfigManager.getNacosConfigProperties();
	}

	// SpringBoot 在启动时，会准备环境，就会运行此方法。此方法会从配置中心加载配置文件
	@Override
	public PropertySource<?> locate(Environment env) {
		// 将应用本身的配置文件 bootstrap.yml 加载进内存中
		nacosConfigProperties.setEnvironment(env);
		ConfigService configService = nacosConfigManager.getConfigService();

		if (null == configService) {
			log.warn("no instance of config service found, can't load config from nacos");
			return null;
		}
		// 配置文件加载的超时时间
		long timeout = nacosConfigProperties.getTimeout();
		nacosPropertySourceBuilder = new NacosPropertySourceBuilder(configService,
				timeout);
		// 获取配置文件中 spring.cloud.nacos.config.name 的属性值，即加载配置文件的名称
		String name = nacosConfigProperties.getName();
		// 获取配置文件中 spring.cloud.nacos.config.prefix 的属性值，即加载配置文件的名称
		String dataIdPrefix = nacosConfigProperties.getPrefix();
		if (StringUtils.isEmpty(dataIdPrefix)) {
			dataIdPrefix = name;
		}
		// 若没有配置 name 与 dataIdPrefix 属性值，则取 spring.application.name 的属性值，即应用名称
		if (StringUtils.isEmpty(dataIdPrefix)) {
			dataIdPrefix = env.getProperty("spring.application.name");
		}

		CompositePropertySource composite = new CompositePropertySource(
				NACOS_PROPERTY_SOURCE_NAME);

		// 加载共享配置（1.先加载本地自身配置，2.若没有本地自身配置，则加载远程配置，3.若也没有远程配置，则加载本地快照配置）
		loadSharedConfiguration(composite);
		// 加载扩展配置（1.先加载本地自身配置，2.若没有本地自身配置，则加载远程配置，3.若也没有远程配置，则加载本地快照配置）
		loadExtConfiguration(composite);
		// 加载应用自身配置（会加载三类配文件：1.加载仅有文件名称，没有扩展名的配置文件，2.加载有文件名称，也有扩展名的配置文件，3.加载有文件名称、有扩展名、并且还包含多环境选择 profile 的配置文件）
		// 并且每类配置文件还分别加载三处位置的配置文件：1.先加载本地自身配置，2.若没有本地自身配置，则加载远程配置，3.若也没有远程配置，则加载本地快照配置
		loadApplicationConfiguration(composite, dataIdPrefix, nacosConfigProperties, env);
		return composite;
	}

	/**
	 * load shared configuration.
	 */
	private void loadSharedConfiguration(
			CompositePropertySource compositePropertySource) {
		// 获取到所有共享配置文件
		List<NacosConfigProperties.Config> sharedConfigs = nacosConfigProperties
				.getSharedConfigs();
		// 只要存在共享配置文件，则进行加载
		if (!CollectionUtils.isEmpty(sharedConfigs)) {
			// 检测所有共享配置文件是否具有 dataId 属性，没有则抛出异常
			checkConfiguration(sharedConfigs, "shared-configs");
			// 加载所有共享配置文件
			loadNacosConfiguration(compositePropertySource, sharedConfigs);
		}
	}

	/**
	 * load extensional configuration.
	 */
	private void loadExtConfiguration(CompositePropertySource compositePropertySource) {
		// 获取到所有扩展配置文件
		List<NacosConfigProperties.Config> extConfigs = nacosConfigProperties
				.getExtensionConfigs();
		if (!CollectionUtils.isEmpty(extConfigs)) {
			checkConfiguration(extConfigs, "extension-configs");
			// 加载所有扩展配置文件
			loadNacosConfiguration(compositePropertySource, extConfigs);
		}
	}

	/**
	 * load configuration of application.
	 */
	private void loadApplicationConfiguration(
			CompositePropertySource compositePropertySource, String dataIdPrefix,
			NacosConfigProperties properties, Environment environment) {
		// 获取配置文件的扩展名（file-extension 属性值）
		String fileExtension = properties.getFileExtension();
		// 获取配置文件所在的 groupId
		String nacosGroup = properties.getGroup();

		// 加载仅有文件名称，没有扩展名的配置文件。load directly once by default
		// 例如：colin-nacos-config-source
		loadNacosDataIfPresent(compositePropertySource, dataIdPrefix, nacosGroup,
				fileExtension, true);

		// 加载有文件名称，也有扩展名的配置文件。load with suffix, which have a higher priority than the default
		// 例如：colin-nacos-config-source.yml
		loadNacosDataIfPresent(compositePropertySource,
				dataIdPrefix + DOT + fileExtension, nacosGroup, fileExtension, true);

		// 加载有文件名称、有扩展名、并且还包含多环境选择 profile 的配置文件。Loaded with profile, which have a higher priority than the suffix
		// 例如：colin-nacos-config-source-test.yml
		for (String profile : environment.getActiveProfiles()) {
			String dataId = dataIdPrefix + SEP1 + profile + DOT + fileExtension;
			loadNacosDataIfPresent(compositePropertySource, dataId, nacosGroup,
					fileExtension, true);
		}

	}

	private void loadNacosConfiguration(final CompositePropertySource composite,
			List<NacosConfigProperties.Config> configs) {
		for (NacosConfigProperties.Config config : configs) {
			// 加载当前遍历的配置文件
			loadNacosDataIfPresent(composite, config.getDataId(), config.getGroup(),
					NacosDataParserHandler.getInstance()
							.getFileExtension(config.getDataId()),
					config.isRefresh());
		}
	}

	private void checkConfiguration(List<NacosConfigProperties.Config> configs,
			String tips) {
		for (int i = 0; i < configs.size(); i++) {
			String dataId = configs.get(i).getDataId();
			// 若配置文件没有 dataId 属性，则抛出异常
			if (dataId == null || dataId.trim().length() == 0) {
				throw new IllegalStateException(String.format(
						"the [ spring.cloud.nacos.config.%s[%s] ] must give a dataId",
						tips, i));
			}
		}
	}

	private void loadNacosDataIfPresent(final CompositePropertySource composite,
			final String dataId, final String group, String fileExtension,
			boolean isRefreshable) {
		if (null == dataId || dataId.trim().length() < 1) {
			return;
		}
		if (null == group || group.trim().length() < 1) {
			return;
		}
		// 加载指定名称的配置文件
		NacosPropertySource propertySource = this.loadNacosPropertySource(dataId, group,
				fileExtension, isRefreshable);
		// 将加载的配置文件添加进 composite 中
		this.addFirstPropertySource(composite, propertySource, false);
	}

	private NacosPropertySource loadNacosPropertySource(final String dataId,
			final String group, String fileExtension, boolean isRefreshable) {
		// 处理配置文件不能自动刷新的情况
		if (NacosContextRefresher.getRefreshCount() != 0) {
			if (!isRefreshable) {
				return NacosPropertySourceRepository.getNacosPropertySource(dataId,
						group);
			}
		}
		// 处理配置文件可以自动刷新的情况
		return nacosPropertySourceBuilder.build(dataId, group, fileExtension,
				isRefreshable);
	}

	/**
	 * Add the nacos configuration to the first place and maybe ignore the empty
	 * configuration.
	 */
	private void addFirstPropertySource(final CompositePropertySource composite,
			NacosPropertySource nacosPropertySource, boolean ignoreEmpty) {
		if (null == nacosPropertySource || null == composite) {
			return;
		}
		if (ignoreEmpty && nacosPropertySource.getSource().isEmpty()) {
			return;
		}
		// 将配置文件添加进 composite 中
		composite.addFirstPropertySource(nacosPropertySource);
	}

	public void setNacosConfigManager(NacosConfigManager nacosConfigManager) {
		this.nacosConfigManager = nacosConfigManager;
	}

}
