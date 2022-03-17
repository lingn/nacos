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

package com.alibaba.nacos.naming.healthcheck.interceptor;

import com.alibaba.nacos.naming.healthcheck.NacosHealthCheckTask;
import com.alibaba.nacos.naming.interceptor.AbstractNamingInterceptorChain;

/**
 * Health check interceptor chain.
 *
 * @author xiweng.yy
 */
public class HealthCheckInterceptorChain extends AbstractNamingInterceptorChain<NacosHealthCheckTask> {
    
    private static final HealthCheckInterceptorChain INSTANCE = new HealthCheckInterceptorChain();

    // 健康检查拦截器链负责加载AbstractHealthCheckInterceptor类型的拦截器
    private HealthCheckInterceptorChain() {
        super(AbstractHealthCheckInterceptor.class);
    }
    
    public static HealthCheckInterceptorChain getInstance() {
        return INSTANCE;
    }
}
