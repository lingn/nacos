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

package com.alibaba.nacos.naming.consistency.ephemeral.distro;

import com.alibaba.nacos.api.common.Constants;
import com.alibaba.nacos.api.exception.NacosException;
import com.alibaba.nacos.consistency.DataOperation;
import com.alibaba.nacos.core.distributed.distro.DistroConfig;
import com.alibaba.nacos.core.distributed.distro.DistroProtocol;
import com.alibaba.nacos.core.distributed.distro.component.DistroDataProcessor;
import com.alibaba.nacos.core.distributed.distro.entity.DistroData;
import com.alibaba.nacos.core.distributed.distro.entity.DistroKey;
import com.alibaba.nacos.naming.cluster.ServerStatus;
import com.alibaba.nacos.naming.cluster.transport.Serializer;
import com.alibaba.nacos.naming.consistency.Datum;
import com.alibaba.nacos.naming.consistency.KeyBuilder;
import com.alibaba.nacos.naming.consistency.RecordListener;
import com.alibaba.nacos.naming.consistency.ephemeral.EphemeralConsistencyService;
import com.alibaba.nacos.naming.consistency.ephemeral.distro.combined.DistroHttpCombinedKey;
import com.alibaba.nacos.naming.core.DistroMapper;
import com.alibaba.nacos.naming.core.Instances;
import com.alibaba.nacos.naming.core.Service;
import com.alibaba.nacos.naming.core.v2.upgrade.UpgradeJudgement;
import com.alibaba.nacos.naming.misc.GlobalConfig;
import com.alibaba.nacos.naming.misc.GlobalExecutor;
import com.alibaba.nacos.naming.misc.Loggers;
import com.alibaba.nacos.naming.misc.SwitchDomain;
import com.alibaba.nacos.naming.pojo.Record;
import com.alibaba.nacos.sys.utils.ApplicationUtils;
import com.alibaba.nacos.common.utils.StringUtils;
import org.javatuples.Pair;
import org.springframework.context.annotation.DependsOn;

import javax.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * A consistency protocol algorithm called <b>Distro</b>
 *
 * <p>Use a distro algorithm to divide data into many blocks. Each Nacos server node takes responsibility for exactly
 * one block of data. Each block of data is generated, removed and synchronized by its responsible server. So every
 * Nacos server only handles writings for a subset of the total service data.
 *
 * <p>At mean time every Nacos server receives data sync of other Nacos server, so every Nacos server will eventually
 * have a complete set of data.
 *
 * @author nkorange
 * @since 1.0.0
 */
@DependsOn("ProtocolManager")
@org.springframework.stereotype.Service("distroConsistencyService")
public class DistroConsistencyServiceImpl implements EphemeralConsistencyService, DistroDataProcessor {
    
    private final DistroMapper distroMapper;
    
    private final DataStore dataStore;
    
    private final Serializer serializer;
    
    private final SwitchDomain switchDomain;
    
    private final GlobalConfig globalConfig;
    
    private final DistroProtocol distroProtocol;
    
    private volatile Notifier notifier = new Notifier();
    
    private Map<String, ConcurrentLinkedQueue<RecordListener>> listeners = new ConcurrentHashMap<>();
    
    private Map<String, String> syncChecksumTasks = new ConcurrentHashMap<>(16);
    
    public DistroConsistencyServiceImpl(DistroMapper distroMapper, DataStore dataStore, Serializer serializer,
            SwitchDomain switchDomain, GlobalConfig globalConfig, DistroProtocol distroProtocol) {
        this.distroMapper = distroMapper;
        this.dataStore = dataStore;
        this.serializer = serializer;
        this.switchDomain = switchDomain;
        this.globalConfig = globalConfig;
        this.distroProtocol = distroProtocol;
    }
    
    @PostConstruct
    public void init() {
        GlobalExecutor.submitDistroNotifyTask(notifier);
    }
    
    @Override
    public void put(String key, Record value) throws NacosException {
        onPut(key, value);
        // If upgrade to 2.0.X, do not sync for v1.
        if (ApplicationUtils.getBean(UpgradeJudgement.class).isUseGrpcFeatures()) {
            return;
        }
        distroProtocol.sync(new DistroKey(key, KeyBuilder.INSTANCE_LIST_KEY_PREFIX), DataOperation.CHANGE,
                DistroConfig.getInstance().getSyncDelayMillis());
    }
    
