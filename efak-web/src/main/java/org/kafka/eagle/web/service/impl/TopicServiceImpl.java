package org.kafka.eagle.web.service.impl;

import lombok.extern.slf4j.Slf4j;
import org.kafka.eagle.core.api.KafkaSchemaFactory;
import org.kafka.eagle.core.api.KafkaStoragePlugin;
import org.kafka.eagle.core.constant.JmxMetricsConst;
import org.kafka.eagle.core.constant.MBeanMetricsConst;
import org.kafka.eagle.dto.broker.BrokerInfo;
import org.kafka.eagle.dto.cluster.KafkaClientInfo;
import org.kafka.eagle.dto.jmx.JMXInitializeInfo;
import org.kafka.eagle.dto.topic.*;
import org.kafka.eagle.dto.cluster.KafkaClusterInfo;
import org.kafka.eagle.web.mapper.BrokerMapper;
import org.kafka.eagle.web.mapper.ClusterMapper;
import org.kafka.eagle.web.mapper.ConsumerGroupTopicMapper;
import org.kafka.eagle.web.mapper.TopicInstantMetricsMapper;
import org.kafka.eagle.web.mapper.TopicMapper;
import org.kafka.eagle.web.mapper.TopicMetricsMapper;
import org.kafka.eagle.web.util.AvroMessageSerializer;
import org.kafka.eagle.web.service.TopicService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import javax.management.MBeanServerConnection;
import javax.management.remote.JMXConnector;
import javax.management.remote.JMXConnectorFactory;
import javax.management.remote.JMXServiceURL;
import java.math.BigDecimal;
import java.util.*;

/**
 * <p>
 * Topic 服务实现类
 * </p>
 * @author Mr.SmartLoli
 * @since 2025/09/27 03:32:22
 * @version 5.0.0
 */
@Slf4j
@Service
public class TopicServiceImpl implements TopicService {

    @Autowired
    private TopicMapper topicMapper;

    @Autowired
    private BrokerMapper brokerMapper;

    @Autowired
    private TopicInstantMetricsMapper topicInstantMetricsMapper;

    @Autowired
    private TopicMetricsMapper topicMetricsMapper;

    @Autowired
    private ConsumerGroupTopicMapper consumerGroupTopicMapper;

    @Autowired
    private ClusterMapper clusterMapper;

    @Override
    public TopicPageResponse getTopicPage(TopicQueryRequest request) {
        if (request == null) {
            return new TopicPageResponse(List.of(), 0L, 1, 10);
        }
        Integer page = request.getPage() != null ? request.getPage() : 1;
        Integer pageSize = request.getPageSize() != null ? request.getPageSize() : 10;
        // 查询总数
        Long total = topicMapper.countTopic(request);
        if (total == null || total == 0) {
            return new TopicPageResponse(List.of(), 0L, page, pageSize);
        }
        int offset = (page - 1) * pageSize;
        List<TopicInfo> list = topicMapper.selectTopicPage(request, offset, pageSize);
        return new TopicPageResponse(list, total, page, pageSize);
    }

    @Override
    public TopicInfo getTopicById(Long id) {
        if (id == null) {
            return null;
        }
        return topicMapper.selectTopicById(id);
    }

