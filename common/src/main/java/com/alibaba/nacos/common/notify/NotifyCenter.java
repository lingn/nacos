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

import com.alibaba.nacos.api.exception.runtime.NacosRuntimeException;
import com.alibaba.nacos.common.JustForTest;
import com.alibaba.nacos.common.notify.listener.SmartSubscriber;
import com.alibaba.nacos.common.notify.listener.Subscriber;
import com.alibaba.nacos.common.spi.NacosServiceLoader;
import com.alibaba.nacos.common.utils.ClassUtils;
import com.alibaba.nacos.common.utils.MapUtil;
import com.alibaba.nacos.common.utils.ThreadUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.Iterator;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.alibaba.nacos.api.exception.NacosException.SERVER_ERROR;

/**
 * 在Nacos中主要用于注册发布者、调用发布者发布事件、为发布者注册订阅者、为指定的事件增加指定的订阅者等操作
 * 可以说它完全接管了订阅者、发布者和事件他们的组合过程。直接调用通知中心的相关方法即可实现事件发布订阅者注册等功能
 * Unified Event Notify Center.
 *
 * @author <a href="mailto:liaochuntao@live.com">liaochuntao</a>
 * @author zongtanghu
 */
public class NotifyCenter {
    
    private static final Logger LOGGER = LoggerFactory.getLogger(NotifyCenter.class);

    /**
     * 单事件发布者内部的事件队列初始容量
     */
    public static int ringBufferSize;

    /**
     * 多事件发布者内部的事件队列初始容量
     */
    public static int shareBufferSize;

    private static final AtomicBoolean CLOSED = new AtomicBoolean(false);

    /**
     * 构造发布者的工厂
     */
    private static final EventPublisherFactory DEFAULT_PUBLISHER_FACTORY;

    private static final NotifyCenter INSTANCE = new NotifyCenter();

    /**
     * 默认的多事件发布者
     */
    private DefaultSharePublisher sharePublisher;

    /**
     * 默认的单事件发布者类型
     * 此处并未直接指定单事件发布者是谁，只是限定了它的类别
     * 因为单事件发布者一个发布者只负责一个事件，因此会存在
     * 多个发布者实例，后面按需创建，并缓存在publisherMap
     */
    private static Class<? extends EventPublisher> clazz;

    /**
     * Publisher management container.
     * 单事件发布者存储容器
     */
    private final Map<String, EventPublisher> publisherMap = new ConcurrentHashMap<>(16);

    static {
        // Internal ArrayBlockingQueue buffer size. For applications with high write throughput,
        // this value needs to be increased appropriately. default value is 16384
        String ringBufferSizeProperty = "nacos.core.notify.ring-buffer-size";
        ringBufferSize = Integer.getInteger(ringBufferSizeProperty, 16384);
        
        // The size of the public publisher's message staging queue buffer
        String shareBufferSizeProperty = "nacos.core.notify.share-buffer-size";
        shareBufferSize = Integer.getInteger(shareBufferSizeProperty, 1024);

        // 使用Nacos SPI机制获取事件发布者
        final Collection<EventPublisher> publishers = NacosServiceLoader.load(EventPublisher.class);
        Iterator<EventPublisher> iterator = publishers.iterator();
        
        if (iterator.hasNext()) {
            clazz = iterator.next().getClass();
        } else {
            // 若为空，则使用默认的发布器（单事件发布者）
            clazz = DefaultPublisher.class;
        }

        // 声明发布者工厂为一个函数，用于创建发布者实例
        /**
         * 为指定类型的事件创建一个单事件发布者对象
         * @param cls       事件类型
         * @param buffer    发布者内部队列初始容量
         * @return
         */
        DEFAULT_PUBLISHER_FACTORY = (cls, buffer) -> {
            try {
                EventPublisher publisher = clazz.newInstance();
                publisher.init(cls, buffer);
                return publisher;
            } catch (Throwable ex) {
                LOGGER.error("Service class newInstance has error : ", ex);
                throw new NacosRuntimeException(SERVER_ERROR, ex);
            }
        };
        
        try {

            // 初始化多事件发布者
            // Create and init DefaultSharePublisher instance.
            INSTANCE.sharePublisher = new DefaultSharePublisher();
            INSTANCE.sharePublisher.init(SlowEvent.class, shareBufferSize);
            
        } catch (Throwable ex) {
            LOGGER.error("Service class newInstance has error : ", ex);
        }

        // 增加关闭钩子，用于关闭Publisher
        ThreadUtils.addShutdownHook(NotifyCenter::shutdown);
    }
    
