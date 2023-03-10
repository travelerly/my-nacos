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

package com.alibaba.nacos.client.naming.core;

import com.alibaba.nacos.api.exception.NacosException;
import com.alibaba.nacos.common.lifecycle.Closeable;
import com.alibaba.nacos.common.utils.IoUtils;
import com.alibaba.nacos.common.utils.JacksonUtils;
import com.alibaba.nacos.common.utils.StringUtils;
import com.alibaba.nacos.common.utils.ThreadUtils;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.nio.charset.Charset;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;

import static com.alibaba.nacos.client.utils.LogUtils.NAMING_LOGGER;

/**
 * Push receiver.
 *
 * @author xuanyin
 */
public class PushReceiver implements Runnable, Closeable {

    private static final Charset UTF_8 = Charset.forName("UTF-8");

    private static final int UDP_MSS = 64 * 1024;

    private ScheduledExecutorService executorService;

    private DatagramSocket udpSocket;

    private HostReactor hostReactor;

    private volatile boolean closed = false;

    /**
     * 这个类会以 UDP 的方式接收来自 Nacos 服务端推送的服务变更数据。
     * @param hostReactor
     */
    public PushReceiver(HostReactor hostReactor) {
        try {
            this.hostReactor = hostReactor;
            // 创建 UDP 客户端
            this.udpSocket = new DatagramSocket();
            // 准备一个线程池
            this.executorService = new ScheduledThreadPoolExecutor(1, new ThreadFactory() {
                @Override
                public Thread newThread(Runnable r) {
                    Thread thread = new Thread(r);
                    thread.setDaemon(true);
                    thread.setName("com.alibaba.nacos.naming.push.receiver");
                    return thread;
                }
            });
            // 开启线程任务，准备异步执行接收 nacos 服务端推送的变更数据的任务，即执行 PushReceiver.run()
            this.executorService.execute(this);
        } catch (Exception e) {
            NAMING_LOGGER.error("[NA] init udp socket failed", e);
        }
    }

    @Override
    public void run() {

        // 开启一个无限循环
        while (!closed) {
            try {
                // 创建一个 64k 大小的缓冲区。byte[] is initialized with 0 full filled by default
                byte[] buffer = new byte[UDP_MSS];
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);

                // 接收 nacos 服务端推送过来的数据。接收来自 Nacos Server 的 UDP 通信推送的数据，并封装到 packet 数据包中
                udpSocket.receive(packet);

                // 数据解码为 JSON 串
                String json = new String(IoUtils.tryDecompress(packet.getData()), UTF_8).trim();
                NAMING_LOGGER.info("received push data: " + json + " from " + packet.getAddress().toString());

                // 将 JSON 反序列化为 PushPacket 对象
                PushPacket pushPacket = JacksonUtils.toObj(json, PushPacket.class);
                String ack;
                // 根据不同的数据类型，生成对应的 ack
                if ("dom".equals(pushPacket.type) || "service".equals(pushPacket.type)) {
                    // 将来自于 Nacos 服务端推送过来的变更的服务数据更新到当前 Nacos Client 的本地注册表中
                    hostReactor.processServiceJson(pushPacket.data);

                    // send ack to server
                    ack = "{\"type\": \"push-ack\"" + ", \"lastRefTime\":\"" + pushPacket.lastRefTime + "\", \"data\":"
                            + "\"\"}";
                } else if ("dump".equals(pushPacket.type)) {
                    // dump data to server
                    ack = "{\"type\": \"dump-ack\"" + ", \"lastRefTime\": \"" + pushPacket.lastRefTime + "\", \"data\":"
                            + "\"" + StringUtils.escapeJavaScript(JacksonUtils.toJson(hostReactor.getServiceInfoMap()))
                            + "\"}";
                } else {
                    // do nothing send ack only
                    ack = "{\"type\": \"unknown-ack\"" + ", \"lastRefTime\":\"" + pushPacket.lastRefTime
                            + "\", \"data\":" + "\"\"}";
                }

                // 向 Nacos Server 发送响应数据，即发送反馈的 UDP 通信
                udpSocket.send(new DatagramPacket(ack.getBytes(UTF_8), ack.getBytes(UTF_8).length,
                        packet.getSocketAddress()));
            } catch (Exception e) {
                if (closed) {
                    return;
                }
                NAMING_LOGGER.error("[NA] error while receiving push data", e);
            }
        }
    }

    @Override
    public void shutdown() throws NacosException {
        String className = this.getClass().getName();
        NAMING_LOGGER.info("{} do shutdown begin", className);
        ThreadUtils.shutdownThreadPool(executorService, NAMING_LOGGER);
        closed = true;
        udpSocket.close();
        NAMING_LOGGER.info("{} do shutdown stop", className);
    }

    public static class PushPacket {

        public String type;

        public long lastRefTime;

        public String data;
    }

    public int getUdpPort() {
        return this.udpSocket.getLocalPort();
    }
}
