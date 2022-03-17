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

package com.alibaba.nacos.naming.interceptor;

/**
 * 定义了拦截器链对象应该具有的基本行为：添加拦截器、执行拦截器
 * Nacos Naming模块的拦截器链接口，拦截器链用于存储并管理多个拦截器
 * Nacos naming interceptor chain.
 *
 * @author xiweng.yy
 */
public interface NacosNamingInterceptorChain<T extends Interceptable> {
    
    /**
     * Add interceptor.
     * 添加指定类型的拦截器对象
     *
     * @param interceptor interceptor
     */
    void addInterceptor(NacosNamingInterceptor<T> interceptor);
    
    /**
     * Do intercept by added interceptors.
     * 执行拦截的业务操作
     *
     * @param object be interceptor object
     */
    void doInterceptor(T object);
}
