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

package com.alibaba.nacos.naming.consistency.ephemeral.distro.v2;

import com.alibaba.nacos.common.notify.Event;
import com.alibaba.nacos.common.notify.NotifyCenter;
import com.alibaba.nacos.common.notify.listener.SmartSubscriber;
import com.alibaba.nacos.consistency.DataOperation;
import com.alibaba.nacos.core.distributed.distro.DistroProtocol;
import com.alibaba.nacos.core.distributed.distro.component.DistroDataProcessor;
import com.alibaba.nacos.core.distributed.distro.component.DistroDataStorage;
import com.alibaba.nacos.core.distributed.distro.entity.DistroData;
import com.alibaba.nacos.core.distributed.distro.entity.DistroKey;
import com.alibaba.nacos.naming.cluster.transport.Serializer;
import com.alibaba.nacos.naming.core.v2.ServiceManager;
import com.alibaba.nacos.naming.core.v2.client.Client;
import com.alibaba.nacos.naming.core.v2.client.ClientSyncData;
import com.alibaba.nacos.naming.core.v2.client.ClientSyncDatumSnapshot;
import com.alibaba.nacos.naming.core.v2.client.manager.ClientManager;
import com.alibaba.nacos.naming.core.v2.event.client.ClientEvent;
import com.alibaba.nacos.naming.core.v2.event.client.ClientOperationEvent;
import com.alibaba.nacos.naming.core.v2.event.publisher.NamingEventPublisherFactory;
import com.alibaba.nacos.naming.core.v2.pojo.InstancePublishInfo;
import com.alibaba.nacos.naming.core.v2.pojo.Service;
import com.alibaba.nacos.naming.core.v2.upgrade.UpgradeJudgement;
import com.alibaba.nacos.naming.misc.Loggers;
import com.alibaba.nacos.sys.env.EnvUtil;
import com.alibaba.nacos.sys.utils.ApplicationUtils;

import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;

/**
 * v2版本的实现
 * Distro processor for v2.
 *
 * @author xiweng.yy
 */
public class DistroClientDataProcessor extends SmartSubscriber implements DistroDataStorage, DistroDataProcessor {
    
    public static final String TYPE = "Nacos:Naming:v2:ClientData";
    
    private final ClientManager clientManager;
    
    private final DistroProtocol distroProtocol;
    
    private final UpgradeJudgement upgradeJudgement;
    
    private volatile boolean isFinishInitial;
    
    public DistroClientDataProcessor(ClientManager clientManager, DistroProtocol distroProtocol,
            UpgradeJudgement upgradeJudgement) {
        this.clientManager = clientManager;
        this.distroProtocol = distroProtocol;
        this.upgradeJudgement = upgradeJudgement;
        NotifyCenter.registerSubscriber(this, NamingEventPublisherFactory.getInstance());
    }
    
    @Override
    public void finishInitial() {
        isFinishInitial = true;
    }
    
    @Override
    public boolean isFinishInitial() {
        return isFinishInitial;
    }
    
    @Override
    public List<Class<? extends Event>> subscribeTypes() {
        List<Class<? extends Event>> result = new LinkedList<>();
        result.add(ClientEvent.ClientChangedEvent.class);
        result.add(ClientEvent.ClientDisconnectEvent.class);
        result.add(ClientEvent.ClientVerifyFailedEvent.class);
        return result;
    }
    
    @Override
    public void onEvent(Event event) {
        if (EnvUtil.getStandaloneMode()) {
            return;
        }
        if (!upgradeJudgement.isUseGrpcFeatures()) {
            return;
        }
        if (event instanceof ClientEvent.ClientVerifyFailedEvent) {
            syncToVerifyFailedServer((ClientEvent.ClientVerifyFailedEvent) event);
        } else {
            syncToAllServer((ClientEvent) event);
        }
    }
    
    private void syncToVerifyFailedServer(ClientEvent.ClientVerifyFailedEvent event) {
        Client client = clientManager.getClient(event.getClientId());
        if (null == client || !client.isEphemeral() || !clientManager.isResponsibleClient(client)) {
            return;
        }
        DistroKey distroKey = new DistroKey(client.getClientId(), TYPE);
        // Verify failed data should be sync directly.
        distroProtocol.syncToTarget(distroKey, DataOperation.ADD, event.getTargetServer(), 0L);
    }
    