    @JustForTest
    public static Map<String, EventPublisher> getPublisherMap() {
        return INSTANCE.publisherMap;
    }
    
    @JustForTest
    public static EventPublisher getPublisher(Class<? extends Event> topic) {
        if (ClassUtils.isAssignableFrom(SlowEvent.class, topic)) {
            return INSTANCE.sharePublisher;
        }
        return INSTANCE.publisherMap.get(topic.getCanonicalName());
    }
    
    @JustForTest
    public static EventPublisher getSharePublisher() {
        return INSTANCE.sharePublisher;
    }
    
    /**
     * Shutdown the several publisher instance which notify center has.
     */
    public static void shutdown() {
        if (!CLOSED.compareAndSet(false, true)) {
            return;
        }
        LOGGER.warn("[NotifyCenter] Start destroying Publisher");
        
        for (Map.Entry<String, EventPublisher> entry : INSTANCE.publisherMap.entrySet()) {
            try {
                EventPublisher eventPublisher = entry.getValue();
                eventPublisher.shutdown();
            } catch (Throwable e) {
                LOGGER.error("[EventPublisher] shutdown has error : ", e);
            }
        }
        
        try {
            INSTANCE.sharePublisher.shutdown();
        } catch (Throwable e) {
            LOGGER.error("[SharePublisher] shutdown has error : ", e);
        }
        
        LOGGER.warn("[NotifyCenter] Destruction of the end");
    }
    
    /**
     * Register a Subscriber. If the Publisher concerned by the Subscriber does not exist, then PublihserMap will
     * preempt a placeholder Publisher with default EventPublisherFactory first.
     *
     * @param consumer subscriber
     */
    public static void registerSubscriber(final Subscriber consumer) {
        registerSubscriber(consumer, DEFAULT_PUBLISHER_FACTORY);
    }
    
    /**
     * Register a Subscriber. If the Publisher concerned by the Subscriber does not exist, then PublihserMap will
     * preempt a placeholder Publisher with specified EventPublisherFactory first.
     *
     * @param consumer subscriber
     * @param factory  publisher factory.
     */
    public static void registerSubscriber(final Subscriber consumer, final EventPublisherFactory factory) {
        // 若想监听多个事件，实现SmartSubscriber.subscribeTypes()方法，在里面返回多个事件的列表即可
        // If you want to listen to multiple events, you do it separately,
        // based on subclass's subscribeTypes method return list, it can register to publisher.
        // 多事件订阅者注册
        if (consumer instanceof SmartSubscriber) {
            // 获取事件列表
            for (Class<? extends Event> subscribeType : ((SmartSubscriber) consumer).subscribeTypes()) {
                // 判断它的事件类型来决定采用哪种Publisher，多事件订阅者由多事件发布者调度
                // For case, producer: defaultSharePublisher -> consumer: smartSubscriber.
                if (ClassUtils.isAssignableFrom(SlowEvent.class, subscribeType)) {
                    //注册到多事件发布者中
                    INSTANCE.sharePublisher.addSubscriber(consumer, subscribeType);
                } else {
                    // 注册到单事件发布者中
                    // For case, producer: defaultPublisher -> consumer: subscriber.
                    addSubscriber(consumer, subscribeType, factory);
                }
            }
            return;
        }

        // 单事件的订阅者注册
        final Class<? extends Event> subscribeType = consumer.subscribeType();
        // 防止误使用，万一有人在使用单事件订阅者Subscriber的时候传入了SlowEvent则可以在此避免
        if (ClassUtils.isAssignableFrom(SlowEvent.class, subscribeType)) {
            INSTANCE.sharePublisher.addSubscriber(consumer, subscribeType);
            return;
        }

        // 注册到单事件发布者中
        addSubscriber(consumer, subscribeType, factory);
    }
    
