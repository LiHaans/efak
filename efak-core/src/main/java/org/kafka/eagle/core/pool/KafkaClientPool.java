/**
 * KafkaClientPool.java
 * <p>
 * Copyright 2025 smartloli
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.kafka.eagle.core.pool;

import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.admin.AdminClient;
import org.kafka.eagle.core.api.KafkaStoragePlugin;
import org.kafka.eagle.dto.cluster.KafkaClientInfo;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * <p>
 * Kafka 客户端连接池
 * 管理多个 Kafka 集群的客户端连接，支持连接复用、健康检查、自动清理等功能
 * </p>
 * @author Mr.SmartLoli
 * @since 2025/11/16
 * @version 5.0.0
 */
@Slf4j
public class KafkaClientPool {

    /**
     * 连接池配置
     */
    private final KafkaClientPoolConfig config;

    /**
     * Kafka 存储插件，用于创建客户端配置
     */
    private final KafkaStoragePlugin storagePlugin;

    /**
     * 连接池：clusterId -> 连接队列
     */
    private final ConcurrentHashMap<String, ConcurrentLinkedQueue<KafkaClientWrapper>> pools;

    /**
     * 活跃连接计数器：clusterId -> 连接数
     */
    private final ConcurrentHashMap<String, AtomicLong> activeConnections;

    /**
     * 定时清理任务调度器
     */
    private final ScheduledExecutorService scheduler;

    /**
     * 连接池是否已关闭
     */
    private final AtomicBoolean closed;

    /**
     * 连接池统计信息
     */
    private final PoolStatistics statistics;