    @Override
    public void remove(String key) throws NacosException {
        onRemove(key);
        listeners.remove(key);
    }
    
    @Override
    public Datum get(String key) throws NacosException {
        return dataStore.get(key);
    }
    
    /**
     * Put a new record.
     *
     * @param key   key of record
     * @param value record
     */
    public void onPut(String key, Record value) {
        
        if (KeyBuilder.matchEphemeralInstanceListKey(key)) {
            Datum<Instances> datum = new Datum<>();
            datum.value = (Instances) value;
            datum.key = key;
            datum.timestamp.incrementAndGet();
            dataStore.put(key, datum);
        }
        
        if (!listeners.containsKey(key)) {
            return;
        }
        
        notifier.addTask(key, DataOperation.CHANGE);
    }
    
    /**
     * Remove a record.
     *
     * @param key key of record
     */
    public void onRemove(String key) {
        
        dataStore.remove(key);
        
        if (!listeners.containsKey(key)) {
            return;
        }
        
        notifier.addTask(key, DataOperation.DELETE);
    }
    
    /**
     * Check sum when receive checksums request.
     * Service检查服务，用于从其他节点更新已变更的Service
     *
     * @param checksumMap map of checksum
     *                    某个服务对应的checksum
     * @param server      source server request checksum
     *                    checksum请求的来源
     */
    public void onReceiveChecksums(Map<String, String> checksumMap, String server) {

        // 若已包含此节点的信息，说明正在处理（处理完毕之后会清空）
        if (syncChecksumTasks.containsKey(server)) {
            // Already in process of this server:
            Loggers.DISTRO.warn("sync checksum task already in process with {}", server);
            return;
        }
        // 将当前要处理的节点信息暂存，表示当前正在处理
        syncChecksumTasks.put(server, "1");

        try {

            List<String> toUpdateKeys = new ArrayList<>();
            List<String> toRemoveKeys = new ArrayList<>();

            // 第一个for循环

            /**
             * 判断当前节点中哪些服务需要去远程更新
             * 需要更新的服务将被添加至toUpdateKeys
             */
            for (Map.Entry<String, String> entry : checksumMap.entrySet()) {

                /**
                 * 判断当前的entry(指的是服务Service)是否由本节点处理, 若是本机负责的则没必要从其他节点发送过来
                 * 因为在本节点注册的服务最终任何新的操作都会被路由到本机，那么它的状态在本机就是最新的
                 */
                if (distroMapper.responsible(KeyBuilder.getServiceName(entry.getKey()))) {
                    // this key should not be sent from remote server:
                    Loggers.DISTRO.error("receive responsible key timestamp of " + entry.getKey() + " from " + server);
                    // abort the procedure:
                    return;
                }

                // 若当前节点不存在此服务，或服务是空的，或服务的实例列表(checksum验证字符串)跟传入的不一致，则标记此服务需要更新
                if (!dataStore.contains(entry.getKey()) ||
                        dataStore.get(entry.getKey()).value == null ||
                        !dataStore.get(entry.getKey()).value.getChecksum().equals(entry.getValue())) {
                    // 添加到待更新列表
                    toUpdateKeys.add(entry.getKey());
                }
            }


            // 第二个for循环

            /**
             * 此处用于判断当前节点已有的服务是不是当前接收的请求的来源节点负责处理的，如果是的话，就说明他是最新的不应该被删除
             */
            for (String key : dataStore.keys()) {
                // 不是请求方处理的服务就不处理
                if (!server.equals(distroMapper.mapSrv(KeyBuilder.getServiceName(key)))) {
                    continue;
                }

                // 是请求方处理的服务但不在请求列表中，说明此服务在请求方已经被删除了
                if (!checksumMap.containsKey(key)) {
                    toRemoveKeys.add(key);
                }
            }

            Loggers.DISTRO.info("to remove keys: {}, to update keys: {}, source: {}", toRemoveKeys, toUpdateKeys, server);

            // 删掉已经变更的服务
            for (String key : toRemoveKeys) {
                // 移除服务，并发送变更通知
                onRemove(key);
            }

            // 若没有需要更新的终止此流程
            if (toUpdateKeys.isEmpty()) {
                return;
            }

            // 经过上述流程的处理，目前本机缓存的DataStorage中保留的服务是：在其他节点注册的、服务。

            try {
                // 组装DistroData查询对象
                DistroHttpCombinedKey distroKey = new DistroHttpCombinedKey(KeyBuilder.INSTANCE_LIST_KEY_PREFIX, server);
                distroKey.getActualResourceTypes().addAll(toUpdateKeys);
                // 从请求发送方获取最新数据
                DistroData remoteData = distroProtocol.queryFromRemote(distroKey);
                if (null != remoteData) {
                    // 处理查询结果
                    processData(remoteData.getContent());
                }
            } catch (Exception e) {
                Loggers.DISTRO.error("get data from " + server + " failed!", e);
            }
        } finally {
            // 全部处理完毕之后，将syncChecksumTasks中标记正在处理的节点移除
            // Remove this 'in process' flag:
            syncChecksumTasks.remove(server);
        }
    }
    
