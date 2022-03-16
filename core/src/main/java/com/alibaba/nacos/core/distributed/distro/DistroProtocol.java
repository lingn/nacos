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

package com.alibaba.nacos.core.distributed.distro;

import com.alibaba.nacos.consistency.DataOperation;
import com.alibaba.nacos.core.cluster.Member;
import com.alibaba.nacos.core.cluster.ServerMemberManager;
import com.alibaba.nacos.core.distributed.distro.component.DistroCallback;
import com.alibaba.nacos.core.distributed.distro.component.DistroComponentHolder;
import com.alibaba.nacos.core.distributed.distro.component.DistroDataProcessor;
import com.alibaba.nacos.core.distributed.distro.component.DistroDataStorage;
import com.alibaba.nacos.core.distributed.distro.component.DistroTransportAgent;
import com.alibaba.nacos.core.distributed.distro.entity.DistroData;
import com.alibaba.nacos.core.distributed.distro.entity.DistroKey;
import com.alibaba.nacos.core.distributed.distro.task.DistroTaskEngineHolder;
import com.alibaba.nacos.core.distributed.distro.task.delay.DistroDelayTask;
import com.alibaba.nacos.core.distributed.distro.task.load.DistroLoadDataTask;
import com.alibaba.nacos.core.distributed.distro.task.verify.DistroVerifyTimedTask;
import com.alibaba.nacos.core.utils.GlobalExecutor;
import com.alibaba.nacos.core.utils.Loggers;
import com.alibaba.nacos.sys.env.EnvUtil;
import org.springframework.stereotype.Component;

/**
 * Distro协议的真正入口
 * 这里将使用上面定义的所有组件来共同完实现Distro协议
 * 可以看到它使用了Spring的@Componet注解，意味着它将被Spring容器管理
 * 执行到构造方法的时候将会启动Distro协议的工作
 *
 * 通过DistroProtocol这个类不难发现，
 * 它实现了定时向其他节点报告状态、首次从其他节点加载数据、同步数据到指定节点、获取当前节点的快照数据。
 * 将这些功能组合在一起便可以实现多节点同步。因为所有节点都会做这些操作
 * Distro protocol.
 *
 * @author xiweng.yy
 */
@Component
public class DistroProtocol {

    /**
     * 节点管理器
     */
    private final ServerMemberManager memberManager;

    /**
     * Distro组件持有者
     */
    private final DistroComponentHolder distroComponentHolder;

    /**
     * Distro任务引擎持有者
     */
    private final DistroTaskEngineHolder distroTaskEngineHolder;
    
    private volatile boolean isInitialized = false;
    
    public DistroProtocol(ServerMemberManager memberManager, DistroComponentHolder distroComponentHolder,
            DistroTaskEngineHolder distroTaskEngineHolder) {
        this.memberManager = memberManager;
        this.distroComponentHolder = distroComponentHolder;
        this.distroTaskEngineHolder = distroTaskEngineHolder;
        // 启动Distro协议
        startDistroTask();
    }

    private void startDistroTask() {
        // 单机模式不进行数据同步操作
        if (EnvUtil.getStandaloneMode()) {
            isInitialized = true;
            return;
        }
        // 开启节点Client状态报告任务
        startVerifyTask();
        // 启动数据同步任务
        startLoadTask();
    }

    /**
     * 从其他节点获取数据到当前节点
     */
    private void startLoadTask() {
        DistroCallback loadCallback = new DistroCallback() {
            @Override
            public void onSuccess() {
                isInitialized = true;
            }
            
            @Override
            public void onFailed(Throwable throwable) {
                isInitialized = false;
            }
        };
        // 提交数据加载任务
        GlobalExecutor.submitLoadDataTask(
                new DistroLoadDataTask(memberManager, distroComponentHolder, DistroConfig.getInstance(), loadCallback));
    }
    
    private void startVerifyTask() {
        // 启动数据报告的定时任务
        GlobalExecutor.schedulePartitionDataTimedSync(new DistroVerifyTimedTask(memberManager, distroComponentHolder,
                        distroTaskEngineHolder.getExecuteWorkersManager()),
                DistroConfig.getInstance().getVerifyIntervalMillis());
    }
    
    public boolean isInitialized() {
        return isInitialized;
    }
    
    /**
     * Start to sync by configured delay.
     * 按配置的延迟开始同步
     *
     * @param distroKey distro key of sync data
     * @param action    the action of data operation
     */
    public void sync(DistroKey distroKey, DataOperation action) {
        sync(distroKey, action, DistroConfig.getInstance().getSyncDelayMillis());
    }
    
    /**
     * Start to sync data to all remote server.
     * 开始将数据同步到其他节点
     *
     * @param distroKey distro key of sync data
     * @param action    the action of data operation
     * @param delay     delay time for sync
     */
    public void sync(DistroKey distroKey, DataOperation action, long delay) {
        for (Member each : memberManager.allMembersWithoutSelf()) {
            syncToTarget(distroKey, action, each.getAddress(), delay);
        }
    }
    
