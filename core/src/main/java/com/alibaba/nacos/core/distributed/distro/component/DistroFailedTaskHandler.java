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

import com.alibaba.nacos.consistency.DataOperation;
import com.alibaba.nacos.core.distributed.distro.entity.DistroKey;

/**
 * 用于Distro任务失败重试
 * Distro failed task handler.
 *
 * @author xiweng.yy
 */
public interface DistroFailedTaskHandler {
    
    /**
     * Build retry task when distro task execute failed.
     * 当Distro任务执行失败可以创建重试任务
     *
     * @param distroKey distro key of failed task
     *                  失败任务的distroKey
     * @param action action of task
     *               任务的操作类型
     */
    void retry(DistroKey distroKey, DataOperation action);
}
