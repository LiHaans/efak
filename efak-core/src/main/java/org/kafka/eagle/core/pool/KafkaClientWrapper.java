/**
 * KafkaClientWrapper.java
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

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.admin.AdminClient;
import org.kafka.eagle.dto.cluster.KafkaClientInfo;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * <p>
 * Kafka 客户端包装类
 * 封装 AdminClient 以及连接的元数据信息，用于连接池管理
 * </p>
 * @author Mr.SmartLoli
 * @since 2025/11/16
 * @version 5.0.0
 */
@Slf4j
@Data
public class KafkaClientWrapper {

    /**
     * Kafka AdminClient 实例
     */
    private final AdminClient adminClient;

    /**
     * 客户端配置信息
     */
    private final KafkaClientInfo clientInfo;

    /**
     * 连接创建时间戳
     */
    private final long createTime;

    /**
     * 最后使用时间戳
     */
    private final AtomicLong lastUsedTime;

    /**
     * 是否正在使用中
     */
    private final AtomicBoolean inUse;

    /**
     * 健康状态
     */
    private volatile boolean healthy;

    /**
     * 使用次数统计
     */
    private final AtomicLong usageCount;

    /**
     * 构造函数
     */
    public KafkaClientWrapper(AdminClient adminClient, KafkaClientInfo clientInfo) {
        this.adminClient = adminClient;
        this.clientInfo = clientInfo;
        this.createTime = System.currentTimeMillis();
        this.lastUsedTime = new AtomicLong(System.currentTimeMillis());
        this.inUse = new AtomicBoolean(false);
        this.healthy = true;
        this.usageCount = new AtomicLong(0);
    }

    /**
     * 标记为使用中
     */
    public void markInUse() {
        this.inUse.set(true);
        this.lastUsedTime.set(System.currentTimeMillis());
        this.usageCount.incrementAndGet();
    }

    /**
     * 标记为空闲
     */
    public void markIdle() {
        this.inUse.set(false);
        this.lastUsedTime.set(System.currentTimeMillis());
    }

    /**
     * 检查连接是否空闲
     */
    public boolean isIdle() {
        return !inUse.get();
    }

    /**
     * 检查连接是否超过最大空闲时间
     */
    public boolean isIdleTimeout(long maxIdleTimeMs) {
        return isIdle() && (System.currentTimeMillis() - lastUsedTime.get()) > maxIdleTimeMs;
    }

    /**
     * 检查连接是否超过最大存活时间
     */
    public boolean isLifetimeExpired(long maxLifetimeMs) {
        return (System.currentTimeMillis() - createTime) > maxLifetimeMs;
    }

    /**
     * 验证连接健康状态
     * 通过调用 describeCluster 检查连接是否可用
     */
    public boolean validateHealth(long timeoutMs) {
        try {
            // 使用 describeCluster 快速检查连接
            adminClient.describeCluster()
                    .clusterId()
                    .get(timeoutMs, TimeUnit.MILLISECONDS);
            this.healthy = true;
            return true;
        } catch (Exception e) {
            log.warn("集群 '{}' 的客户端连接健康检查失败: {}", 
                    clientInfo.getClusterId(), e.getMessage());
            this.healthy = false;
            return false;
        }
    }

    /**
     * 关闭客户端连接
     */
    public void close() {
        try {
            if (adminClient != null) {
                adminClient.close();
                log.debug("关闭集群 '{}' 的客户端连接", clientInfo.getClusterId());
            }
        } catch (Exception e) {
            log.error("关闭集群 '{}' 的客户端连接失败: ", clientInfo.getClusterId(), e);
        }
    }

    /**
     * 获取连接存活时间（毫秒）
     */
    public long getAliveTimeMs() {
        return System.currentTimeMillis() - createTime;
    }

    /**
     * 获取连接空闲时间（毫秒）
     */
    public long getIdleTimeMs() {
        return System.currentTimeMillis() - lastUsedTime.get();
    }

    /**
     * 获取集群ID
     */
    public String getClusterId() {
        return clientInfo.getClusterId();
    }

    @Override
    public String toString() {
        return String.format("KafkaClientWrapper{clusterId='%s', inUse=%s, healthy=%s, usageCount=%d, aliveTime=%dms, idleTime=%dms}",
                clientInfo.getClusterId(), inUse.get(), healthy, usageCount.get(), 
                getAliveTimeMs(), getIdleTimeMs());
    }
}