    private boolean processData(byte[] data) throws Exception {
        if (data.length > 0) {
            // 序列化将要解析的值
            Map<String, Datum<Instances>> datumMap = serializer.deserializeMap(data, Instances.class);

            // 此for循环主要功能是用于将获取到的Service添加到监听器列表
            for (Map.Entry<String, Datum<Instances>> entry : datumMap.entrySet()) {
                // 遍历并放入数据仓库中
                dataStore.put(entry.getKey(), entry.getValue());
                // ① 判断是否有当前服务的监听器，若有则触发监听器用于更新服务信息
                if (!listeners.containsKey(entry.getKey())) {
                    // 因为当前的类是用于处理临时节点的同步信息的（详情请看v1版本ServiceManager内部的逻辑）
                    // pretty sure the service not exist:
                    if (switchDomain.isDefaultInstanceEphemeral()) {
                        // 根据获取的数据信息构建一个v1版本的Service对象
                        // create empty service
                        Loggers.DISTRO.info("creating service {}", entry.getKey());
                        Service service = new Service();
                        String serviceName = KeyBuilder.getServiceName(entry.getKey());
                        String namespaceId = KeyBuilder.getNamespace(entry.getKey());
                        service.setName(serviceName);
                        service.setNamespaceId(namespaceId);
                        service.setGroupName(Constants.DEFAULT_GROUP);
                        // now validate the service. if failed, exception will be thrown
                        service.setLastModifiedMillis(System.currentTimeMillis());
                        service.recalculateChecksum();
                        // 尤其是要注意此处的key类型，因为listener集合内部可能存放多种类型的监听器
                        // ② 获取ServiceManager，因为其也是一个监听器
                        // The Listener corresponding to the key value must not be empty
                        RecordListener listener = listeners.get(KeyBuilder.SERVICE_META_KEY_PREFIX).peek();
                        if (Objects.isNull(listener)) {
                            return false;
                        }
                        // 触发ServiceManager的onChange事件，
                        listener.onChange(KeyBuilder.buildServiceMetaKey(namespaceId, serviceName), service);
                    }
                }
            }

            // 此for循环主要用于触发监听器列表中的Service监听器
            for (Map.Entry<String, Datum<Instances>> entry : datumMap.entrySet()) {

                if (!listeners.containsKey(entry.getKey())) {
                    // Should not happen:
                    Loggers.DISTRO.warn("listener of {} not found.", entry.getKey());
                    continue;
                }

                try {
                    for (RecordListener listener : listeners.get(entry.getKey())) {
                        listener.onChange(entry.getKey(), entry.getValue().value);
                    }
                } catch (Exception e) {
                    Loggers.DISTRO.error("[NACOS-DISTRO] error while execute listener of key: {}", entry.getKey(), e);
                    continue;
                }

                // Update data store if listener executed successfully:
                dataStore.put(entry.getKey(), entry.getValue());
            }
        }
        return true;
    }
    
    @Override
    public boolean processData(DistroData distroData) {
        DistroHttpData distroHttpData = (DistroHttpData) distroData;
        Datum<Instances> datum = (Datum<Instances>) distroHttpData.getDeserializedContent();
        onPut(datum.key, datum.value);
        return true;
    }
    
    @Override
    public String processType() {
        return KeyBuilder.INSTANCE_LIST_KEY_PREFIX;
    }
    