    /**
     * Start to sync to target server.
     *
     * @param distroKey    distro key of sync data
     * @param action       the action of data operation
     * @param targetServer target server
     * @param delay        delay time for sync
     */
    public void syncToTarget(DistroKey distroKey, DataOperation action, String targetServer, long delay) {
        DistroKey distroKeyWithTarget = new DistroKey(distroKey.getResourceKey(), distroKey.getResourceType(),
                targetServer);
        DistroDelayTask distroDelayTask = new DistroDelayTask(distroKeyWithTarget, action, delay);
        distroTaskEngineHolder.getDelayTaskExecuteEngine().addTask(distroKeyWithTarget, distroDelayTask);
        if (Loggers.DISTRO.isDebugEnabled()) {
            Loggers.DISTRO.debug("[DISTRO-SCHEDULE] {} to {}", distroKey, targetServer);
        }
    }
    
    /**
     * Query data from specified server.
     * 从指定节点查询数据
     *
     * @param distroKey data key
     * @return data
     */
    public DistroData queryFromRemote(DistroKey distroKey) {
        if (null == distroKey.getTargetServer()) {
            Loggers.DISTRO.warn("[DISTRO] Can't query data from empty server");
            return null;
        }
        String resourceType = distroKey.getResourceType();
        DistroTransportAgent transportAgent = distroComponentHolder.findTransportAgent(resourceType);
        if (null == transportAgent) {
            Loggers.DISTRO.warn("[DISTRO] Can't find transport agent for key {}", resourceType);
            return null;
        }
        // 使用DistroTransportAgent获取数据
        return transportAgent.getData(distroKey, distroKey.getTargetServer());
    }
    
    /**
     * Receive synced distro data, find processor to process.
     * 接收到同步数据，并查找处理器进行处理
     *
     * @param distroData Received data
     * @return true if handle receive data successfully, otherwise false
     */
    public boolean onReceive(DistroData distroData) {
        Loggers.DISTRO.info("[DISTRO] Receive distro data type: {}, key: {}", distroData.getType(),
                distroData.getDistroKey());
        String resourceType = distroData.getDistroKey().getResourceType();
        DistroDataProcessor dataProcessor = distroComponentHolder.findDataProcessor(resourceType);
        if (null == dataProcessor) {
            Loggers.DISTRO.warn("[DISTRO] Can't find data process for received data {}", resourceType);
            return false;
        }
        return dataProcessor.processData(distroData);
    }
    
    /**
     * Receive verify data, find processor to process.
     * 接收到验证数据，并查找处理器进行处理
     *
     * @param distroData    verify data
     * @param sourceAddress source server address, might be get data from source server
     * @return true if verify data successfully, otherwise false
     */
    public boolean onVerify(DistroData distroData, String sourceAddress) {
        if (Loggers.DISTRO.isDebugEnabled()) {
            Loggers.DISTRO.debug("[DISTRO] Receive verify data type: {}, key: {}", distroData.getType(),
                    distroData.getDistroKey());
        }
        // 根据不同类型获取不同的数据处理器
        String resourceType = distroData.getDistroKey().getResourceType();
        DistroDataProcessor dataProcessor = distroComponentHolder.findDataProcessor(resourceType);
        if (null == dataProcessor) {
            Loggers.DISTRO.warn("[DISTRO] Can't find verify data process for received data {}", resourceType);
            return false;
        }
        return dataProcessor.processVerifyData(distroData, sourceAddress);
    }
    
    /**
     * Query data of input distro key.
     * 根据条件查询数据
     *
     * @param distroKey key of data
     * @return data
     */
    public DistroData onQuery(DistroKey distroKey) {
        String resourceType = distroKey.getResourceType();
        DistroDataStorage distroDataStorage = distroComponentHolder.findDataStorage(resourceType);
        if (null == distroDataStorage) {
            Loggers.DISTRO.warn("[DISTRO] Can't find data storage for received key {}", resourceType);
            return new DistroData(distroKey, new byte[0]);
        }
        return distroDataStorage.getDistroData(distroKey);
    }
    
    /**
     * Query all datum snapshot.
     * 查询所有快照数据
     *
     * @param type datum type
     * @return all datum snapshot
     */
    public DistroData onSnapshot(String type) {
        DistroDataStorage distroDataStorage = distroComponentHolder.findDataStorage(type);
        if (null == distroDataStorage) {
            Loggers.DISTRO.warn("[DISTRO] Can't find data storage for received key {}", type);
            return new DistroData(new DistroKey("snapshot", type), new byte[0]);
        }
        return distroDataStorage.getDatumSnapshot();
    }
}