    private void syncToAllServer(ClientEvent event) {
        Client client = event.getClient();
        // Only ephemeral data sync by Distro, persist client should sync by raft.
        if (null == client || !client.isEphemeral() || !clientManager.isResponsibleClient(client)) {
            return;
        }
        if (event instanceof ClientEvent.ClientDisconnectEvent) {
            DistroKey distroKey = new DistroKey(client.getClientId(), TYPE);
            distroProtocol.sync(distroKey, DataOperation.DELETE);
        } else if (event instanceof ClientEvent.ClientChangedEvent) {
            DistroKey distroKey = new DistroKey(client.getClientId(), TYPE);
            distroProtocol.sync(distroKey, DataOperation.CHANGE);
        }
    }
    
    @Override
    public String processType() {
        return TYPE;
    }
    
    @Override
    public boolean processData(DistroData distroData) {
        switch (distroData.getType()) {
            case ADD:
            case CHANGE:
                ClientSyncData clientSyncData = ApplicationUtils.getBean(Serializer.class)
                        .deserialize(distroData.getContent(), ClientSyncData.class);
                handlerClientSyncData(clientSyncData);
                return true;
            case DELETE:
                String deleteClientId = distroData.getDistroKey().getResourceKey();
                Loggers.DISTRO.info("[Client-Delete] Received distro client sync data {}", deleteClientId);
                clientManager.clientDisconnected(deleteClientId);
                return true;
            default:
                return false;
        }
    }
    
    private void handlerClientSyncData(ClientSyncData clientSyncData) {
        Loggers.DISTRO.info("[Client-Add] Received distro client sync data {}", clientSyncData.getClientId());
        // 因为是同步数据，因此创建IpPortBasedClient，并缓存
        clientManager.syncClientConnected(clientSyncData.getClientId(), clientSyncData.getAttributes());
        Client client = clientManager.getClient(clientSyncData.getClientId());
        // 升级此客户端的服务信息
        upgradeClient(client, clientSyncData);
    }

    /*
        ClientSyncData示例数据

        clientSyncData = {ClientSyncData@12737}
        clientId = "10.55.56.1:8888#true"
        attributes = {ClientSyncAttributes@12740}
        namespaces = {ArrayList@12741}  size = 2
            0 = "public"
            1 = "public"
        groupNames = {ArrayList@12742}  size = 2
            0 = "DEFAULT_GROUP"
            1 = "DEFAULT_GROUP"
        serviceNames = {ArrayList@12743}  size = 2
            0 = "SERVICE_01"
            1 = "SERVICE_02"
        instancePublishInfos = {ArrayList@12744}  size = 2
            0 = {InstancePublishInfo@12941} "InstancePublishInfo{ip='10.55.56.1', port=8888, healthy=false}"
            1 = {InstancePublishInfo@12942} "InstancePublishInfo{ip='10.55.56.1', port=8888, healthy=false}"
     */
    // 通过示例数据可以看出在10.55.56.1这个客户端中有两个服务，他们都在同一个namespace、同一个group中，
    // 因为InstancePublishInfo是和Service一对一的关系，而一个客户端下的服务IP一定和客户端的IP是一致的，所以也会存在两条instance信息。
    // upgradeClient的主要功能就是，将从其他节点获取的所有注册的服务注册到当前节点内。
    private void upgradeClient(Client client, ClientSyncData clientSyncData) {
        // 当前处理的远端节点中的数据集合
        // 获取所有的namespace
        List<String> namespaces = clientSyncData.getNamespaces();
        // 获取所有的groupNames
        List<String> groupNames = clientSyncData.getGroupNames();
        // 获取所有的serviceNames
        List<String> serviceNames = clientSyncData.getServiceNames();
        // 获取所有的instance
        List<InstancePublishInfo> instances = clientSyncData.getInstancePublishInfos();
        // 已同步的服务集合
        Set<Service> syncedService = new HashSet<>();
        // 处理逻辑和ClientSyncData存储的对象有关系
        // 此处是存放的以Service为维度的信息，它将一个Service的全部信息分别保存，并保证所有列表中的数据顺序一致。
        for (int i = 0; i < namespaces.size(); i++) {
            // 从获取的数据中构建一个Service对象
            Service service = Service.newService(namespaces.get(i), groupNames.get(i), serviceNames.get(i));
            Service singleton = ServiceManager.getInstance().getSingleton(service);
            // 标记此service已被处理
            syncedService.add(singleton);
            // 获取当前的实例
            InstancePublishInfo instancePublishInfo = instances.get(i);
            // 判断是否已经包含当前实例
            if (!instancePublishInfo.equals(client.getInstancePublishInfo(singleton))) {
                // 不包含则添加
                client.addServiceInstance(singleton, instancePublishInfo);
                // 当前节点发布服务注册事件
                NotifyCenter.publishEvent(new ClientOperationEvent.ClientRegisterServiceEvent(singleton, client.getClientId()));
            }
        }
        // 若当前client内部已发布的service不在本次同步的列表内，说明已经过时了，要删掉
        for (Service each : client.getAllPublishedService()) {
            if (!syncedService.contains(each)) {
                client.removeServiceInstance(each);
                // 发布客户端下线事件
                // TODO 这里有个疑问，首次同步数据只会执行一次拉取（若拉取失败则会再次拉取，若拉取过后没有数据也不会再次拉取），
                //  而且拉取的是某个节点负责的服务数据，为何当前节点要发布事件呢？服务的状态维护不是应该由它负责的节点来维护嘛，
                //  比如我拉取的A服务是B节点的，我同步过来就OK了，如果A服务下线了，B节点来触发变更不就行了。然后再通知其他节点下线。
                NotifyCenter.publishEvent(new ClientOperationEvent.ClientDeregisterServiceEvent(each, client.getClientId()));
            }
        }
    }
    
