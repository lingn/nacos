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

/**
 * 用于处理Distro协议的数据对象
 * Distro data processor.
 *
 * @author xiweng.yy
 */
public interface DistroDataProcessor {
    
    /**
     * Process type of this processor.
     * 当前处理器可处理的类型
     *
     * @return type of this processor
     */
    String processType();
    
    /**
     * Process received data.
     * 处理接收到的数据
     *
     * @param distroData received data
     *                   接收到的数据对象
     * @return true if process data successfully, otherwise false
     */
    boolean processData(DistroData distroData);
    
    /**
     * Process received verify data.
     * 处理接收到的验证类型的数据
     *
     * @param distroData    verify data
     *                      被处理的数据
     * @param sourceAddress source server address, might be get data from source server
     *                      被处理数据的来源服务器
     * @return true if the data is available, otherwise false
     */
    boolean processVerifyData(DistroData distroData, String sourceAddress);
    
    /**
     * Process snapshot data.
     * 处理快照数据
     *
     * @param distroData snapshot data
     * @return true if process data successfully, otherwise false
     */
    boolean processSnapshot(DistroData distroData);
}
