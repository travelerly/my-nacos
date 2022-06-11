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

package com.alibaba.nacos.naming.consistency;

import com.alibaba.nacos.api.exception.NacosException;
import com.alibaba.nacos.naming.consistency.ephemeral.EphemeralConsistencyService;
import com.alibaba.nacos.naming.consistency.persistent.PersistentConsistencyServiceDelegateImpl;
import com.alibaba.nacos.naming.pojo.Record;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Service;

/**
 * Consistency delegate.
 *
 * @author nkorange
 * @since 1.0.0
 */
@DependsOn("ProtocolManager")
@Service("consistencyDelegate")
public class DelegateConsistencyServiceImpl implements ConsistencyService {
    
    private final PersistentConsistencyServiceDelegateImpl persistentConsistencyService;
    
    private final EphemeralConsistencyService ephemeralConsistencyService;
    
    public DelegateConsistencyServiceImpl(PersistentConsistencyServiceDelegateImpl persistentConsistencyService,
            EphemeralConsistencyService ephemeralConsistencyService) {
        this.persistentConsistencyService = persistentConsistencyService;
        this.ephemeralConsistencyService = ephemeralConsistencyService;
    }
    
    @Override
    public void put(String key, Record value) throws NacosException {
        // 根据实例是否是临时实例，判断委托对象
        mapConsistencyService(key).put(key, value);
    }
    
    @Override
    public void remove(String key) throws NacosException {
        mapConsistencyService(key).remove(key);
    }
    
    @Override
    public Datum get(String key) throws NacosException {
        return mapConsistencyService(key).get(key);
    }
    
    @Override
    public void listen(String key, RecordListener listener) throws NacosException {
        
        // this special key is listened by both:
        if (KeyBuilder.SERVICE_META_KEY_PREFIX.equals(key)) {
            persistentConsistencyService.listen(key, listener);
            ephemeralConsistencyService.listen(key, listener);
            return;
        }
        
        mapConsistencyService(key).listen(key, listener);
    }
    
    @Override
    public void unListen(String key, RecordListener listener) throws NacosException {
        mapConsistencyService(key).unListen(key, listener);
    }
    
    @Override
    public boolean isAvailable() {
        return ephemeralConsistencyService.isAvailable() && persistentConsistencyService.isAvailable();
    }

    /**
     * 集群一致性的委托方法，根据临时实例和持久实例的不同，委托不同的实现
     * 临时实例：选择 ephemeralConsistencyService，即为 DistroConsistencyServiceImpl
     * 持久实例：选择 PersistentConsistencyServiceDelegateImpl，即为 PersistentConsistencyServiceDelegateImpl
     * @param key 临时实例或持久实例的标识
     * @return
     */
    private ConsistencyService mapConsistencyService(String key) {
        return KeyBuilder.matchEphemeralKey(key) ? ephemeralConsistencyService : persistentConsistencyService;
    }
}