    @Override
    public boolean processVerifyData(DistroData distroData, String sourceAddress) {
        DistroClientVerifyInfo verifyData = ApplicationUtils.getBean(Serializer.class)
                .deserialize(distroData.getContent(), DistroClientVerifyInfo.class);
        if (clientManager.verifyClient(verifyData.getClientId())) {
            return true;
        }
        Loggers.DISTRO.info("client {} is invalid, get new client from {}", verifyData.getClientId(), sourceAddress);
        return false;
    }
    
    @Override
    public boolean processSnapshot(DistroData distroData) {
        // 反序列化获取的DistroData为ClientSyncDatumSnapshot
        ClientSyncDatumSnapshot snapshot = ApplicationUtils.getBean(Serializer.class)
                .deserialize(distroData.getContent(), ClientSyncDatumSnapshot.class);
        // 处理结果集，这里将返回远程节点负责的所有client以及client下面的service、instance信息
        for (ClientSyncData each : snapshot.getClientSyncDataList()) {
            // 每次处理一个client
            handlerClientSyncData(each);
        }
        return true;
    }
    
    @Override
    public DistroData getDistroData(DistroKey distroKey) {
        // 从Client管理器中获取指定Client
        Client client = clientManager.getClient(distroKey.getResourceKey());
        if (null == client) {
            return null;
        }
        byte[] data = ApplicationUtils.getBean(Serializer.class).serialize(client.generateSyncData());
        return new DistroData(distroKey, data);
    }
    
    @Override
    public DistroData getDatumSnapshot() {
        List<ClientSyncData> datum = new LinkedList<>();
        // 从Client管理器中获取所有Client
        for (String each : clientManager.allClientId()) {
            Client client = clientManager.getClient(each);
            if (null == client || !client.isEphemeral()) {
                continue;
            }
            datum.add(client.generateSyncData());
        }
        ClientSyncDatumSnapshot snapshot = new ClientSyncDatumSnapshot();
        snapshot.setClientSyncDataList(datum);
        byte[] data = ApplicationUtils.getBean(Serializer.class).serialize(snapshot);
        return new DistroData(new DistroKey(DataOperation.SNAPSHOT.name(), TYPE), data);
    }
    
    @Override
    public List<DistroData> getVerifyData() {
        List<DistroData> result = new LinkedList<>();
        // 从Client管理器中获取所有Client
        for (String each : clientManager.allClientId()) {
            Client client = clientManager.getClient(each);
            if (null == client || !client.isEphemeral()) {
                continue;
            }
            if (clientManager.isResponsibleClient(client)) {
                // TODO add revision for client.
                DistroClientVerifyInfo verifyData = new DistroClientVerifyInfo(client.getClientId(), 0);
                DistroKey distroKey = new DistroKey(client.getClientId(), TYPE);
                DistroData data = new DistroData(distroKey,
                        ApplicationUtils.getBean(Serializer.class).serialize(verifyData));
                data.setType(DataOperation.VERIFY);
                result.add(data);
            }
        }
        return result;
    }
}
