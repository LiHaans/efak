/**
 * KafkaClientPoolConfig.java
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

/**
 * <p>
 * Kafka 客户端连接池配置类
 * 定义连接池的各项参数，包括连接数限制、超时时间、健康检查等
 * </p>
 * @author Mr.SmartLoli
 * @since 2025/11/16
 * @version 5.0.0
 */
@Data
public class KafkaClientPoolConfig {

    /**
     * 每个集群的最大连接数
     * 默认值：10
     */
    private int maxConnectionsPerCluster = 10;

    /**
     * 每个集群的最小空闲连接数
     * 默认值：2
     */
    private int minIdleConnectionsPerCluster = 2;

    /**
     * 连接最大空闲时间（毫秒）
     * 超过此时间未使用的连接将被清理
     * 默认值：5分钟
     */
    private long maxIdleTimeMs = 5 * 60 * 1000;

    /**
     * 连接最大存活时间（毫秒）
     * 无论是否使用，超过此时间的连接将被清理
     * 默认值：30分钟
     */
    private long maxLifetimeMs = 30 * 60 * 1000;

    /**
     * 健康检查间隔（毫秒）
     * 定期清理失效连接的周期
     * 默认值：1分钟
     */
    private long healthCheckIntervalMs = 60 * 1000;

    /**
     * 获取连接的超时时间（毫秒）
     * 当连接池已满时，等待的最长时间
     * 默认值：30秒
     */
    private long borrowTimeoutMs = 30 * 1000;

    /**
     * 连接健康检查超时时间（毫秒）
     * 检查连接是否可用的超时时间
     * 默认值：5秒
     */
    private long validationTimeoutMs = 5 * 1000;

    /**
     * 是否在启动时预热连接池
     * 如果为 true，将在应用启动时为每个集群创建最小数量的连接
     * 默认值：true
     */
    private boolean warmupOnStartup = true;

    /**
     * 是否启用连接池统计信息
     * 默认值：true
     */
    private boolean enableStatistics = true;

    /**
     * 创建默认配置
     */
    public static KafkaClientPoolConfig defaultConfig() {
        return new KafkaClientPoolConfig();
    }

    /**
     * 创建生产环境配置
     */
    public static KafkaClientPoolConfig productionConfig() {
        KafkaClientPoolConfig config = new KafkaClientPoolConfig();
        config.setMaxConnectionsPerCluster(20);
        config.setMinIdleConnectionsPerCluster(5);
        config.setMaxIdleTimeMs(10 * 60 * 1000); // 10分钟
        config.setHealthCheckIntervalMs(30 * 1000); // 30秒
        return config;
    }

    /**
     * 创建开发环境配置
     */
    public static KafkaClientPoolConfig developmentConfig() {
        KafkaClientPoolConfig config = new KafkaClientPoolConfig();
        config.setMaxConnectionsPerCluster(5);
        config.setMinIdleConnectionsPerCluster(1);
        config.setMaxIdleTimeMs(3 * 60 * 1000); // 3分钟
        config.setWarmupOnStartup(false);
        return config;
    }
}
