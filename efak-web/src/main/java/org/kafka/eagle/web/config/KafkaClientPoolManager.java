package org.kafka.eagle.web.config;

import lombok.extern.slf4j.Slf4j;
import org.kafka.eagle.core.api.KafkaStoragePlugin;
import org.kafka.eagle.core.pool.KafkaClientPool;
import org.kafka.eagle.core.pool.KafkaClientPoolConfig;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.annotation.PreDestroy;

/**
 * Kafka 客户端连接池管理配置类
 * 将 KafkaStoragePlugin 和连接池作为 Spring Bean 管理
 *
 * @author Mr.SmartLoli
 * @since 2025/11/16
 * @version 5.0.0
 */
@Slf4j
@Configuration
public class KafkaClientPoolManager {

    private KafkaStoragePlugin storagePlugin;

    /**
     * 创建 KafkaStoragePlugin Bean
     */
    @Bean
    public KafkaStoragePlugin kafkaStoragePlugin() {
        log.info("初始化 KafkaStoragePlugin Bean");
        // 使用生产环境配置
        KafkaClientPoolConfig config = KafkaClientPoolConfig.productionConfig();
        this.storagePlugin = new KafkaStoragePlugin(config);
        return storagePlugin;
    }

    /**
     * 获取连接池实例
     */
    @Bean
    public KafkaClientPool kafkaClientPool(KafkaStoragePlugin storagePlugin) {
        return storagePlugin.getClientPool();
    }

    /**
     * 应用关闭时清理资源
     */
    @PreDestroy
    public void destroy() {
        log.info("开始关闭 Kafka 客户端连接池");
        if (storagePlugin != null) {
            storagePlugin.shutdown();
        }
    }
}
