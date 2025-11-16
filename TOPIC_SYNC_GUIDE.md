# Topic同步功能使用指南

## 问题说明

之前主题管理页面只能看到系统数据库中已创建的topic信息，无法查看Kafka集群中的所有topic。

## 解决方案

已实现从Kafka集群同步所有topic到数据库的功能。

## 已修复的问题

### 1. TopicMapper countTopic方法缺少clusterId过滤

**问题**: `countTopic` 方法没有按 `clusterId` 过滤，导致查询结果不准确。

**修复**: 在 `TopicMapper.java` 的 `countTopic` 方法中添加了 `clusterId` 过滤条件：

```java
"<if test='request.clusterId != null and request.clusterId != &quot;&quot;'>" +
"AND cluster_id = #{request.clusterId}" +
"</if>"
```

### 2. 新增Topic同步功能

**新增功能**:
- 从Kafka集群实时获取所有topic
- 自动同步topic到数据库（新增或更新）
- 批量获取topic元数据（分区数、副本数、保留时间等）

## 功能特性

### 1. 同步API

**接口地址**: `POST /topic/api/sync`

**请求参数**:
- `clusterId` (必填): 集群ID

**请求示例**:
```bash
curl -X POST "http://localhost:28888/topic/api/sync?clusterId=your_cluster_id"
```

**响应示例**:
```json
{
  "success": true,
  "message": "同步完成",
  "added": 5,
  "updated": 3,
  "total": 8,
  "errors": 0,
  "errorDetails": []
}
```

### 2. 同步逻辑

1. **获取Kafka集群信息**: 通过 `clusterId` 获取broker连接信息
2. **列出所有Topic**: 调用 `KafkaSchemaFactory.listTopicNames()` 获取Kafka中的所有topic（排除内部topic）
3. **批量获取元数据**: 调用 `KafkaSchemaFactory.getTopicMetaData()` 批量获取topic详细信息：
   - 分区数
   - 副本数
   - 保留时间（retention.ms）
   - Broker分布（broker_spread）
   - Broker偏斜（broker_skewed）
   - Leader偏斜（leader_skewed）
4. **数据库同步**:
   - 如果topic不存在：插入新记录
   - 如果topic已存在：比较并更新变化的字段
5. **返回同步结果**: 包含新增、更新、错误统计

### 3. 核心代码文件

#### 修改的文件：

1. **TopicMapper.java**
   - 修复 `countTopic` 方法的 `clusterId` 过滤

2. **TopicService.java**
   - 新增接口方法 `syncTopicsFromKafka(String clusterId)`

3. **TopicServiceImpl.java**
   - 实现 `syncTopicsFromKafka` 方法
   - 使用 `@Transactional` 保证事务一致性

4. **TopicController.java**
   - 新增REST接口 `POST /topic/api/sync`

## 使用方式

### 方式一：手动调用API

在主题管理页面添加"同步Topic"按钮，点击时调用同步API：

```javascript
// 前端调用示例
function syncTopics(clusterId) {
    fetch(`/topic/api/sync?clusterId=${clusterId}`, {
        method: 'POST',
        headers: {
            'Content-Type': 'application/json'
        }
    })
    .then(response => response.json())
    .then(data => {
        if (data.success) {
            alert(`同步成功！新增: ${data.added}, 更新: ${data.updated}, 总数: ${data.total}`);
            // 刷新页面列表
            location.reload();
        } else {
            alert(`同步失败: ${data.message}`);
        }
    })
    .catch(error => {
        console.error('同步失败:', error);
        alert('同步失败，请查看控制台日志');
    });
}
```

### 方式二：定时任务自动同步

可以配置定时任务定期同步：

```java
@Scheduled(cron = "0 */30 * * * ?") // 每30分钟执行一次
public void autoSyncTopics() {
    // 获取所有集群ID
    List<String> clusterIds = getClusterIds();
    
    for (String clusterId : clusterIds) {
        try {
            topicService.syncTopicsFromKafka(clusterId);
            log.info("集群 {} topic自动同步完成", clusterId);
        } catch (Exception e) {
            log.error("集群 {} topic自动同步失败", clusterId, e);
        }
    }
}
```

## 注意事项

1. **集群ID必填**: 调用同步API时必须提供有效的 `clusterId`

2. **Broker连接**: 确保数据库中存在该集群的broker信息，否则无法连接Kafka

3. **内部Topic**: 系统会自动排除内部topic（如 `__consumer_offsets`）

4. **事务处理**: 同步过程使用事务，确保数据一致性

5. **错误处理**: 
   - 单个topic同步失败不会影响其他topic
   - 错误信息会在返回结果的 `errorDetails` 中详细列出

6. **性能考虑**: 
   - 使用批量获取元数据的方式提高性能
   - 只更新变化的字段，减少不必要的数据库操作

7. **创建人**: 自动同步的topic创建人和更新人为 "system"

## 测试步骤

1. 启动EFAK应用
2. 在Kafka集群中创建几个测试topic
3. 调用同步API：
   ```bash
   curl -X POST "http://localhost:28888/topic/api/sync?clusterId=your_cluster_id"
   ```
4. 查看返回结果，确认新增topic数量
5. 刷新主题管理页面，验证topic是否显示

## 日志查看

同步过程会输出详细日志：

```
[INFO] 开始同步集群 cluster-1 的 10 个topic
[DEBUG] 新增topic: test-topic-1
[DEBUG] 更新topic: test-topic-2
[INFO] 集群 cluster-1 topic同步完成：新增 5, 更新 3, 总数 10, 错误 0
```

出现错误时：

```
[ERROR] 同步topic test-topic-3 失败：Unable to get metadata
[ERROR] 同步集群 cluster-1 的topic失败：Connection refused
```

## 相关文件清单

- `efak-web/src/main/java/org/kafka/eagle/web/mapper/TopicMapper.java` (修改)
- `efak-web/src/main/java/org/kafka/eagle/web/service/TopicService.java` (修改)
- `efak-web/src/main/java/org/kafka/eagle/web/service/impl/TopicServiceImpl.java` (修改)
- `efak-web/src/main/java/org/kafka/eagle/web/controller/TopicController.java` (修改)
- `efak-core/src/main/java/org/kafka/eagle/core/api/KafkaSchemaFactory.java` (使用)

## 后续优化建议

1. **前端UI集成**: 在主题管理页面添加"同步Topic"按钮
2. **定时同步**: 配置定时任务自动同步
3. **同步状态提示**: 显示同步进度和结果
4. **权限控制**: 限制只有管理员可以执行同步操作
5. **同步历史**: 记录每次同步的历史记录
6. **增量同步**: 优化为只同步变化的topic

---

更新时间: 2025-11-16
