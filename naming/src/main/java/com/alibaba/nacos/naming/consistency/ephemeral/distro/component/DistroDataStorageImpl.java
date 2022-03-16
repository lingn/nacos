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

package com.alibaba.nacos.naming.consistency.ephemeral.distro.component;

import com.alibaba.nacos.consistency.DataOperation;
import com.alibaba.nacos.core.distributed.distro.component.DistroDataStorage;
import com.alibaba.nacos.core.distributed.distro.entity.DistroData;
import com.alibaba.nacos.core.distributed.distro.entity.DistroKey;
import com.alibaba.nacos.naming.cluster.transport.Serializer;
import com.alibaba.nacos.naming.consistency.Datum;
import com.alibaba.nacos.naming.consistency.KeyBuilder;
import com.alibaba.nacos.naming.consistency.ephemeral.distro.DataStore;
import com.alibaba.nacos.naming.consistency.ephemeral.distro.combined.DistroHttpCombinedKey;
import com.alibaba.nacos.naming.core.DistroMapper;
import com.alibaba.nacos.naming.core.v2.upgrade.UpgradeJudgement;
import com.alibaba.nacos.sys.utils.ApplicationUtils;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * v1版本的实现
 * Distro data storage impl.
 *
 * @author xiweng.yy
 */
public class DistroDataStorageImpl implements DistroDataStorage {
    
    private final DataStore dataStore;
    
    private final DistroMapper distroMapper;
    
    private volatile boolean isFinishInitial;
    
    public DistroDataStorageImpl(DataStore dataStore, DistroMapper distroMapper) {
        this.dataStore = dataStore;
        this.distroMapper = distroMapper;
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
    public DistroData getDistroData(DistroKey distroKey) {
        Map<String, Datum> result = new HashMap<>(2);
        if (distroKey instanceof DistroHttpCombinedKey) {
            result = dataStore.batchGet(((DistroHttpCombinedKey) distroKey).getActualResourceTypes());
        } else {
            Datum datum = dataStore.get(distroKey.getResourceKey());
            result.put(distroKey.getResourceKey(), datum);
        }
        byte[] dataContent = ApplicationUtils.getBean(Serializer.class).serialize(result);
        return new DistroData(distroKey, dataContent);
    }
    
    @Override
    public DistroData getDatumSnapshot() {
        Map<String, Datum> result = dataStore.getDataMap();
        byte[] dataContent = ApplicationUtils.getBean(Serializer.class).serialize(result);
        DistroKey distroKey = new DistroKey(KeyBuilder.RESOURCE_KEY_SNAPSHOT, KeyBuilder.INSTANCE_LIST_KEY_PREFIX);
        return new DistroData(distroKey, dataContent);
    }
    
    @Override
    public List<DistroData> getVerifyData() {
        // If upgrade to 2.0.X, do not verify for v1.
        if (ApplicationUtils.getBean(UpgradeJudgement.class).isUseGrpcFeatures()) {
            return Collections.emptyList();
        }
        // 用于保存属于当前节点处理的，且数据真实有效的Service
        Map<String, String> keyChecksums = new HashMap<>(64);
        // 遍历当前节点已有的datastore
        for (String key : dataStore.keys()) {
            // 若当前的服务不是本机处理，则排除
            if (!distroMapper.responsible(KeyBuilder.getServiceName(key))) {
                continue;
            }
            // 若当key对应的数据为空，则排除
            Datum datum = dataStore.get(key);
            if (datum == null) {
                continue;
            }
            keyChecksums.put(key, datum.value.getChecksum());
        }
        if (keyChecksums.isEmpty()) {
            return Collections.emptyList();
        }
        // 构建DistroData
        DistroKey distroKey = new DistroKey("checksum", KeyBuilder.INSTANCE_LIST_KEY_PREFIX);
        DistroData data = new DistroData(distroKey, ApplicationUtils.getBean(Serializer.class).serialize(keyChecksums));
        // 设置当前操作类型为Verify
        data.setType(DataOperation.VERIFY);
        return Collections.singletonList(data);
    }
}
