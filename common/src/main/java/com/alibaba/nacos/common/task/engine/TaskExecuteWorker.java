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

package com.alibaba.nacos.common.task.engine;

import com.alibaba.nacos.api.exception.NacosException;
import com.alibaba.nacos.common.lifecycle.Closeable;
import com.alibaba.nacos.common.task.AbstractExecuteTask;
import com.alibaba.nacos.common.task.NacosTask;
import com.alibaba.nacos.common.task.NacosTaskProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Nacos任务执行者
 * 每个执行者在创建的时候会同时启动一个线程InnerWorker
 * 持续从内部队列中获取需要处理的任务
 * Nacos execute task execute worker.
 *
 * @author xiweng.yy
 */
public final class TaskExecuteWorker implements NacosTaskProcessor, Closeable {
    
    /**
     * Max task queue size 32768.
     * 队列最大数量为32768
     */
    private static final int QUEUE_CAPACITY = 1 << 15;
    
    private final Logger log;

    /**
     * 当前执行者线程的名称
     */
    private final String name;

    /**
     * 负责处理的线程队列
     */
    private final BlockingQueue<Runnable> queue;

    /**
     * 工作状态
     */
    private final AtomicBoolean closed;
    
    public TaskExecuteWorker(final String name, final int mod, final int total) {
        this(name, mod, total, null);
    }
    
    public TaskExecuteWorker(final String name, final int mod, final int total, final Logger logger) {
        /**
         * 执行线程的名称，以DistroExecuteTaskExecuteEngine举例：
         * DistroExecuteTaskExecuteEngine_0%8
         * DistroExecuteTaskExecuteEngine_1%8
         * DistroExecuteTaskExecuteEngine_2%8
         * DistroExecuteTaskExecuteEngine_3%8
         * DistroExecuteTaskExecuteEngine_4%8
         * DistroExecuteTaskExecuteEngine_5%8
         * DistroExecuteTaskExecuteEngine_6%8
         * DistroExecuteTaskExecuteEngine_7%8
         */
        this.name = name + "_" + mod + "%" + total;
        this.queue = new ArrayBlockingQueue<Runnable>(QUEUE_CAPACITY);
        this.closed = new AtomicBoolean(false);
        this.log = null == logger ? LoggerFactory.getLogger(TaskExecuteWorker.class) : logger;
        // 启动一个新线程来消费队列
        new InnerWorker(name).start();
    }
    
    public String getName() {
        return name;
    }
    
    @Override
    public boolean process(NacosTask task) {
        if (task instanceof AbstractExecuteTask) {
            putTask((Runnable) task);
        }
        return true;
    }
    
    private void putTask(Runnable task) {
        try {
            queue.put(task);
        } catch (InterruptedException ire) {
            log.error(ire.toString(), ire);
        }
    }
    
    public int pendingTaskCount() {
        return queue.size();
    }
    
    /**
     * Worker status.
     */
    public String status() {
        return name + ", pending tasks: " + pendingTaskCount();
    }
    
    @Override
    public void shutdown() throws NacosException {
        queue.clear();
        closed.compareAndSet(false, true);
    }
    
    /**
     * Inner execute worker.
     */
    private class InnerWorker extends Thread {
        
        InnerWorker(String name) {
            setDaemon(false);
            setName(name);
        }
        
        @Override
        public void run() {
            // 若线程还未中断，则持续执行
            while (!closed.get()) {
                try {
                    // 从队列获取任务
                    Runnable task = queue.take();
                    long begin = System.currentTimeMillis();
                    // 在当前InnerWorker线程内执行任务
                    task.run();
                    long duration = System.currentTimeMillis() - begin;
                    // 若任务执行时间超过1秒，则警告
                    if (duration > 1000L) {
                        log.warn("task {} takes {}ms", task, duration);
                    }
                } catch (Throwable e) {
                    log.error("[TASK-FAILED] " + e.toString(), e);
                }
            }
        }
    }
}