    @Override
    public boolean processVerifyData(DistroData distroData, String sourceAddress) {
        // If upgrade to 2.0.X, do not verify for v1.
        if (ApplicationUtils.getBean(UpgradeJudgement.class).isUseGrpcFeatures()) {
            return true;
        }
        DistroHttpData distroHttpData = (DistroHttpData) distroData;
        Map<String, String> verifyData = (Map<String, String>) distroHttpData.getDeserializedContent();
        onReceiveChecksums(verifyData, sourceAddress);
        return true;
    }
    
    @Override
    public boolean processSnapshot(DistroData distroData) {
        try {
            return processData(distroData.getContent());
        } catch (Exception e) {
            return false;
        }
    }
    
    @Override
    public void listen(String key, RecordListener listener) throws NacosException {
        if (!listeners.containsKey(key)) {
            listeners.put(key, new ConcurrentLinkedQueue<>());
        }
        
        if (listeners.get(key).contains(listener)) {
            return;
        }
        
        listeners.get(key).add(listener);
    }
    
    @Override
    public void unListen(String key, RecordListener listener) throws NacosException {
        if (!listeners.containsKey(key)) {
            return;
        }
        for (RecordListener recordListener : listeners.get(key)) {
            if (recordListener.equals(listener)) {
                listeners.get(key).remove(listener);
                break;
            }
        }
    }
    
    @Override
    public boolean isAvailable() {
        return isInitialized() || ServerStatus.UP.name().equals(switchDomain.getOverriddenServerStatus());
    }
    
    @Override
    public Optional<String> getErrorMsg() {
        String errorMsg;
        if (!isInitialized() && !ServerStatus.UP.name().equals(switchDomain.getOverriddenServerStatus())) {
            errorMsg = "Distro protocol is not initialized";
        } else {
            errorMsg = null;
        }
        return Optional.ofNullable(errorMsg);
    }
    
    public boolean isInitialized() {
        return distroProtocol.isInitialized() || !globalConfig.isDataWarmup();
    }
    
    public class Notifier implements Runnable {
        
        private ConcurrentHashMap<String, String> services = new ConcurrentHashMap<>(10 * 1024);
        
        private BlockingQueue<Pair<String, DataOperation>> tasks = new ArrayBlockingQueue<>(1024 * 1024);
        
        /**
         * Add new notify task to queue.
         *
         * @param datumKey data key
         * @param action   action for data
         */
        public void addTask(String datumKey, DataOperation action) {
            
            if (services.containsKey(datumKey) && action == DataOperation.CHANGE) {
                return;
            }
            if (action == DataOperation.CHANGE) {
                services.put(datumKey, StringUtils.EMPTY);
            }
            tasks.offer(Pair.with(datumKey, action));
        }
        
        public int getTaskSize() {
            return tasks.size();
        }
        
        @Override
        public void run() {
            Loggers.DISTRO.info("distro notifier started");
            
            for (; ; ) {
                try {
                    Pair<String, DataOperation> pair = tasks.take();
                    handle(pair);
                } catch (Throwable e) {
                    Loggers.DISTRO.error("[NACOS-DISTRO] Error while handling notifying task", e);
                }
            }
        }
        
        private void handle(Pair<String, DataOperation> pair) {
            try {
                String datumKey = pair.getValue0();
                DataOperation action = pair.getValue1();
                
                services.remove(datumKey);
                
                int count = 0;
                
                if (!listeners.containsKey(datumKey)) {
                    return;
                }
                
                for (RecordListener listener : listeners.get(datumKey)) {
                    
                    count++;
                    
                    try {
                        if (action == DataOperation.CHANGE) {
                            listener.onChange(datumKey, dataStore.get(datumKey).value);
                            continue;
                        }
                        
                        if (action == DataOperation.DELETE) {
                            listener.onDelete(datumKey);
                            continue;
                        }
                    } catch (Throwable e) {
                        Loggers.DISTRO.error("[NACOS-DISTRO] error while notifying listener of key: {}", datumKey, e);
                    }
                }
                
                if (Loggers.DISTRO.isDebugEnabled()) {
                    Loggers.DISTRO
                            .debug("[NACOS-DISTRO] datum change notified, key: {}, listener count: {}, action: {}",
                                    datumKey, count, action.name());
                }
            } catch (Throwable e) {
                Loggers.DISTRO.error("[NACOS-DISTRO] Error while handling notifying task", e);
            }
        }
    }
}
