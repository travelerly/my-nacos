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

package com.alibaba.nacos.common.notify;

import com.alibaba.nacos.common.notify.listener.Subscriber;
import com.alibaba.nacos.common.utils.CollectionUtils;
import com.alibaba.nacos.common.utils.ConcurrentHashSet;
import com.alibaba.nacos.common.utils.ThreadUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

import static com.alibaba.nacos.common.notify.NotifyCenter.ringBufferSize;

/**
 * The default event publisher implementation.
 *
 * <p>Internally, use {@link ArrayBlockingQueue <Event/>} as a message staging queue.
 *
 * @author <a href="mailto:liaochuntao@live.com">liaochuntao</a>
 * @author zongtanghu
 */
public class DefaultPublisher extends Thread implements EventPublisher {
    
    protected static final Logger LOGGER = LoggerFactory.getLogger(NotifyCenter.class);
    
    private volatile boolean initialized = false;
    
    private volatile boolean shutdown = false;
    
    private Class<? extends Event> eventType;
    
    protected final ConcurrentHashSet<Subscriber> subscribers = new ConcurrentHashSet<Subscriber>();
    
    private int queueMaxSize = -1;
    
    private BlockingQueue<Event> queue;
    
    protected volatile Long lastEventSequence = -1L;
    
    private static final AtomicReferenceFieldUpdater<DefaultPublisher, Long> UPDATER = AtomicReferenceFieldUpdater
            .newUpdater(DefaultPublisher.class, Long.class, "lastEventSequence");
    
    @Override
    public void init(Class<? extends Event> type, int bufferSize) {
        setDaemon(true);
        setName("nacos.publisher-" + type.getName());
        this.eventType = type;
        this.queueMaxSize = bufferSize;
        this.queue = new ArrayBlockingQueue<Event>(bufferSize);
        // 开启子线程，在子线程中会不断地从队列中获取到事件并进行处理
        start();
    }
    
    public ConcurrentHashSet<Subscriber> getSubscribers() {
        return subscribers;
    }
    
    @Override
    public synchronized void start() {
        if (!initialized) {
            // start just called once
            super.start();
            if (queueMaxSize == -1) {
                queueMaxSize = ringBufferSize;
            }
            initialized = true;
        }
    }
    
    public long currentEventSize() {
        return queue.size();
    }
    
    @Override
    public void run() {
        openEventHandler();
    }

    /**
     * 处理事件，子线程执行
     */
    void openEventHandler() {
        try {

            // This variable is defined to resolve the problem which message overstock in the queue.
            int waitTimes = 60;
            // To ensure that messages are not lost, enable EventHandler when
            // waiting for the first Subscriber to register
            // 如果客户端没有给该事件发布者设置对应的事件订阅者，那么在子线程执行 60s 后，都会吧未处理的事件从队列中移除。
            for (; ; ) {
                if (shutdown || hasSubscriber() || waitTimes <= 0) {
                    break;
                }
                ThreadUtils.sleep(1000L);
                waitTimes--;
            }

            for (; ; ) {
                if (shutdown) {
                    break;
                }
                final Event event = queue.take();
                // 接收并通知订阅服务器来处理事件。
                receiveEvent(event);
                UPDATER.compareAndSet(this, lastEventSequence, Math.max(lastEventSequence, event.sequence()));
            }
        } catch (Throwable ex) {
            LOGGER.error("Event listener exception : {}", ex);
        }
    }
    
    private boolean hasSubscriber() {
        return CollectionUtils.isNotEmpty(subscribers);
    }
    
    @Override
    public void addSubscriber(Subscriber subscriber) {
        subscribers.add(subscriber);
    }
    
    @Override
    public void removeSubscriber(Subscriber subscriber) {
        subscribers.remove(subscriber);
    }
    
    @Override
    public boolean publish(Event event) {
        checkIsStart();
        /**
         * 往事件队列中添加一个事件，是一个异步操作，
         * DefaultPublisher 在初始化时，会开启一个子线程，在子线程中会不断地从队列中获取到事件并进行处理
         */
        boolean success = this.queue.offer(event);
        // 如果向队列添加事件失败，则手动处理该事件
        if (!success) {
            LOGGER.warn("Unable to plug in due to interruption, synchronize sending time, event : {}", event);
            receiveEvent(event);
            return true;
        }
        return true;
    }
    
    void checkIsStart() {
        if (!initialized) {
            throw new IllegalStateException("Publisher does not start");
        }
    }
    
    @Override
    public void shutdown() {
        this.shutdown = true;
        this.queue.clear();
    }
    
    public boolean isInitialized() {
        return initialized;
    }
    
    /**
     * 接收并通知订阅服务器来处理事件。Receive and notifySubscriber to process the event.
     *
     * @param event 通知事件。{@link Event}.
     */
    void receiveEvent(Event event) {
        final long currentEventSequence = event.sequence();

        // 遍历所有事件订阅者去处理事件。Notification single event listener
        for (Subscriber subscriber : subscribers) {
            // Whether to ignore expiration events
            if (subscriber.ignoreExpireEvent() && lastEventSequence > currentEventSequence) {
                LOGGER.debug("[NotifyCenter] the {} is unacceptable to this subscriber, because had expire",
                        event.getClass());
                continue;
            }

            // Because unifying smartSubscriber and subscriber, so here need to think of compatibility.
            // Remove original judge part of codes.
            // 通知指定的订阅者处理事件。
            notifySubscriber(subscriber, event);
        }
    }

    /**
     * 通知指定的订阅者
     * @param subscriber 订阅者。{@link Subscriber}
     * @param event      通知事件。{@link Event}
     */
    @Override
    public void notifySubscriber(final Subscriber subscriber, final Event event) {

        LOGGER.debug("[NotifyCenter] the {} will received by {}", event, subscriber);

        final Runnable job = new Runnable() {
            @Override
            public void run() {
                subscriber.onEvent(event);
            }
        };

        final Executor executor = subscriber.executor();

        // 指定了线程池，放到线程池中去执行订阅回调，即执行 InstancesChangeNotifier 的 onEvent() 方法
        if (executor != null) {
            executor.execute(job);
        } else { // 没有指定线程池，则直接执行订阅回调，即执行 InstancesChangeNotifier 的 onEvent() 方法
            try {
                job.run();
            } catch (Throwable e) {
                LOGGER.error("Event callback exception : {}", e);
            }
        }
    }
}