    /**
     * Add a subscriber to publisher.
     * 单事件发布者添加订阅者
     * 单事件发布者容器内的存储状态为： 事件类型的完整限定名 -> DefaultPublisher.
     * 例如：
     * com.alibaba.nacos.core.cluster.MembersChangeEvent
     * -> {DefaultPublisher@6839} "Thread[nacos.publisher-com.alibaba.nacos.core.cluster.MembersChangeEvent,5,main]"
     *
     * @param consumer      subscriber instance.
     * @param subscribeType subscribeType.
     * @param factory       publisher factory.
     */
    private static void addSubscriber(final Subscriber consumer, Class<? extends Event> subscribeType,
            EventPublisherFactory factory) {

        // 获取类的规范名称，实际上就是包名加类名，作为topic
        final String topic = ClassUtils.getCanonicalName(subscribeType);
        synchronized (NotifyCenter.class) {
            // MapUtils.computeIfAbsent is a unsafe method.
            /**
             * 生成指定类型的发布者，并将其放入publisherMap中
             * 使用topic为key从publisherMap获取数据，若为空则使用publisherFactory函数并传递subscribeType和ringBufferSize来实例
             * 化一个clazz类型的发布者对象，使用topic为key放入publisherMap中，实际上就是为每一个类型的事件创建一个发布者。具体
             * 可查看publisherFactory的逻辑。
             */
            MapUtil.computeIfAbsent(INSTANCE.publisherMap, topic, factory, subscribeType, ringBufferSize);
        }
        // 获取生成的发布者对象，将订阅者添加进去
        EventPublisher publisher = INSTANCE.publisherMap.get(topic);
        if (publisher instanceof ShardedEventPublisher) {
            ((ShardedEventPublisher) publisher).addSubscriber(consumer, subscribeType);
        } else {
            publisher.addSubscriber(consumer);
        }
    }
    
    /**
     * Deregister subscriber.
     *
     * @param consumer subscriber instance.
     */
    public static void deregisterSubscriber(final Subscriber consumer) {
        // 若是多事件订阅者
        if (consumer instanceof SmartSubscriber) {
            // 获取事件列表
            for (Class<? extends Event> subscribeType : ((SmartSubscriber) consumer).subscribeTypes()) {
                // 若是慢事件
                if (ClassUtils.isAssignableFrom(SlowEvent.class, subscribeType)) {
                    // 从多事件发布者中移除
                    INSTANCE.sharePublisher.removeSubscriber(consumer, subscribeType);
                } else {
                    // 从单事件发布者中移除
                    removeSubscriber(consumer, subscribeType);
                }
            }
            return;
        }

        // 若是单事件订阅者
        final Class<? extends Event> subscribeType = consumer.subscribeType();
        // 判断是否是慢事件
        if (ClassUtils.isAssignableFrom(SlowEvent.class, subscribeType)) {
            INSTANCE.sharePublisher.removeSubscriber(consumer, subscribeType);
            return;
        }

        // 调用移除方法
        if (removeSubscriber(consumer, subscribeType)) {
            return;
        }
        throw new NoSuchElementException("The subscriber has no event publisher");
    }
    
    /**
     * Remove subscriber.
     *
     * @param consumer      subscriber instance.
     * @param subscribeType subscribeType.
     * @return whether remove subscriber successfully or not.
     */
    private static boolean removeSubscriber(final Subscriber consumer, Class<? extends Event> subscribeType) {
        
        final String topic = ClassUtils.getCanonicalName(subscribeType);
        // 根据topic获取对应的发布者
        EventPublisher eventPublisher = INSTANCE.publisherMap.get(topic);
        if (null == eventPublisher) {
            return false;
        }
        if (eventPublisher instanceof ShardedEventPublisher) {
            ((ShardedEventPublisher) eventPublisher).removeSubscriber(consumer, subscribeType);
        } else {
            // 从发布者中移除订阅者
            eventPublisher.removeSubscriber(consumer);
        }
        return true;
    }
    
    /**
     * Request publisher publish event Publishers load lazily, calling publisher. Start () only when the event is
     * actually published.
     *
     * @param event class Instances of the event.
     */
    public static boolean publishEvent(final Event event) {
        try {
            return publishEvent(event.getClass(), event);
        } catch (Throwable ex) {
            LOGGER.error("There was an exception to the message publishing : ", ex);
            return false;
        }
    }
    
