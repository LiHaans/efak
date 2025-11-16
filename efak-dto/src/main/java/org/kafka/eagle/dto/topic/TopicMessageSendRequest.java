package org.kafka.eagle.dto.topic;

import lombok.Data;

/**
 * <p>
 * Topic消息发送请求DTO
 * </p>
 * @author Mr.SmartLoli
 * @since 2025/11/16
 * @version 5.0.0
 */
@Data
public class TopicMessageSendRequest {
    /**
     * 集群ID
     */
    private String clusterId;
    
    /**
     * Topic名称
     */
    private String topicName;
    
    /**
     * 分区号（可选，如果不指定则由Kafka自动分配）
     */
    private Integer partition;
    
    /**
     * 消息Key（可选）
     */
    private String key;
    
    /**
     * 消息内容
     */
    private String message;
    
    /**
     * 消息格式：json 或 avro
     */
    private String format;
    
    /**
     * Avro Schema（format为avro时需要）
     */
    private String schema;
    
    /**
     * 消息数量（批量发送）
     */
    private Integer count;
}
