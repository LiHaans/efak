# 消费者组监控任务数据未入库问题诊断

## 问题描述
任务执行完成，日志显示"任务 消费者组监控任务 执行完成"，但 `ke_consumer_group_topic` 表中没有新数据。

## 可能原因分析

### 1. 数据采集阶段问题
- 集群列表为空或未启用
- Kafka连接失败
- 消费者组列表为空
- 获取消费者组详情失败

### 2. 数据保存阶段问题
- 参数验证失败（clusterId、groupId、topicName 为空）
- 批量插入SQL执行失败
- 事务回滚
- 数据库连接问题

### 3. 分片协调问题
- 当前节点未分配到消费者组
- 分片结果为空

## 排查步骤

### 步骤1：开启DEBUG日志
修改 `application.yml` 或 `application.properties`：

```yaml
logging:
  level:
    org.kafka.eagle.web.service.TaskExecutorManager: DEBUG
    org.kafka.eagle.web.service.impl.ConsumerGroupTopicServiceImpl: DEBUG
    org.kafka.eagle.web.mapper.ConsumerGroupTopicMapper: DEBUG
    org.kafka.eagle.core.api.KafkaSchemaFactory: DEBUG
```

### 步骤2：查看详细日志

关键日志点：

1. **任务开始执行**
```
2025-11-16 22:46:20 [pool-2-thread-7] INFO  [...] o.k.e.w.s.TaskExecutorManager - 开始执行消费者监控任务 (分片模式)
```

2. **获取集群列表**
```log
应有日志：从数据库获取到 X 个集群
```

3. **消费者组分片**
```log
应有日志：总共 X 个消费者组，当前节点分配到 X 个
```

4. **数据保存**
```log
应有日志：批量保存消费者组主题信息到数据库
成功日志：批量保存 X 条记录
失败日志：批量保存消费者组主题信息到数据库失败 / 批量插入消费者组主题数据失败
```

### 步骤3：手动SQL查询验证

```sql
-- 查看最近的消费者组数据
SELECT * FROM ke_consumer_group_topic 
ORDER BY collect_time DESC 
LIMIT 10;

-- 查看今天的数据统计
SELECT collect_date, COUNT(*) as count 
FROM ke_consumer_group_topic 
WHERE collect_date = CURDATE()
GROUP BY collect_date;

-- 查看按集群统计
SELECT cluster_id, COUNT(*) as count, MAX(collect_time) as last_collect 
FROM ke_consumer_group_topic 
WHERE collect_date = CURDATE()
GROUP BY cluster_id;
```

### 步骤4：检查集群配置

```sql
-- 查看集群配置
SELECT cluster_id, name, cluster_type, auth 
FROM ke_cluster 
WHERE status = 'ACTIVE';

-- 查看broker信息
SELECT cluster_id, broker_id, host_ip, port, status 
FROM ke_broker_info 
ORDER BY cluster_id, broker_id;
```

### 步骤5：手动测试Kafka连接

添加临时测试代码到 `TaskExecutorManager.java`：

```java
// 在 executeConsumerMonitorTask 方法开始处添加
log.info("=== 开始消费者组监控任务诊断 ===");
log.info("当前时间: {}", LocalDateTime.now());
log.info("集群数量: {}", clusters.size());
for (KafkaClusterInfo cluster : clusters) {
    log.info("集群: {}, ID: {}", cluster.getName(), cluster.getClusterId());
}
```

## 常见问题修复方案

### 问题1：连接池导致连接失败
**症状**：任务执行但日志中有"连接失败"或超时错误

**修复**：检查 `KafkaClientPool` 的健康检查和连接获取逻辑

```java
// 在 TaskExecutorManager 中添加连接池状态日志
log.info("连接池状态: {}", storagePlugin.getClientPool().getStatistics());
```

### 问题2：数据为空但无异常
**症状**：日志显示 savedCount=0，但无错误日志

**原因**：
- 消费者组列表为空
- 分片后当前节点未分配到任何消费者组

**修复**：增加详细日志

```java
// 在 saveConsumerGroupTopicInfosToDatabase 方法开始处添加
log.info("准备保存消费者组数据，数量: {}", consumerGroupTopicInfos.size());
if (consumerGroupTopicInfos.isEmpty()) {
    log.warn("消费者组主题信息为空，跳过保存");
    return 0;
}

// 打印前几条数据用于检查
for (int i = 0; i < Math.min(3, consumerGroupTopicInfos.size()); i++) {
    ConsumerGroupTopicInfo info = consumerGroupTopicInfos.get(i);
    log.info("样本数据[{}]: cluster={}, group={}, topic={}, lags={}", 
        i, info.getClusterId(), info.getGroupId(), 
        info.getTopicName(), info.getLags());
}
```

### 问题3：批量插入失败无详细错误
**症状**：日志只显示"批量插入失败"，无具体原因

**修复**：在 `ConsumerGroupTopicServiceImpl.batchInsertConsumerGroupTopic` 中增加详细日志