    /**
     * 构造函数
     */
    public KafkaClientPool(KafkaClientPoolConfig config, KafkaStoragePlugin storagePlugin) {
        this.config = config;
        this.storagePlugin = storagePlugin;
        this.pools = new ConcurrentHashMap<>();
        this.activeConnections = new ConcurrentHashMap<>();
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "KafkaClientPool-Cleaner");
            thread.setDaemon(true);
            return thread;
        });
        this.closed = new AtomicBoolean(false);
        this.statistics = new PoolStatistics();

        // 启动定时清理任务
        startHealthCheckTask();

        log.info("Kafka 客户端连接池已初始化，配置: {}", config);
    }

    /**
     * 从连接池获取客户端
     */
    public KafkaClientWrapper borrowClient(KafkaClientInfo clientInfo) throws Exception {
        if (closed.get()) {
            throw new IllegalStateException("连接池已关闭");
        }

        String clusterId = clientInfo.getClusterId();
        ConcurrentLinkedQueue<KafkaClientWrapper> pool = pools.computeIfAbsent(
                clusterId, k -> new ConcurrentLinkedQueue<>());
        activeConnections.putIfAbsent(clusterId, new AtomicLong(0));

        long startTime = System.currentTimeMillis();
        KafkaClientWrapper wrapper = null;

        // 1. 尝试从池中获取空闲连接
        while ((wrapper = pool.poll()) != null) {
            // 检查连接健康状态
            if (wrapper.isHealthy() && wrapper.validateHealth(config.getValidationTimeoutMs())) {
                wrapper.markInUse();
                statistics.incrementBorrowCount();
                statistics.incrementHitCount();
                log.debug("从连接池获取客户端: {}", wrapper);
                return wrapper;
            } else {
                // 连接不健康，关闭并移除
                log.warn("连接不健康，已移除: {}", wrapper);
                closeWrapper(wrapper);
                activeConnections.get(clusterId).decrementAndGet();
                statistics.incrementRemovedCount();
            }
        }

        // 2. 池中无可用连接，检查是否可以创建新连接
        AtomicLong count = activeConnections.get(clusterId);
        if (count.get() >= config.getMaxConnectionsPerCluster()) {
            // 达到最大连接数，等待可用连接
            long waitTime = config.getBorrowTimeoutMs();
            long elapsed = 0;
            while (elapsed < waitTime) {
                wrapper = pool.poll();
                if (wrapper != null && wrapper.validateHealth(config.getValidationTimeoutMs())) {
                    wrapper.markInUse();
                    statistics.incrementBorrowCount();
                    statistics.incrementHitCount();
                    return wrapper;
                }
                Thread.sleep(100);
                elapsed = System.currentTimeMillis() - startTime;
            }
            throw new TimeoutException(String.format(
                    "获取集群 '%s' 的客户端超时，已达到最大连接数 %d",
                    clusterId, config.getMaxConnectionsPerCluster()));
        }

        // 3. 创建新连接
        wrapper = createNewClient(clientInfo);
        wrapper.markInUse();
        count.incrementAndGet();
        statistics.incrementBorrowCount();
        statistics.incrementMissCount();
        statistics.incrementCreatedCount();
        log.info("创建新的客户端连接: {}", wrapper);
        return wrapper;
    }

    /**
     * 归还客户端到连接池
     */
    public void returnClient(KafkaClientWrapper wrapper) {
        if (wrapper == null) {
            return;
        }

        if (closed.get()) {
            closeWrapper(wrapper);
            return;
        }

        String clusterId = wrapper.getClusterId();
        wrapper.markIdle();

        // 检查连接是否超过最大存活时间
        if (wrapper.isLifetimeExpired(config.getMaxLifetimeMs())) {
            log.info("连接已超过最大存活时间，关闭: {}", wrapper);
            closeWrapper(wrapper);
            activeConnections.get(clusterId).decrementAndGet();
            statistics.incrementRemovedCount();
            return;
        }

        ConcurrentLinkedQueue<KafkaClientWrapper> pool = pools.get(clusterId);
        if (pool != null) {
            pool.offer(wrapper);
            statistics.incrementReturnCount();
            log.debug("归还客户端到连接池: {}", wrapper);
        } else {
            closeWrapper(wrapper);
            activeConnections.get(clusterId).decrementAndGet();
        }
    }

    /**
     * 创建新的客户端连接
     */
    private KafkaClientWrapper createNewClient(KafkaClientInfo clientInfo) {
        AdminClient adminClient = AdminClient.create(storagePlugin.buildAdminClientProps(clientInfo));
        return new KafkaClientWrapper(adminClient, clientInfo);
    }

    /**
     * 关闭客户端包装器
     */
    private void closeWrapper(KafkaClientWrapper wrapper) {
        try {
            wrapper.close();
        } catch (Exception e) {
            log.error("关闭客户端连接失败: {}", wrapper, e);
        }
    }

    /**
     * 刷新指定集群的连接池
     * 在集群配置变更时调用
     */
    public void refreshCluster(String clusterId) {
        log.info("刷新集群 '{}' 的连接池", clusterId);
        ConcurrentLinkedQueue<KafkaClientWrapper> pool = pools.remove(clusterId);
        if (pool != null) {
            // 关闭所有现有连接
            KafkaClientWrapper wrapper;
            while ((wrapper = pool.poll()) != null) {
                closeWrapper(wrapper);
                statistics.incrementRemovedCount();
            }
        }
        activeConnections.remove(clusterId);
        log.info("集群 '{}' 的连接池已刷新", clusterId);
    }

    /**
     * 移除指定集群的连接池
     * 在集群删除时调用
     */
    public void removeCluster(String clusterId) {
        log.info("移除集群 '{}' 的连接池", clusterId);
        refreshCluster(clusterId);
    }

    /**
     * 预热连接池
     * 为指定集群创建最小数量的连接
     */
    public void warmup(KafkaClientInfo clientInfo) {
        String clusterId = clientInfo.getClusterId();
        log.info("预热集群 '{}' 的连接池，目标连接数: {}", 
                clusterId, config.getMinIdleConnectionsPerCluster());

        ConcurrentLinkedQueue<KafkaClientWrapper> pool = pools.computeIfAbsent(
                clusterId, k -> new ConcurrentLinkedQueue<>());
        activeConnections.putIfAbsent(clusterId, new AtomicLong(0));

        for (int i = 0; i < config.getMinIdleConnectionsPerCluster(); i++) {
            try {
                KafkaClientWrapper wrapper = createNewClient(clientInfo);
                pool.offer(wrapper);
                activeConnections.get(clusterId).incrementAndGet();
                statistics.incrementCreatedCount();
            } catch (Exception e) {
                log.error("预热集群 '{}' 时创建连接失败", clusterId, e);
            }
        }
    }

    /**
     * 启动健康检查和清理任务
     */
    private void startHealthCheckTask() {
        scheduler.scheduleAtFixedRate(() -> {
            try {
                performHealthCheck();
            } catch (Exception e) {
                log.error("健康检查任务执行失败", e);
            }
        }, config.getHealthCheckIntervalMs(), config.getHealthCheckIntervalMs(), TimeUnit.MILLISECONDS);

        log.info("健康检查任务已启动，间隔: {}ms", config.getHealthCheckIntervalMs());
    }

    /**
     * 执行健康检查和清理
     */
    private void performHealthCheck() {
        log.debug("开始执行连接池健康检查");
        int totalCleaned = 0;

        for (Map.Entry<String, ConcurrentLinkedQueue<KafkaClientWrapper>> entry : pools.entrySet()) {
            String clusterId = entry.getKey();
            ConcurrentLinkedQueue<KafkaClientWrapper> pool = entry.getValue();
            AtomicLong count = activeConnections.get(clusterId);

            List<KafkaClientWrapper> toRemove = new ArrayList<>();
            List<KafkaClientWrapper> toKeep = new ArrayList<>();

            // 检查每个连接
            KafkaClientWrapper wrapper;
            while ((wrapper = pool.poll()) != null) {
                if (shouldRemoveConnection(wrapper, pool.size() + toKeep.size(), count.get())) {
                    toRemove.add(wrapper);
                } else {
                    toKeep.add(wrapper);
                }
            }

            // 关闭需要移除的连接
            for (KafkaClientWrapper w : toRemove) {
                closeWrapper(w);
                count.decrementAndGet();
                totalCleaned++;
                statistics.incrementRemovedCount();
            }

            // 保留健康连接
            pool.addAll(toKeep);
        }

        if (totalCleaned > 0) {
            log.info("健康检查完成，清理了 {} 个连接", totalCleaned);
        }

        if (config.isEnableStatistics()) {
            log.info("连接池统计: {}", statistics);
        }
    }

    /**
     * 判断连接是否应该被移除
     */
    private boolean shouldRemoveConnection(KafkaClientWrapper wrapper, int currentPoolSize, long totalConnections) {
        // 1. 连接不健康
        if (!wrapper.isHealthy()) {
            return true;
        }

        // 2. 连接正在使用中，不移除
        if (!wrapper.isIdle()) {
            return false;
        }

        // 3. 连接超过最大存活时间
        if (wrapper.isLifetimeExpired(config.getMaxLifetimeMs())) {
            log.debug("连接超过最大存活时间: {}", wrapper);
            return true;
        }

        // 4. 连接超过最大空闲时间，且连接数超过最小值
        if (wrapper.isIdleTimeout(config.getMaxIdleTimeMs()) 
                && totalConnections > config.getMinIdleConnectionsPerCluster()) {
            log.debug("连接超过最大空闲时间: {}", wrapper);
            return true;
        }

        // 5. 验证连接健康状态
        if (!wrapper.validateHealth(config.getValidationTimeoutMs())) {
            log.warn("连接健康检查失败: {}", wrapper);
            return true;
        }

        return false;
    }

    /**
     * 关闭连接池
     */
    public void shutdown() {
        if (closed.compareAndSet(false, true)) {
            log.info("开始关闭 Kafka 客户端连接池");

            // 停止定时任务
            scheduler.shutdown();
            try {
                if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                    scheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                scheduler.shutdownNow();
                Thread.currentThread().interrupt();
            }

            // 关闭所有连接
            int totalClosed = 0;
            for (Map.Entry<String, ConcurrentLinkedQueue<KafkaClientWrapper>> entry : pools.entrySet()) {
                ConcurrentLinkedQueue<KafkaClientWrapper> pool = entry.getValue();
                KafkaClientWrapper wrapper;
                while ((wrapper = pool.poll()) != null) {
                    closeWrapper(wrapper);
                    totalClosed++;
                }
            }

            pools.clear();
            activeConnections.clear();

            log.info("Kafka 客户端连接池已关闭，共关闭 {} 个连接", totalClosed);
            log.info("连接池最终统计: {}", statistics);
        }
    }

    /**
     * 获取连接池统计信息
     */
    public PoolStatistics getStatistics() {
        return statistics;
    }

    /**
     * 获取指定集群的连接数
     */
    public int getConnectionCount(String clusterId) {
        AtomicLong count = activeConnections.get(clusterId);
        return count != null ? (int) count.get() : 0;
    }

    /**
     * 获取指定集群的空闲连接数
     */
    public int getIdleConnectionCount(String clusterId) {
        ConcurrentLinkedQueue<KafkaClientWrapper> pool = pools.get(clusterId);
        return pool != null ? pool.size() : 0;
    }

    /**
     * 连接池统计信息
     */
    public static class PoolStatistics {
        private final AtomicLong borrowCount = new AtomicLong(0);
        private final AtomicLong returnCount = new AtomicLong(0);
        private final AtomicLong hitCount = new AtomicLong(0);
        private final AtomicLong missCount = new AtomicLong(0);
        private final AtomicLong createdCount = new AtomicLong(0);
        private final AtomicLong removedCount = new AtomicLong(0);

        public void incrementBorrowCount() { borrowCount.incrementAndGet(); }
        public void incrementReturnCount() { returnCount.incrementAndGet(); }
        public void incrementHitCount() { hitCount.incrementAndGet(); }
        public void incrementMissCount() { missCount.incrementAndGet(); }
        public void incrementCreatedCount() { createdCount.incrementAndGet(); }
        public void incrementRemovedCount() { removedCount.incrementAndGet(); }

        public double getHitRate() {
            long total = borrowCount.get();
            return total > 0 ? (double) hitCount.get() / total * 100 : 0;
        }

        @Override
        public String toString() {
            return String.format(
                    "PoolStatistics{borrow=%d, return=%d, hit=%d, miss=%d, hitRate=%.2f%%, created=%d, removed=%d}",
                    borrowCount.get(), returnCount.get(), hitCount.get(), missCount.get(),
                    getHitRate(), createdCount.get(), removedCount.get());
        }
    }
}