    /**
     * Request publisher publish event Publishers load lazily, calling publisher.
     *
     * @param eventType class Instances type of the event type.
     * @param event     event instance.
     */
    private static boolean publishEvent(final Class<? extends Event> eventType, final Event event) {
        // 慢事件处理
        if (ClassUtils.isAssignableFrom(SlowEvent.class, eventType)) {
            return INSTANCE.sharePublisher.publish(event);
        }

        // 常规事件处理
        final String topic = ClassUtils.getCanonicalName(eventType);
        
        EventPublisher publisher = INSTANCE.publisherMap.get(topic);
        if (publisher != null) {
            return publisher.publish(event);
        }
        LOGGER.warn("There are no [{}] publishers for this event, please register", topic);
        return false;
    }
    
    /**
     * Register to share-publisher.
     *
     * @param eventType class Instances type of the event type.
     * @return share publisher instance.
     */
    public static EventPublisher registerToSharePublisher(final Class<? extends SlowEvent> eventType) {
        return INSTANCE.sharePublisher;
    }
    
    /**
     * Register publisher with default factory.
     *
     * @param eventType    class Instances type of the event type.
     * @param queueMaxSize the publisher's queue max size.
     */
    public static EventPublisher registerToPublisher(final Class<? extends Event> eventType, final int queueMaxSize) {
        return registerToPublisher(eventType, DEFAULT_PUBLISHER_FACTORY, queueMaxSize);
    }
    
    /**
     * Register publisher with specified factory.
     *
     * @param eventType    class Instances type of the event type.
     * @param factory      publisher factory.
     * @param queueMaxSize the publisher's queue max size.
     */
    public static EventPublisher registerToPublisher(final Class<? extends Event> eventType,
            final EventPublisherFactory factory, final int queueMaxSize) {
        /*
            这里并未有注册动作，若是SlowEvent则直接返回了，为何呢？
            这里再理一下关系，事件的实际用途是由订阅者来决定的，由订阅者来执行对应事件触发后的操作，事件和发布者并没有直接关系。
            而多事件发布者呢，它是一个发布者来处理所有的事件和订阅者（事件：订阅者，一对多的关系），
            这个事件都没人订阅何谈发布呢？因此单纯的注册事件并没有实际意义。
            反观一次只能处理一个事件的单事件处理器(DefaultPublisher)则需要一个事件对应一个发布者，
            即便这个事件没有人订阅，也可以缓存起来
         */
        // 慢事件由多事件发布者处理
        if (ClassUtils.isAssignableFrom(SlowEvent.class, eventType)) {
            return INSTANCE.sharePublisher;
        }

        // 若不是慢事件，因为它可以存在多个不同的类型，因此需要判断对应的发布者是否存在
        final String topic = ClassUtils.getCanonicalName(eventType);
        synchronized (NotifyCenter.class) {
            // MapUtils.computeIfAbsent is a unsafe method.
            // 当前传入的事件类型对应的发布者，有则忽略无则新建
            MapUtil.computeIfAbsent(INSTANCE.publisherMap, topic, factory, eventType, queueMaxSize);
        }
        return INSTANCE.publisherMap.get(topic);
    }
    
    /**
     * Register publisher.
     *
     * @param eventType class Instances type of the event type.
     * @param publisher the specified event publisher
     */
    public static void registerToPublisher(final Class<? extends Event> eventType, final EventPublisher publisher) {
        if (null == publisher) {
            return;
        }
        final String topic = ClassUtils.getCanonicalName(eventType);
        synchronized (NotifyCenter.class) {
            INSTANCE.publisherMap.putIfAbsent(topic, publisher);
        }
    }
    
    /**
     * Deregister publisher.
     *
     * @param eventType class Instances type of the event type.
     */
    public static void deregisterPublisher(final Class<? extends Event> eventType) {
        final String topic = ClassUtils.getCanonicalName(eventType);
        // 根据topic移除对应的发布者
        EventPublisher publisher = INSTANCE.publisherMap.remove(topic);
        try {
            // 调用关闭方法
            publisher.shutdown();
        } catch (Throwable ex) {
            LOGGER.error("There was an exception when publisher shutdown : ", ex);
        }
    }
    
}
