# 主题详情页面消费者组查询问题修复指南

## 问题描述

在主题详情页面，消费者组信息显示为空，即使Kafka集群中存在消费者组。

## 根本原因

原始的Mapper SQL查询使用了 `CURDATE()` 函数来限制只查询当天的消费者组数据：

```sql
SELECT ... 
FROM ke_consumer_group_topic 
WHERE cluster_id = #{clusterId} AND topic_name = #{topicName} 
AND collect_date = CURDATE()
```

**问题分析**：
1. `ke_consumer_group_topic` 表是通过定时任务采集消费者组数据填充的
2. 如果今天还没有运行采集任务，或者采集任务还未执行，表中就不会有当天的数据
3. 这导致查询结果为空，前端显示"暂无消费者组信息"

## 解决方案

修改 `ConsumerGroupTopicMapper.java` 中的两个SQL查询，去掉日期限制：

### 修改1: `getConsumerGroupsByTopicForDetail` 方法（第508-526行）

**修改前**：
```java
@Select("SELECT " +
        "t1.group_id, " +
        "t1.topic_name, " +
        "t1.state, " +
        "t1.logsize, " +
        "t1.offsets, " +
        "t1.lags, " +
        "t1.collect_time " +
        "FROM ke_consumer_group_topic t1 " +
        "INNER JOIN ( " +
        "  SELECT group_id, MAX(collect_time) as max_time " +
        "  FROM ke_consumer_group_topic " +
        "  WHERE cluster_id = #{clusterId} AND topic_name = #{topicName} " +
        "  AND collect_date = CURDATE() " +  // ❌ 限制了只查询当天数据
        "  GROUP BY group_id " +
        ") t2 ON t1.group_id = t2.group_id AND t1.collect_time = t2.max_time " +
        "WHERE t1.cluster_id = #{clusterId} AND t1.topic_name = #{topicName} " +
        "ORDER BY t1.group_id " +
        "LIMIT #{offset}, #{limit}")
```

**修改后**：
```java
@Select("SELECT " +
        "t1.group_id, " +
        "t1.topic_name, " +
        "t1.state, " +
        "t1.logsize, " +
        "t1.offsets, " +
        "t1.lags, " +
        "t1.collect_time " +
        "FROM ke_consumer_group_topic t1 " +
        "INNER JOIN ( " +
        "  SELECT group_id, MAX(collect_time) as max_time " +
        "  FROM ke_consumer_group_topic " +
        "  WHERE cluster_id = #{clusterId} AND topic_name = #{topicName} " +
        // ✅ 移除日期限制，查询所有历史数据中的最新记录
        "  GROUP BY group_id " +
        ") t2 ON t1.group_id = t2.group_id AND t1.collect_time = t2.max_time " +
        "WHERE t1.cluster_id = #{clusterId} AND t1.topic_name = #{topicName} " +
        "ORDER BY t1.group_id " +
        "LIMIT #{offset}, #{limit}")
```

### 修改2: `countConsumerGroupsByTopicForDetail` 方法（第539-542行）

**修改前**：
```java
@Select("SELECT COUNT(DISTINCT group_id) " +
        "FROM ke_consumer_group_topic " +
        "WHERE cluster_id = #{clusterId} AND topic_name = #{topicName} " +
        "AND collect_date = CURDATE()")  // ❌ 限制了只统计当天数据
```

**修改后**：
```java
@Select("SELECT COUNT(DISTINCT group_id) " +
        "FROM ke_consumer_group_topic " +
        "WHERE cluster_id = #{clusterId} AND topic_name = #{topicName}")
        // ✅ 移除日期限制，统计所有历史数据中的不重复group_id
```

## 修改优点

1. **兼容性更好**：无论采集任务是否运行，都能显示消费者组数据
2. **查询逻辑更合理**：通过 `MAX(collect_time)` 自动获取每个消费者组的最新状态
3. **性能不受影响**：仍然使用索引 `idx_cluster_topic_date` 和 `idx_cluster_group_topic`
4. **用户体验提升**：即使是历史数据，也比显示空白更有价值

## 数据采集说明

虽然修改后可以查询历史数据，但建议配置定时任务定期采集消费者组数据：

### 采集任务配置

在 `TaskExecutorManager` 中应该配置如下定时任务：

```java
// 每5分钟采集一次消费者组数据
@Scheduled(cron = "0 */5 * * * ?")
public void collectConsumerGroupData() {
    // 采集逻辑
    List<String> clusterIds = clusterService.getAllClusterIds();
    for (String clusterId : clusterIds) {
        // 调用Kafka API获取消费者组信息
        // 保存到ke_consumer_group_topic表
    }
}
```

### 数据清理建议

由于不再依赖当天数据，建议配置数据保留策略：

```sql
-- 保留最近7天的消费者组数据
DELETE FROM ke_consumer_group_topic 
WHERE collect_date < DATE_SUB(CURDATE(), INTERVAL 7 DAY);
```

## 测试验证

### 场景1：有历史数据但无当天数据
- ✅ 应该显示最新的历史消费者组记录
- ✅ `collect_time` 字段显示数据的实际采集时间

### 场景2：有当天数据
- ✅ 应该显示今天最新的消费者组记录
- ✅ 数据与Kafka实时状态保持一致

### 场景3：完全无数据
- ✅ 应该显示"暂无消费者组信息"

## 相关文件

- `efak-web/src/main/java/org/kafka/eagle/web/mapper/ConsumerGroupTopicMapper.java` - Mapper接口
- `efak-web/src/main/java/org/kafka/eagle/web/service/impl/TopicServiceImpl.java` - 服务实现（调用Mapper）
- `efak-web/src/main/java/org/kafka/eagle/web/controller/TopicController.java` - API接口（第326-352行）
- `efak-web/src/main/resources/statics/js/system/topic-detail.js` - 前端JS（第123-147行）

## Git提交信息

```
fix: 修复主题详情页面消费者组查询问题

- 移除ConsumerGroupTopicMapper中查询消费者组的CURDATE()日期限制
- 改为查询所有历史数据中每个group的最新记录
- 解决消费者组数据未采集时页面显示为空的问题
```

## 总结

该修复确保了主题详情页面的消费者组信息能够正常显示，提升了系统的容错性和用户体验。同时保持了查询性能和数据的时效性。
