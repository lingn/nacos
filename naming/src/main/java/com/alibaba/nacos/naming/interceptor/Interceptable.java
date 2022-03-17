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
 * 定义了对拦截操作相关的执行方法
 * passIntercept()在未被拦截的时候需要执行
 * afterIntercept()在被拦截之后需要执行。
 * 被拦截对象的业务逻辑需要由拦截器负责调度
 * Interceptable Interface.
 *
 * @author xiweng.yy
 */
public interface Interceptable {
    
    /**
     * If no {@link NacosNamingInterceptor} intercept this object, this method will be called to execute.
     */
    void passIntercept();
    
    /**
     * If one {@link NacosNamingInterceptor} intercept this object, this method will be called.
     */
    void afterIntercept();
}
