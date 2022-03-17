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
import com.alibaba.nacos.common.utils.ConcurrentHashSet;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 用于发布SlowEvent事件并通知所有订阅了该事件的订阅者
 * The default share event publisher implementation for slow event.
 *
 * @author zongtanghu
 */
public class DefaultSharePublisher extends DefaultPublisher implements ShardedEventPublisher {

    // 用于保存事件类型为SlowEvent的订阅者，一个事件类型对应多个订阅者
    private final Map<Class<? extends SlowEvent>, Set<Subscriber>> subMappings = new ConcurrentHashMap<>();
    
    private final Lock lock = new ReentrantLock();
    
    @Override
    public void addSubscriber(Subscriber subscriber, Class<? extends Event> subscribeType) {
        // 将事件类型转换为当前发布者支持的类型
        // Actually, do a classification based on the slowEvent type.
        Class<? extends SlowEvent> subSlowEventType = (Class<? extends SlowEvent>) subscribeType;
        // 添加到父类的订阅者列表中，为何要添加呢？因为它需要使用父类的队列消费逻辑
        // For adding to parent class attributes synchronization.
        subscribers.add(subscriber);
        lock.lock();
        try {
            // 首先从事件订阅列表里面获取当前事件对应的订阅者集合
            Set<Subscriber> sets = subMappings.get(subSlowEventType);
            // 若没有订阅者，则新增当前订阅者
            if (sets == null) {
                // 这里实际上使用的是自己实现的ConcurrentHashSet，它内部使用了ConcurrentHashMap来实现存储
                Set<Subscriber> newSet = new ConcurrentHashSet<Subscriber>();
                // add方法以当前插入的Subscriber对象为key，以一个Boolean值占位：map.putIfAbsent(o, Boolean.TRUE)
                newSet.add(subscriber);
                /*
                    事件类型和订阅者的存储状态为：
                    EventType1 -> {Subscriber1, Subscriber2, Subscriber3...}
                    EventType2 -> {Subscriber1, Subscriber2, Subscriber3...}
                    EventType3 -> {Subscriber1, Subscriber2, Subscriber3...}
                 */
                subMappings.put(subSlowEventType, newSet);
                return;
            }
            // 若当前事件订阅者列表不为空，则插入，因为使用的是Set集合因此可以避免重复数据
            sets.add(subscriber);
        } finally {
            lock.unlock();
        }
    }
    
    @Override
    public void removeSubscriber(Subscriber subscriber, Class<? extends Event> subscribeType) {
        // 转换类型
        // Actually, do a classification based on the slowEvent type.
        Class<? extends SlowEvent> subSlowEventType = (Class<? extends SlowEvent>) subscribeType;
        // 先移除父类中的订阅者
        // For removing to parent class attributes synchronization.
        subscribers.remove(subscriber);
        lock.lock();
        try {
            // 移除指定事件的指定订阅者
            Set<Subscriber> sets = subMappings.get(subSlowEventType);

            if (sets != null) {
                sets.remove(subscriber);
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * 接收事件
     * @param event {@link Event}.
     */
    @Override
    public void receiveEvent(Event event) {

        // 获取当前事件的序列号
        final long currentEventSequence = event.sequence();
        // 获取事件的类型，转换为当前发布器支持的事件
        // get subscriber set based on the slow EventType.
        final Class<? extends SlowEvent> slowEventType = (Class<? extends SlowEvent>) event.getClass();

        // 获取当前事件的订阅者列表
        // Get for Map, the algorithm is O(1).
        Set<Subscriber> subscribers = subMappings.get(slowEventType);
        if (null == subscribers) {
            LOGGER.debug("[NotifyCenter] No subscribers for slow event {}", slowEventType.getName());
            return;
        }

        // 循环通知所有订阅者
        // Notification single event subscriber
        for (Subscriber subscriber : subscribers) {
            // Whether to ignore expiration events
            if (subscriber.ignoreExpireEvent() && lastEventSequence > currentEventSequence) {
                LOGGER.debug("[NotifyCenter] the {} is unacceptable to this subscriber, because had expire", event.getClass());
                continue;
            }
            // 通知逻辑和父类是共用的
            // Notify single subscriber for slow event.
            notifySubscriber(subscriber, event);
        }
    }
}
