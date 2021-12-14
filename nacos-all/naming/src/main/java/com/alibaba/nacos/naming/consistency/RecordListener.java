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

import com.alibaba.nacos.naming.pojo.Record;

/**
 * Data listener public interface.
 * 是一个数据监听的接口，用于监听指定数据的变更或删除，泛型指定了当前监听器正在监听的数据类型。
 * @author nacos
 */
public interface RecordListener<T extends Record> {

    /**
     * 判断当前监听器是否监听指定 key 的数据。Determine if the listener was registered with this key.
     *
     * @param key candidate key
     * @return true if the listener was registered with this key
     */
    boolean interests(String key);

    /**
     * 判断当前监听器是否已经不再监听当前指定 key 的数据。Determine if the listener is to be removed by matching the 'key'.
     *
     * @param key key to match
     * @return true if match success
     */
    boolean matchUnlistenKey(String key);

    /**
     * 若当前监听的 key 的数据发生变更，则触发此方法。Action to do if data of target key has changed.
     *
     * @param key   target key
     * @param value data of the key
     * @throws Exception exception
     */
    void onChange(String key, T value) throws Exception;

    /**
     * 若当前被监听的 key 被删除，则触发此方法。Action to do if data of target key has been removed.
     *
     * @param key target key
     * @throws Exception exception
     */
    void onDelete(String key) throws Exception;
}