```java
try {
    log.info("执行批量插入，数据量: {}", requests.size());
    int result = consumerGroupTopicMapper.batchInsertConsumerGroupTopic(requests);
    log.info("批量插入结果: 影响行数={}", result);
    if (result > 0) {
        return true;
    } else {
        log.error("批量插入失败：数据库操作返回0，期望影响 {} 行", requests.size());
        return false;
    }
} catch (Exception e) {
    log.error("批量插入异常，数据量={}，详细错误: {}", requests.size(), e.getMessage(), e);
    // 打印第一条数据用于调试
    if (!requests.isEmpty()) {
        log.error("第一条数据样本: {}", requests.get(0));
    }
    return false;
}
```

### 问题4：参数验证失败
**症状**：日志显示"存在无效的请求参数"

**修复**：详细记录哪些字段为空

```java
for (int i = 0; i < requests.size(); i++) {
    ConsumerGroupTopicInsertRequest request = requests.get(i);
    List<String> errors = new ArrayList<>();
    if (request.getClusterId() == null || request.getClusterId().trim().isEmpty()) {
        errors.add("clusterId为空");
    }
    if (request.getGroupId() == null || request.getGroupId().trim().isEmpty()) {
        errors.add("groupId为空");
    }
    if (request.getTopicName() == null || request.getTopicName().trim().isEmpty()) {
        errors.add("topicName为空");
    }
    if (!errors.isEmpty()) {
        log.error("第{}条数据验证失败: {}, 数据: {}", i, String.join(", ", errors), request);
        return false;
    }
}
```

## 快速修复代码

将以下代码合并到相应文件：

### TaskExecutorManager.java

在 `saveConsumerGroupTopicInfosToDatabase` 方法中添加详细日志：

```java
private int saveConsumerGroupTopicInfosToDatabase(List<ConsumerGroupTopicInfo> consumerGroupTopicInfos) {
    int savedCount = 0;

    if (consumerGroupTopicInfos == null || consumerGroupTopicInfos.isEmpty()) {
        log.warn("消费者组主题信息为空，跳过保存");
        return savedCount;
    }

    log.info("开始保存消费者组数据到数据库，数量: {}", consumerGroupTopicInfos.size());

    try {
        List<org.kafka.eagle.dto.consumer.ConsumerGroupTopicInsertRequest> insertRequests = new ArrayList<>();

        for (ConsumerGroupTopicInfo cgti : consumerGroupTopicInfos) {
            try {
                org.kafka.eagle.dto.consumer.ConsumerGroupTopicInsertRequest insertRequest =
                        new org.kafka.eagle.dto.consumer.ConsumerGroupTopicInsertRequest();

                insertRequest.setClusterId(cgti.getClusterId());
                insertRequest.setGroupId(cgti.getGroupId());
                insertRequest.setTopicName(cgti.getTopicName());
                insertRequest.setState(cgti.getState());
                insertRequest.setLogsize(cgti.getLogsize());
                insertRequest.setOffsets(cgti.getOffsets());
                insertRequest.setLags(cgti.getLags());
                insertRequest.setCollectTime(cgti.getCollectTime());
                insertRequest.setCollectDate(cgti.getCollectDate());

                insertRequests.add(insertRequest);

            } catch (Exception e) {
                log.error("转换消费者组主题信息失败: cluster={}, group={}, topic={}, 错误: {}",
                        cgti.getClusterId(), cgti.getGroupId(), cgti.getTopicName(), e.getMessage(), e);
            }
        }

        if (!insertRequests.isEmpty()) {
            log.info("准备批量插入 {} 条记录", insertRequests.size());
            
            // 打印前3条数据样本
            for (int i = 0; i < Math.min(3, insertRequests.size()); i++) {
                var req = insertRequests.get(i);
                log.info("样本[{}]: cluster={}, group={}, topic={}, lags={}, collectTime={}", 
                    i, req.getClusterId(), req.getGroupId(), req.getTopicName(), 
                    req.getLags(), req.getCollectTime());
            }
            
            boolean success = consumerGroupTopicService.batchInsertConsumerGroupTopic(insertRequests);
            if (success) {
                savedCount = insertRequests.size();
                log.info("成功保存 {} 条消费者组主题数据到数据库", savedCount);
            } else {
                log.error("批量保存消费者组主题信息到数据库失败");
            }
        } else {
            log.warn("转换后的插入请求为空，无数据可保存");
        }

    } catch (Exception e) {
        log.error("批量保存消费者组主题信息时发生异常", e);
    }

    return savedCount;
}
```

## 验证修复

1. 重启应用
2. 等待任务执行（或手动触发）
3. 查看新增的详细日志
4. 根据日志定位具体问题
5. 查询数据库验证数据是否入库

```sql
SELECT COUNT(*) FROM ke_consumer_group_topic 
WHERE collect_time >= DATE_SUB(NOW(), INTERVAL 5 MINUTE);
```