    @Override
    public TopicInfo getTopicByNameAndCluster(String topicName, String clusterId) {
        if (!StringUtils.hasText(topicName)) {
            return null;
        }
        return topicMapper.selectTopicByNameAndCluster(topicName, clusterId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean createTopic(TopicCreateRequest request) {
        try {
            // 参数校验
            if (request == null || !StringUtils.hasText(request.getTopicName())) {
                log.error("创建Topic失败：参数不能为空");
                return false;
            }

            // 记录集群ID信息
            String clusterId = request.getClusterId();

            if (clusterId == null || clusterId.trim().isEmpty()) {
                log.warn("创建Topic时集群ID为空，可能影响集群关联，主题: {}", request.getTopicName());
            }

            // 检查Topic是否已存在（在指定集群中）
            TopicInfo existingTopic = topicMapper.selectTopicByNameAndCluster(request.getTopicName(), clusterId);
            if (existingTopic != null) {
                log.error("创建Topic失败：Topic [{}] 在集群 [{}] 中已存在", request.getTopicName(), clusterId);
                return false;
            }

            // 1. 先调用KafkaSchemaFactory创建Kafka中的Topic
            KafkaSchemaFactory ksf = new KafkaSchemaFactory(new KafkaStoragePlugin());

            // 创建KafkaClientInfo
            KafkaClientInfo kafkaClientInfo = new KafkaClientInfo();
            kafkaClientInfo.setClusterId(clusterId);

            // 获取集群的broker信息用于连接
            List<BrokerInfo> brokerInfos = List.of();
            if (StringUtils.hasText(clusterId)) {
                brokerInfos = brokerMapper.getBrokersByClusterId(clusterId);
            } else {
                log.warn("集群ID为空，将使用默认连接配置");
            }

            if (!brokerInfos.isEmpty()) {
                // 使用第一个broker的信息作为连接信息
                BrokerInfo firstBroker = brokerInfos.get(0);
                kafkaClientInfo.setBrokerServer(firstBroker.getHostIp() + ":" + firstBroker.getPort());
                log.info("使用broker连接信息: {}:{}", firstBroker.getHostIp(), firstBroker.getPort());
            } else {
                log.error("集群 {} 没有可用的broker信息，请检查配置", clusterId);
                return false;
            }

            // 创建NewTopicInfo
            NewTopicInfo newTopicInfo = new NewTopicInfo();
            newTopicInfo.setTopicName(request.getTopicName());
            newTopicInfo.setPartitions(request.getPartitions());
            newTopicInfo.setReplication(request.getReplicas());

            // 设置保留时间（如果有）
            if (StringUtils.hasText(request.getRetentionTime())) {
                try {
                    long retainMs = Long.parseLong(request.getRetentionTime());
                    newTopicInfo.setRetainMs(retainMs);
                    log.info("设置Topic保留时间: {} ms", retainMs);
                } catch (NumberFormatException e) {
                    log.warn("保留时间格式错误，使用默认值: {}", request.getRetentionTime());
                }
            }

            // 调用KafkaSchemaFactory创建Topic
            boolean kafkaCreateSuccess = ksf.createTopicIfNotExists(kafkaClientInfo, newTopicInfo);
            if (!kafkaCreateSuccess) {
                log.error("创建Topic失败：Kafka创建操作失败，主题: {}", request.getTopicName());
                return false;
            }

            // 2. Kafka创建成功后，写入数据库
            TopicInfo topicInfo = new TopicInfo();
            topicInfo.setTopicName(request.getTopicName());
            topicInfo.setClusterId(clusterId); // 确保设置集群ID
            topicInfo.setPartitions(request.getPartitions());
            topicInfo.setReplicas(request.getReplicas());
            topicInfo.setRetentionTime(request.getRetentionTime());
            topicInfo.setBrokerSpread("100"); // 默认值
            topicInfo.setBrokerSkewed("0"); // 默认值
            topicInfo.setLeaderSkewed("0"); // 默认值
            topicInfo.setIcon(request.getIcon()); // 设置图标
            topicInfo.setCreateBy(request.getCreateBy());
            topicInfo.setUpdateBy(request.getCreateBy());

            // 确认集群ID已正确设置
            if (topicInfo.getClusterId() != null && !topicInfo.getClusterId().trim().isEmpty()) {
                log.info("✓ 集群ID已正确设置: {}", topicInfo.getClusterId());
            } else {
                log.warn("⚠ 警告: 集群ID为空，主题将无法关联到特定集群");
            }

            // 插入数据库
            int result = topicMapper.insertTopic(topicInfo);

            if (result > 0) {
                return true;
            } else {
                log.error("Topic [{}] 创建失败：数据库插入失败", request.getTopicName());
                // 注意：此时Kafka中的主题已经创建，但数据库插入失败
                // 可以考虑记录这种不一致状态，或者通过定时任务同步
                return false;
            }

        } catch (Exception e) {
            log.error("创建Topic失败：{}", e.getMessage(), e);
            return false;
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean deleteTopic(String topicName, String clusterId) {
        try {
            if (!StringUtils.hasText(topicName)) {
                log.error("删除Topic失败：topic名称不能为空");
                return false;
            }

            // 先从数据库查询Topic信息
            TopicInfo topicInfo = topicMapper.selectTopicByNameAndCluster(topicName, clusterId);
            if (topicInfo == null || topicInfo.getId() == null) {
                log.error("删除Topic失败：Topic [{}] 在集群 [{}] 中不存在", topicName, clusterId);
                return false;
            }

            // 1. 先调用KafkaSchemaFactory删除Kafka中的主题
            KafkaSchemaFactory ksf = new KafkaSchemaFactory(new KafkaStoragePlugin());

            // 创建KafkaClientInfo
            KafkaClientInfo kafkaClientInfo = new KafkaClientInfo();
            kafkaClientInfo.setClusterId(clusterId);

            // 获取集群的broker信息用于连接
            List<BrokerInfo> brokerInfos = List.of();
            if (StringUtils.hasText(clusterId)) {
                brokerInfos = brokerMapper.getBrokersByClusterId(clusterId);
            }

            if (!brokerInfos.isEmpty()) {
                // 使用第一个broker的信息作为连接信息
                BrokerInfo firstBroker = brokerInfos.get(0);
                kafkaClientInfo.setBrokerServer(firstBroker.getHostIp() + ":" + firstBroker.getPort());
            } else {
                log.error("删除Topic失败：集群 [{}] 中没有Broker信息", clusterId);
                return false;
            }

            // 调用KafkaSchemaFactory删除Topic
            boolean kafkaDeleteSuccess = ksf.removeTopic(kafkaClientInfo, topicName);
            if (!kafkaDeleteSuccess) {
                log.error("删除Topic失败：Kafka删除失败，名称 = {}", topicName);
                return false;
            }

            // 2. Kafka删除成功后，删除数据库中的所有相关记录（原子操作）
            try {
                // 删除 ke_topic_instant_metrics 表中的相关记录
                int instantMetricsDeleted = topicInstantMetricsMapper.deleteByTopicNameAndClusterId(clusterId, topicName);

                // 删除 ke_topics_metrics 表中的相关记录
                int topicMetricsDeleted = topicMetricsMapper.deleteByTopicNameAndClusterId(clusterId, topicName);

                // 最后删除 ke_topic_info 表中的主记录
                int result = topicMapper.deleteTopic(topicInfo.getId());

                if (result > 0) {
                    return true;
                } else {
                    log.error("删除Topic失败：ke_topic_info表删除失败，名称 = {}", topicName);
                    // 注意：此时Kafka中的主题已经被删除，但数据库删除失败，需要手动处理
                    return false;
                }
            } catch (Exception e) {
                log.error("删除Topic的数据库记录失败：{}", e.getMessage(), e);
                // 注意：此时Kafka中的主题已经被删除，但数据库删除失败，需要手动处理
                return false;
            }

        } catch (Exception e) {
            log.error("删除Topic失败：{}", e.getMessage(), e);
            return false;
        }
    }

    @Override
    public boolean scaleTopic(String topicName, Integer newPartitions, String clusterId,String username) {
        try {
            if (!StringUtils.hasText(topicName) || newPartitions == null || newPartitions <= 0) {
                log.error("扩容Topic失败：参数不能为空");
                return false;
            }

            // 1. 先从数据库获取现有主题信息
            TopicInfo existingTopic = topicMapper.selectTopicByNameAndCluster(topicName, clusterId);
            if (existingTopic == null) {
                log.error("扩容Topic失败：Topic [{}] 不存在，集群ID: {}", topicName, clusterId);
                return false;
            }

            // 2. 验证新分区数是否大于当前分区数
            if (newPartitions <= existingTopic.getPartitions()) {
                log.error("扩容Topic失败：新分区数 {} 必须大于当前分区数 {}", newPartitions, existingTopic.getPartitions());
                return false;
            }

            // 3. 获取broker信息
            List<BrokerInfo> brokerInfos = List.of();
            if (StringUtils.hasText(clusterId)) {
                brokerInfos = brokerMapper.getBrokersByClusterId(clusterId);
                if (brokerInfos.isEmpty()) {
                    log.warn("集群 {} 中未找到broker信息，尝试使用默认连接", clusterId);
                }
            }

            // 4. 创建KafkaClientInfo
            KafkaClientInfo kafkaClientInfo = new KafkaClientInfo();
            kafkaClientInfo.setClusterId(clusterId);

            if (!brokerInfos.isEmpty()) {
                // 使用第一个broker的信息作为连接信息
                BrokerInfo firstBroker = brokerInfos.get(0);
                kafkaClientInfo.setBrokerServer(firstBroker.getHostIp() + ":" + firstBroker.getPort());
            } else {
                log.error("扩容Topic失败：集群 {} 中未找到broker信息，请检查配置", clusterId);
                return false;
            }

            // 5. 创建NewTopicInfo用于扩容操作
            NewTopicInfo newTopicInfo = new NewTopicInfo();
            newTopicInfo.setTopicName(topicName);
            newTopicInfo.setPartitions(newPartitions);
            // 副本数保持不变
            newTopicInfo.setReplication(existingTopic.getReplicas().shortValue());

            // 6. 调用KafkaSchemaFactory执行实际的Kafka扩容操作
            KafkaSchemaFactory ksf = new KafkaSchemaFactory(new KafkaStoragePlugin());
            boolean kafkaSuccess = ksf.increaseTopicPartitions(kafkaClientInfo, newTopicInfo);

            if (!kafkaSuccess) {
                log.error("扩容Topic失败：Kafka扩容操作失败，主题: {}, 新分区数: {}", topicName, newPartitions);
                return false;
            }

            // 7. Kafka扩容成功后，更新数据库中的分区数
            existingTopic.setPartitions(newPartitions);
            existingTopic.setUpdateBy(username);
            int updateResult = topicMapper.updateTopic(existingTopic);

            if (updateResult > 0) {
                return true;
            } else {
                log.error("Topic扩容失败：数据库更新失败，主题: {}", topicName);
                // 注意：此时Kafka中的分区已经扩容，但数据库更新失败
                // 可以考虑记录这种不一致状态，或者通过定时任务同步
                return false;
            }

        } catch (Exception e) {
            log.error("扩容Topic失败：{}", e.getMessage(), e);
            return false;
        }
    }

    @Override
    public boolean setTopicRetention(String topicName, Long retentionMs, String clusterId,String username) {
        try {
            if (!StringUtils.hasText(topicName) || retentionMs == null || retentionMs <= 0) {
                log.error("设置Topic保留时间失败：参数不能为空");
                return false;
            }

            // 1. 先调用KafkaSchemaFactory更新Kafka中的保留时间
            KafkaSchemaFactory ksf = new KafkaSchemaFactory(new KafkaStoragePlugin());

            // 创建KafkaClientInfo
            KafkaClientInfo kafkaClientInfo = new KafkaClientInfo();
            kafkaClientInfo.setClusterId(clusterId);

            // 获取集群的broker信息用于连接
            List<BrokerInfo> brokerInfos = List.of();
            if (StringUtils.hasText(clusterId)) {
                brokerInfos = brokerMapper.getBrokersByClusterId(clusterId);
            }

            if (!brokerInfos.isEmpty()) {
                // 使用第一个broker的信息作为连接信息
                BrokerInfo firstBroker = brokerInfos.get(0);
                kafkaClientInfo.setBrokerServer(firstBroker.getHostIp() + ":" + firstBroker.getPort());
            } else {
                log.error("集群 {} 中未找到broker信息，请检查配置", clusterId);
                return false;
            }

            // 创建NewTopicInfo
            NewTopicInfo newTopicInfo = new NewTopicInfo();
            newTopicInfo.setTopicName(topicName);
            newTopicInfo.setRetainMs(retentionMs);

            // 调用KafkaSchemaFactory更新保留时间
            boolean kafkaUpdateSuccess = ksf.updateTopicRetention(kafkaClientInfo, newTopicInfo);
            if (!kafkaUpdateSuccess) {
                log.error("设置Topic保留时间失败：Kafka设置失败，主题: {}", topicName);
                return false;
            }

            // 2. Kafka设置成功后，更新数据库中的保留时间
            TopicInfo topicInfo = topicMapper.selectTopicByNameAndCluster(topicName, clusterId);
            if (topicInfo != null) {
                topicInfo.setRetentionTime(String.valueOf(retentionMs));
                topicInfo.setUpdateBy(username);
                int result = topicMapper.updateTopic(topicInfo);
                if (result > 0) {
                } else {
                    log.warn("数据库中的Topic保留时间更新失败，主题: {}", topicName);
                    // 注意：此时Kafka中的保留时间已经更新，但数据库更新失败
                }
            } else {
                log.warn("未找到Topic [{}] 在集群 [{}] 中的数据库记录", topicName, clusterId);
            }

            return true;

        } catch (Exception e) {
            log.error("设置Topic保留时间失败：{}", e.getMessage(), e);
            return false;
        }
    }

    @Override
    public boolean sendTestData(String topicName, String dataType, Integer messageCount) {
        try {
            if (!StringUtils.hasText(topicName)) {
                log.error("发送测试数据失败：Topic名称不能为空");
                return false;
            }
            if (messageCount == null || messageCount <= 0) {
                messageCount = 10;
            }
            if (!StringUtils.hasText(dataType)) {
                dataType = "json";
            }

            // 此功能需要实现 Kafka Producer 发送测试数据
            // 当前未实现，返回 true 表示接口调用成功
            log.warn("发送测试数据功能尚未实现，主题: {}, 数据类型: {}, 消息数量: {}", topicName, dataType, messageCount);
            return true;

        } catch (Exception e) {
            log.error("向Topic [{}] 发送测试数据失败：{}", topicName, e.getMessage(), e);
            return false;
        }
    }

    @Override
    public List<String> getAllTopicNames() {
        try {
            List<String> topicNames = topicMapper.selectAllTopicNames();
            return topicNames != null ? topicNames : List.of();
        } catch (Exception e) {
            log.error("获取主题名称列表失败：{}", e.getMessage(), e);
            return List.of();
        }
    }

    @Override
    public Map<String, Object> getTopicDetailedStats(String topicName, String clusterId) {
        Map<String, Object> stats = new HashMap<>();
        stats.put("totalRecords", 0L);
        stats.put("totalSize", 0L);
        stats.put("writeSpeed", 0.0);
        stats.put("readSpeed", 0.0);

        try {
            if (!StringUtils.hasText(topicName)) {
                log.warn("主题名称为空，返回默认统计数据");
                return stats;
            }

            // 获取集群的broker信息
            List<BrokerInfo> brokerInfos = List.of();
            if (StringUtils.hasText(clusterId)) {
                brokerInfos = brokerMapper.getBrokersByClusterId(clusterId);
                if (brokerInfos.isEmpty()) {
                    log.warn("集群 {} 中未找到任何broker信息", clusterId);
                    return stats;
                }
            }

            // 创建KafkaClientInfo
            KafkaClientInfo kafkaClientInfo = new KafkaClientInfo();
            kafkaClientInfo.setClusterId(clusterId);
            if (!brokerInfos.isEmpty()) {
                // 使用第一个broker的信息作为连接信息
                BrokerInfo firstBroker = brokerInfos.get(0);
                kafkaClientInfo.setBrokerServer(firstBroker.getHostIp() + ":" + firstBroker.getPort());
            }

            KafkaSchemaFactory ksf = new KafkaSchemaFactory(new KafkaStoragePlugin());

            // 获取主题记录数
            try {
                Long capacity = ksf.getTopicRecordCapacityNum(kafkaClientInfo, brokerInfos, topicName);
                stats.put("totalSize", capacity != null ? capacity : 0L);
            } catch (Exception e) {
                log.warn("获取主题 {} 记录数失败：{}", topicName, e.getMessage());
            }

            // 获取主题大小
            try {
                // totalRecords
                Long logsize = ksf.getTotalActualTopicLogSize(kafkaClientInfo, topicName);
                stats.put("totalRecords", logsize != null ? logsize : 0L);
            } catch (Exception e) {
                log.warn("获取主题 {} 大小失败：{}", topicName, e.getMessage());
            }

            // 获取读写速度
            try {
                TopicDetailedStats topicStats = ksf.getTopicMetaData(kafkaClientInfo, topicName);
                if (topicStats != null && !brokerInfos.isEmpty()) {
                    // 获取写入和读取速度
                    BigDecimal writeSpeed = getTopicJmxMetric(brokerInfos, topicName, JmxMetricsConst.Server.BYTES_IN_PER_SEC_TOPIC.key());
                    BigDecimal readSpeed = getTopicJmxMetric(brokerInfos, topicName, JmxMetricsConst.Server.BYTES_OUT_PER_SEC_TOPIC.key());

                    stats.put("writeSpeed", writeSpeed != null ? writeSpeed.doubleValue() : 0.0);
                    stats.put("readSpeed", readSpeed != null ? readSpeed.doubleValue() : 0.0);
                }
            } catch (Exception e) {
                log.warn("获取主题 {} 元数据失败：{}", topicName, e.getMessage());
            }

        } catch (Exception e) {
            log.error("获取主题 {} 详细统计信息失败：{}", topicName, e.getMessage(), e);
        }

        return stats;
    }

    @Override
    public Map<String, Object> getTopicPartitionPage(String topicName, String clusterId, Map<String, Object> params) {
        Map<String, Object> result = new HashMap<>();

        try {
            // 获取broker信息
            List<BrokerInfo> brokerInfos = brokerMapper.getBrokersByClusterId(clusterId);

            if (brokerInfos.isEmpty()) {
                log.warn("未找到集群 {} 的broker信息", clusterId);
                result.put("records", List.of());
                result.put("total", 0);
                return result;
            }

            // 创建KafkaClientInfo
            KafkaClientInfo kafkaClientInfo = new KafkaClientInfo();
            kafkaClientInfo.setClusterId(clusterId);
            // 使用第一个broker的信息作为连接信息
            BrokerInfo firstBroker = brokerInfos.get(0);
            kafkaClientInfo.setBrokerServer(firstBroker.getHostIp() + ":" + firstBroker.getPort());

            // 调用KafkaSchemaFactory获取分区分页数据
            KafkaSchemaFactory ksf = new KafkaSchemaFactory(new KafkaStoragePlugin());
            TopicPartitionPageResult pageResult = ksf.getTopicPartitionPage(kafkaClientInfo, topicName, params);

            result.put("records", pageResult.getRecords());
            result.put("total", pageResult.getTotal());
        } catch (Exception e) {
            log.error("获取主题 {} 分区分页数据失败：{}", topicName, e.getMessage(), e);
            result.put("records", List.of());
            result.put("total", 0);
        }

        return result;
    }

    @Override
    public List<Map<String, Object>> getTopicPartitionMessages(String topicName, String clusterId, Integer partition, Integer limit) {
        List<Map<String, Object>> messages = new ArrayList<>();

        try {
            // 获取broker信息
            List<BrokerInfo> brokerInfos = brokerMapper.getBrokersByClusterId(clusterId);

            if (brokerInfos.isEmpty()) {
                log.warn("未找到集群 {} 的broker信息", clusterId);
                return messages;
            }

            // 创建KafkaClientInfo
            KafkaClientInfo kafkaClientInfo = new KafkaClientInfo();
            kafkaClientInfo.setClusterId(clusterId);
            // 使用第一个broker的信息作为连接信息
            BrokerInfo firstBroker = brokerInfos.get(0);
            kafkaClientInfo.setBrokerServer(firstBroker.getHostIp() + ":" + firstBroker.getPort());

            // 调用KafkaSchemaFactory获取分区消息
            KafkaSchemaFactory ksf = new KafkaSchemaFactory(new KafkaStoragePlugin());
            String messagesJson = ksf.fetchLatestMessages(kafkaClientInfo, topicName, partition);

            // 解析JSON字符串为List<Map<String, Object>>
            if (messagesJson != null && !messagesJson.trim().isEmpty()) {
                try {
                    com.alibaba.fastjson2.JSONArray jsonArray = com.alibaba.fastjson2.JSONArray.parseArray(messagesJson);
                    for (int i = 0; i < jsonArray.size(); i++) {
                        com.alibaba.fastjson2.JSONObject jsonObject = jsonArray.getJSONObject(i);
                        Map<String, Object> messageMap = new HashMap<>();
                        messageMap.put("partition", jsonObject.getInteger("partition"));
                        messageMap.put("offset", jsonObject.getLong("offset"));
                        messageMap.put("value", jsonObject.getString("value"));
                        messageMap.put("timestamp", jsonObject.getLong("timestamp"));
                        // 检查是否有key字段
                        if (jsonObject.containsKey("key")) {
                            messageMap.put("key", jsonObject.getString("key"));
                        }
                        messages.add(messageMap);
                    }
                } catch (Exception e) {
                    log.error("解析消息JSON失败: {}", e.getMessage(), e);
                }
            }

        } catch (Exception e) {
            log.error("获取主题 {} 分区 {} 消息失败：{}", topicName, partition, e.getMessage(), e);
        }

        return messages;
    }

    @Override
    public Map<String, String> getTopicConfig(String topicName, String clusterId) {
        Map<String, String> topicConfig = new HashMap<>();

        try {
            if (!StringUtils.hasText(topicName)) {
                log.warn("主题名称为空，返回空配置");
                return topicConfig;
            }

            // 获取集群的broker信息
            List<BrokerInfo> brokerInfos = List.of();
            if (StringUtils.hasText(clusterId)) {
                brokerInfos = brokerMapper.getBrokersByClusterId(clusterId);
                if (brokerInfos.isEmpty()) {
                    log.warn("集群 {} 中未找到任何broker信息", clusterId);
                    return topicConfig;
                }
            } else {
                log.warn("集群ID为空，无法获取主题配置");
                return topicConfig;
            }

            // 创建KafkaClientInfo
            KafkaClientInfo kafkaClientInfo = new KafkaClientInfo();
            kafkaClientInfo.setClusterId(clusterId);

            // 使用第一个broker的信息作为连接信息
            BrokerInfo firstBroker = brokerInfos.get(0);
            kafkaClientInfo.setBrokerServer(firstBroker.getHostIp() + ":" + firstBroker.getPort());

            // 调用KafkaSchemaFactory获取主题配置
            KafkaSchemaFactory ksf = new KafkaSchemaFactory(new KafkaStoragePlugin());
            topicConfig = ksf.getTopicConfig(kafkaClientInfo, topicName);

            if (topicConfig != null && !topicConfig.isEmpty()) {
            } else {
                log.warn("主题 {} 配置信息为空", topicName);
                topicConfig = new HashMap<>();
            }

        } catch (Exception e) {
            log.error("获取主题 {} 配置信息失败：{}", topicName, e.getMessage(), e);
            return new HashMap<>();
        }

        return topicConfig;
    }

    /**
     * 获取主题JMX指标
     */
    private BigDecimal getTopicJmxMetric(List<BrokerInfo> brokerInfos, String topicName, String metricKey) {
        try {
            BigDecimal totalMetric = BigDecimal.ZERO;
            int validBrokers = 0;

            for (BrokerInfo brokerInfo : brokerInfos) {
                try {
                    JMXInitializeInfo jmxInfo = new JMXInitializeInfo();
                    jmxInfo.setBrokerId(String.valueOf(brokerInfo.getBrokerId()));
                    jmxInfo.setHost(brokerInfo.getHostIp());
                    jmxInfo.setPort(brokerInfo.getJmxPort());

                    String objectName = String.format(metricKey, topicName);
                    BigDecimal metricValue = executeJmxOperation(jmxInfo, objectName, MBeanMetricsConst.Common.ONE_MINUTE_RATE.key());

                    if (metricValue != null) {
                        totalMetric = totalMetric.add(metricValue);
                        validBrokers++;
                    }
                } catch (Exception e) {
                    log.warn("获取Broker {} JMX指标失败：{}", brokerInfo.getBrokerId(), e.getMessage());
                }
            }

            return validBrokers > 0 ? totalMetric : BigDecimal.ZERO;
        } catch (Exception e) {
            log.error("获取主题 {} JMX指标失败：{}", topicName, e.getMessage(), e);
            return BigDecimal.ZERO;
        }
    }

    /**
     * 执行JMX操作
     */
    private BigDecimal executeJmxOperation(JMXInitializeInfo jmxInfo, String objectName, String attribute) {
        JMXConnector connector = null;
        try {
            String jmxUrl = String.format("service:jmx:rmi:///jndi/rmi://%s:%d/jmxrmi", jmxInfo.getHost(), jmxInfo.getPort());
            JMXServiceURL serviceURL = new JMXServiceURL(jmxUrl);
            connector = JMXConnectorFactory.connect(serviceURL);
            MBeanServerConnection connection = connector.getMBeanServerConnection();

            Object value = connection.getAttribute(new javax.management.ObjectName(objectName), attribute);
            if (value instanceof Number) {
                return new BigDecimal(value.toString());
            }
        } catch (Exception e) {
            log.warn("执行JMX操作失败：{}", e.getMessage());
        } finally {
            if (connector != null) {
                try {
                    connector.close();
                } catch (Exception e) {
                    log.warn("关闭JMX连接失败：{}", e.getMessage());
                }
            }
        }
        return null;
    }

    /**
     * 根据Leader ID获取对应的Broker JMX连接信息
     */
    private JMXInitializeInfo getBrokerJmxRmiOfLeaderId(List<BrokerInfo> brokerInfos, Integer leadId) {
        JMXInitializeInfo initializeInfo = new JMXInitializeInfo();
        for (BrokerInfo brokerInfo : brokerInfos) {
            if (leadId.equals(brokerInfo.getBrokerId())) {
                initializeInfo.setBrokerId(String.valueOf(leadId));
                initializeInfo.setHost(brokerInfo.getHostIp());
                initializeInfo.setPort(brokerInfo.getJmxPort());
                break;
            }
        }
            return initializeInfo;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> syncTopicsFromKafka(String clusterId) {
        Map<String, Object> result = new HashMap<>();
        int addedCount = 0;
        int updatedCount = 0;
        int errorCount = 0;
        List<String> errors = new ArrayList<>();

        try {
            if (!StringUtils.hasText(clusterId)) {
                result.put("success", false);
                result.put("message", "集群ID不能为空");
                return result;
            }

            // 获取集群的broker信息
            List<BrokerInfo> brokerInfos = brokerMapper.getBrokersByClusterId(clusterId);
            if (brokerInfos.isEmpty()) {
                result.put("success", false);
                result.put("message", "未找到集群 " + clusterId + " 的broker信息");
                return result;
            }

            // 创建KafkaClientInfo
            KafkaClientInfo kafkaClientInfo = new KafkaClientInfo();
            kafkaClientInfo.setClusterId(clusterId);
            BrokerInfo firstBroker = brokerInfos.get(0);
            kafkaClientInfo.setBrokerServer(firstBroker.getHostIp() + ":" + firstBroker.getPort());

            // 调用KafkaSchemaFactory获取Kafka中的所有topic
            KafkaSchemaFactory ksf = new KafkaSchemaFactory(new KafkaStoragePlugin());
            Set<String> kafkaTopicNames = ksf.listTopicNames(kafkaClientInfo);

            if (kafkaTopicNames.isEmpty()) {
                result.put("success", true);
                result.put("message", "Kafka集群中没有topic");
                result.put("added", 0);
                result.put("updated", 0);
                result.put("total", 0);
                result.put("errors", errors);
                return result;
            }

            log.info("开始同步集群 {} 的 {} 个topic", clusterId, kafkaTopicNames.size());

            // 批量获取topic的详细信息
            Map<String, TopicDetailedStats> topicMetaDataMap = ksf.getTopicMetaData(kafkaClientInfo, kafkaTopicNames, brokerInfos);

            // 遍历每个topic，同步到数据库
            for (String topicName : kafkaTopicNames) {
                try {
                    // 检查数据库中是否存在该topic
                    TopicInfo existingTopic = topicMapper.selectTopicByNameAndCluster(topicName, clusterId);
                    
                    // 获取topic的详细信息
                    TopicDetailedStats metaData = topicMetaDataMap.get(topicName);
                    if (metaData == null) {
                        log.warn("无法获取topic {} 的元数据，跳过", topicName);
                        errorCount++;
                        errors.add("Topic " + topicName + ": 无法获取元数据");
                        continue;
                    }

                    if (existingTopic == null) {
                        // 新增topic
                        TopicInfo newTopic = new TopicInfo();
                        newTopic.setTopicName(topicName);
                        newTopic.setClusterId(clusterId);
                        newTopic.setPartitions(metaData.getPartitionCount());
                        newTopic.setReplicas(metaData.getReplicationFactor());
                        newTopic.setRetentionTime(metaData.getRetentionMs());
                        newTopic.setBrokerSpread(String.valueOf(metaData.getBrokerSpread()));
                        newTopic.setBrokerSkewed(String.valueOf(metaData.getBrokerSkewed()));
                        newTopic.setLeaderSkewed(String.valueOf(metaData.getLeaderSkewed()));
                        newTopic.setCreateBy("system");
                        newTopic.setUpdateBy("system");

                        int insertResult = topicMapper.insertTopic(newTopic);
                        if (insertResult > 0) {
                            addedCount++;
                            log.debug("新增topic: {}", topicName);
                        } else {
                            errorCount++;
                            errors.add("Topic " + topicName + ": 插入失败");
                        }
                    } else {
                        // 更新已存在的topic
                        boolean needUpdate = false;

                        if (!existingTopic.getPartitions().equals(metaData.getPartitionCount())) {
                            existingTopic.setPartitions(metaData.getPartitionCount());
                            needUpdate = true;
                        }

                        if (!existingTopic.getReplicas().equals(metaData.getReplicationFactor())) {
                            existingTopic.setReplicas(metaData.getReplicationFactor());
                            needUpdate = true;
                        }

                        if (metaData.getRetentionMs() != null && !metaData.getRetentionMs().equals(existingTopic.getRetentionTime())) {
                            existingTopic.setRetentionTime(metaData.getRetentionMs());
                            needUpdate = true;
                        }

                        String brokerSpreadStr = String.valueOf(metaData.getBrokerSpread());
                        if (!brokerSpreadStr.equals(existingTopic.getBrokerSpread())) {
                            existingTopic.setBrokerSpread(brokerSpreadStr);
                            needUpdate = true;
                        }

                        String brokerSkewedStr = String.valueOf(metaData.getBrokerSkewed());
                        if (!brokerSkewedStr.equals(existingTopic.getBrokerSkewed())) {
                            existingTopic.setBrokerSkewed(brokerSkewedStr);
                            needUpdate = true;
                        }

                        String leaderSkewedStr = String.valueOf(metaData.getLeaderSkewed());
                        if (!leaderSkewedStr.equals(existingTopic.getLeaderSkewed())) {
                            existingTopic.setLeaderSkewed(leaderSkewedStr);
                            needUpdate = true;
                        }

                        if (needUpdate) {
                            existingTopic.setUpdateBy("system");
                            int updateResult = topicMapper.updateTopic(existingTopic);
                            if (updateResult > 0) {
                                updatedCount++;
                                log.debug("更新topic: {}", topicName);
                            } else {
                                errorCount++;
                                errors.add("Topic " + topicName + ": 更新失败");
                            }
                        }
                    }
                } catch (Exception e) {
                    log.error("同步topic {} 失败：{}", topicName, e.getMessage(), e);
                    errorCount++;
                    errors.add("Topic " + topicName + ": " + e.getMessage());
                }
            }

            result.put("success", true);
            result.put("message", "同步完成");
            result.put("added", addedCount);
            result.put("updated", updatedCount);
            result.put("total", kafkaTopicNames.size());
            result.put("errors", errorCount);
            result.put("errorDetails", errors);

            log.info("集群 {} topic同步完成：新增 {}, 更新 {}, 总数 {}, 错误 {}", 
                    clusterId, addedCount, updatedCount, kafkaTopicNames.size(), errorCount);

        } catch (Exception e) {
            log.error("同步集群 {} 的topic失败：{}", clusterId, e.getMessage(), e);
            result.put("success", false);
            result.put("message", "同步失败: " + e.getMessage());
            result.put("added", addedCount);
            result.put("updated", updatedCount);
            result.put("errors", errorCount);
            result.put("errorDetails", errors);
        }

        return result;
    }

    @Override
    public Map<String, Object> getTopicConsumerGroups(String topicName, String clusterId, Integer page, Integer pageSize) {
        Map<String, Object> result = new HashMap<>();

        try {
            // 设置默认值
            int currentPage = page != null && page > 0 ? page : 1;
            int size = pageSize != null && pageSize > 0 ? pageSize : 5;
            int offset = (currentPage - 1) * size;

            // 查询消费者组数据
            List<Map<String, Object>> consumerGroups = consumerGroupTopicMapper.getConsumerGroupsByTopicForDetail(
                clusterId, topicName, offset, size);

            // 查询总数
            Long total = consumerGroupTopicMapper.countConsumerGroupsByTopicForDetail(clusterId, topicName);

            // 处理数据格式，转换为前端需要的格式
            List<Map<String, Object>> formattedGroups = new ArrayList<>();
            for (Map<String, Object> group : consumerGroups) {
                Map<String, Object> formattedGroup = new HashMap<>();
                formattedGroup.put("groupId", group.get("group_id"));
                formattedGroup.put("topicName", group.get("topic_name"));
                formattedGroup.put("logsize", group.get("logsize"));
                formattedGroup.put("offsets", group.get("offsets"));
                formattedGroup.put("lag", group.get("lags"));

                // 转换状态显示
                String state = (String) group.get("state");
                String stateDisplay;
                switch (state) {
                    case "EMPTY":
                        stateDisplay = "空闲";
                        break;
                    case "STABLE":
                        stateDisplay = "活跃";
                        break;
                    case "DEAD":
                        stateDisplay = "停止";
                        break;
                    default:
                        stateDisplay = state;
                }
                formattedGroup.put("state", stateDisplay);
                formattedGroup.put("stateCode", state); // 保留原始状态码

                formattedGroups.add(formattedGroup);
            }

            result.put("data", formattedGroups);
            result.put("total", total != null ? total : 0L);
            result.put("page", currentPage);
            result.put("pageSize", size);
            result.put("totalPages", total != null ? (int) Math.ceil((double) total / size) : 0);

        } catch (Exception e) {
            log.error("获取主题 {} 的消费者组信息失败：{}", topicName, e.getMessage(), e);
            result.put("data", new ArrayList<>());
            result.put("total", 0L);
            result.put("page", page != null ? page : 1);
            result.put("pageSize", pageSize != null ? pageSize : 5);
            result.put("totalPages", 0);
        }

        return result;
    }

    @Override
    public Map<String, Object> sendMessage(org.kafka.eagle.dto.topic.TopicMessageSendRequest request) {
        Map<String, Object> result = new HashMap<>();
        
        try {
            // 参数校验
            if (request == null || !StringUtils.hasText(request.getTopicName()) 
                    || !StringUtils.hasText(request.getClusterId())) {
                result.put("success", false);
                result.put("message", "参数不完整：Topic名称和集群ID不能为空");
                return result;
            }
            
            if (!StringUtils.hasText(request.getMessage())) {
                result.put("success", false);
                result.put("message", "消息内容不能为空");
                return result;
            }
            
            // 获取集群broker信息
            List<BrokerInfo> brokerInfos = brokerMapper.getBrokersByClusterId(request.getClusterId());
            if (brokerInfos.isEmpty()) {
                result.put("success", false);
                result.put("message", "集群 " + request.getClusterId() + " 没有可用的broker信息");
                return result;
            }
            
            // 构造broker地址
            StringBuilder bootstrapServers = new StringBuilder();
            for (int i = 0; i < brokerInfos.size(); i++) {
                BrokerInfo broker = brokerInfos.get(i);
                bootstrapServers.append(broker.getHostIp()).append(":").append(broker.getPort());
                if (i < brokerInfos.size() - 1) {
                    bootstrapServers.append(",");
                }
            }
            
            // 判断消息格式
            String format = request.getFormat();
            if (!StringUtils.hasText(format)) {
                format = "json"; // 默认JSON格式
            }
            
            int successCount = 0;
            int failCount = 0;
            int messageCount = request.getCount() != null && request.getCount() > 0 ? request.getCount() : 1;
            
            // 根据格式选择不同的发送逻辑
            if ("avro".equalsIgnoreCase(format)) {
                // ========== Avro格式发送 ==========
                
                // 1. 校验Schema
                if (!StringUtils.hasText(request.getSchema())) {
                    result.put("success", false);
                    result.put("message", "Avro格式必须提供Schema");
                    return result;
                }
                
                // 2. 获取集群的Schema Registry URL
                KafkaClusterInfo clusterInfo = clusterMapper.findByClusterId(request.getClusterId());
                if (clusterInfo == null || !StringUtils.hasText(clusterInfo.getSchemaRegistryUrl())) {
                    result.put("success", false);
                    result.put("message", "集群未配置Schema Registry URL，请先在集群配置中设置");
                    return result;
                }
                
                // 3. 验证Schema和消息格式
                String validationError = AvroMessageSerializer.validateJsonAgainstSchema(
                    request.getMessage(), request.getSchema());
                if (validationError != null) {
                    result.put("success", false);
                    result.put("message", "消息与Schema不匹配: " + validationError);
                    return result;
                }
                
                // 4. 配置Avro Producer
                Properties avroProps = new Properties();
                avroProps.put("bootstrap.servers", bootstrapServers.toString());
                avroProps.put("key.serializer", "org.apache.kafka.common.serialization.StringSerializer");
                avroProps.put("value.serializer", "io.confluent.kafka.serializers.KafkaAvroSerializer");
                avroProps.put("schema.registry.url", clusterInfo.getSchemaRegistryUrl());
                avroProps.put("acks", "all");
                avroProps.put("retries", 3);
                avroProps.put("max.request.size", 10485760); // 10MB
                
                try (org.apache.kafka.clients.producer.KafkaProducer<String, org.apache.avro.generic.GenericRecord> avroProducer = 
                        new org.apache.kafka.clients.producer.KafkaProducer<>(avroProps)) {
                    
                    for (int i = 0; i < messageCount; i++) {
                        try {
                            // 将JSON转换为GenericRecord
                            org.apache.avro.generic.GenericRecord avroRecord = 
                                AvroMessageSerializer.jsonToAvroRecord(request.getMessage(), request.getSchema());
                            
                            // 构造ProducerRecord
                            org.apache.kafka.clients.producer.ProducerRecord<String, org.apache.avro.generic.GenericRecord> record;
                            if (request.getPartition() != null) {
                                record = new org.apache.kafka.clients.producer.ProducerRecord<>(
                                    request.getTopicName(), 
                                    request.getPartition(), 
                                    request.getKey(), 
                                    avroRecord
                                );
                            } else if (StringUtils.hasText(request.getKey())) {
                                record = new org.apache.kafka.clients.producer.ProducerRecord<>(
                                    request.getTopicName(), 
                                    request.getKey(), 
                                    avroRecord
                                );
                            } else {
                                record = new org.apache.kafka.clients.producer.ProducerRecord<>(
                                    request.getTopicName(), 
                                    avroRecord
                                );
                            }
                            
                            // 发送消息
                            avroProducer.send(record).get();
                            successCount++;
                            
                        } catch (Exception e) {
                            log.error("发送Avro消息失败，第{}条: {}", i + 1, e.getMessage(), e);
                            failCount++;
                        }
                    }
                }
                
            } else {
                // ========== JSON格式发送 ==========
                
                // 验证JSON格式
                try {
                    new com.fasterxml.jackson.databind.ObjectMapper().readTree(request.getMessage());
                } catch (Exception e) {
                    result.put("success", false);
                    result.put("message", "JSON格式错误: " + e.getMessage());
                    return result;
                }
                
                // 配置JSON Producer
                Properties jsonProps = new Properties();
                jsonProps.put("bootstrap.servers", bootstrapServers.toString());
                jsonProps.put("key.serializer", "org.apache.kafka.common.serialization.StringSerializer");
                jsonProps.put("value.serializer", "org.apache.kafka.common.serialization.StringSerializer");
                jsonProps.put("acks", "all");
                jsonProps.put("retries", 3);
                jsonProps.put("max.request.size", 10485760); // 10MB
                
                try (org.apache.kafka.clients.producer.KafkaProducer<String, String> producer = 
                        new org.apache.kafka.clients.producer.KafkaProducer<>(jsonProps)) {
                    
                    for (int i = 0; i < messageCount; i++) {
                        try {
                            // 构造ProducerRecord
                            org.apache.kafka.clients.producer.ProducerRecord<String, String> record;
                            if (request.getPartition() != null) {
                                record = new org.apache.kafka.clients.producer.ProducerRecord<>(
                                    request.getTopicName(), 
                                    request.getPartition(), 
                                    request.getKey(), 
                                    request.getMessage()
                                );
                            } else if (StringUtils.hasText(request.getKey())) {
                                record = new org.apache.kafka.clients.producer.ProducerRecord<>(
                                    request.getTopicName(), 
                                    request.getKey(), 
                                    request.getMessage()
                                );
                            } else {
                                record = new org.apache.kafka.clients.producer.ProducerRecord<>(
                                    request.getTopicName(), 
                                    request.getMessage()
                                );
                            }
                            
                            // 发送消息
                            producer.send(record).get();
                            successCount++;
                            
                        } catch (Exception e) {
                            log.error("发送JSON消息失败，第{}条: {}", i + 1, e.getMessage(), e);
                            failCount++;
                        }
                    }
                }
            }
            
            // 返回结果
            if (failCount == 0) {
                result.put("success", true);
                result.put("message", "发送成功");
                result.put("successCount", successCount);
                result.put("failCount", 0);
            } else if (successCount > 0) {
                result.put("success", true);
                result.put("message", "部分成功");
                result.put("successCount", successCount);
                result.put("failCount", failCount);
            } else {
                result.put("success", false);
                result.put("message", "全部失败");
                result.put("successCount", 0);
                result.put("failCount", failCount);
            }
            
        } catch (Exception e) {
            log.error("发送消息异常: {}", e.getMessage(), e);
            result.put("success", false);
            result.put("message", "发送失败: " + e.getMessage());
        }
        
        return result;
    }
    
    @Override
    public String getTopicSchemaFromRegistry(String topicName, String clusterId) {
        try {
            // 1. 获取集群的Schema Registry URL
            KafkaClusterInfo clusterInfo = clusterMapper.findByClusterId(clusterId);
            if (clusterInfo == null || !StringUtils.hasText(clusterInfo.getSchemaRegistryUrl())) {
                log.warn("集群 {} 未配置Schema Registry URL", clusterId);
                return null;
            }
            
            String schemaRegistryUrl = clusterInfo.getSchemaRegistryUrl();
            
            // 2. 构造Schema Registry API URL
            // Schema Registry的命名规则：{topicName}-value 或 {topicName}-key
            // 这里获取value的schema（消息内容）
            String subject = topicName + "-value";
            String apiUrl = schemaRegistryUrl + "/subjects/" + subject + "/versions/latest";
            
            log.info("获取Topic {} 的Schema，API URL: {}", topicName, apiUrl);
            
            // 3. 调用Schema Registry API
            java.net.http.HttpClient client = java.net.http.HttpClient.newHttpClient();
            java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
                    .uri(java.net.URI.create(apiUrl))
                    .header("Accept", "application/json")
                    .GET()
                    .build();
            
            java.net.http.HttpResponse<String> response = client.send(request, 
                    java.net.http.HttpResponse.BodyHandlers.ofString());
            
            // 4. 解析响应
            if (response.statusCode() == 200) {
                // 解析JSON获取schema字段
                com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                com.fasterxml.jackson.databind.JsonNode jsonNode = mapper.readTree(response.body());
                String schema = jsonNode.get("schema").asText();
                
                log.info("成功获取Topic {} 的Schema", topicName);
                return schema;
            } else if (response.statusCode() == 404) {
                log.warn("Topic {} 没有注册Schema到Schema Registry", topicName);
                return null;
            } else {
                log.error("获取Schema失败，HTTP状态码: {}, 响应: {}", 
                        response.statusCode(), response.body());
                return null;
            }
            
        } catch (Exception e) {
            log.error("从Schema Registry获取Topic {} 的Schema失败：{}", topicName, e.getMessage(), e);
            return null;
        }
    }
}
