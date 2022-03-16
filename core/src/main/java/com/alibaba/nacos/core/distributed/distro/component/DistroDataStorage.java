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

package com.alibaba.nacos.core.distributed.distro.component;

import com.alibaba.nacos.core.distributed.distro.entity.DistroData;
import com.alibaba.nacos.core.distributed.distro.entity.DistroKey;

import java.util.List;

/**
 * DistroData的存储器
 * Distro data storage.
 *
 * @author xiweng.yy
 */
public interface DistroDataStorage {
    
    /**
     * Set this distro data storage has finished initial step.
     * 设置当前存储器已经初始化完毕它内部的DistroData
     */
    void finishInitial();
    
    /**
     * Whether this distro data is finished initial.
     * 当前存储器是否已经初始化完毕内部的DistroData
     *
     * <p>If not finished, this data storage should not send verify data to other node.
     *
     * @return {@code true} if finished, otherwise false
     */
    boolean isFinishInitial();
    
    /**
     * Get distro datum.
     * 获取内部的DistroData
     *
     * @param distroKey key of distro datum
     *                  数据对应的key
     * @return need to sync datum
     */
    DistroData getDistroData(DistroKey distroKey);
    
    /**
     * Get all distro datum snapshot.
     * 获取内部存储的所有DistroData
     *
     * @return all datum
     */
    DistroData getDatumSnapshot();
    
    /**
     * Get verify datum.
     * 获取所有的DistroData用于验证
     *
     * @return verify datum
     */
    List<DistroData> getVerifyData();
}
