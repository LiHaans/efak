# 任务调度问题诊断方案

## 问题概述

从日志分析发现两个核心问题:
1. **任务调度冲突** - 所有定时任务都报告"正在执行中,跳过本次执行"
2. **分片结果丢失** - 汇总服务找不到各任务的分片结果

## 问题一: 任务调度冲突

### 根因分析

#### 1.1 并发控制机制
```java
// UnifiedDistributedScheduler.java:287
if (runningTasks.containsKey(task.getId())) {
    log.warn("任务 {} 正在执行中，跳过本次执行", task.getTaskName());
    return;
}
```

`runningTasks`是一个`ConcurrentHashMap<Long, Future<?>>`,用于跟踪正在执行的任务。

#### 1.2 可能原因

**原因A: 任务执行时间超过调度间隔**
- 调度间隔: 60秒 (`@Scheduled(fixedRate = 60000)`)
- 实际执行时间: 可能 > 60秒
- 结果: 下次调度时任务仍在执行

**原因B: 任务清理逻辑未执行**
```java
// UnifiedDistributedScheduler.java:318-320
finally {
    runningTasks.remove(task.getId());
}
```
如果`finally`块未执行,任务将永久留在`runningTasks`中。

**原因C: Future对象状态异常**
- `Future.isDone()`可能返回false
- 任务已完成但未从Map中移除

### 诊断步骤

#### 步骤1: 检查任务执行时长

在Redis中查询当前运行的任务:

```bash
# 连接Redis
redis-cli

# 查看任务执行历史
KEYS "efak:unified:scheduler:*"

# 查看任务锁
GET "efak:unified:scheduler:lock"
```

#### 步骤2: 检查运行中的任务

添加诊断日志,查看`runningTasks`状态:

```java
// 在UnifiedDistributedScheduler中添加
public List<Map<String, Object>> getRunningTasksDetail() {
    List<Map<String, Object>> tasks = new ArrayList<>();
    for (Map.Entry<Long, Future<?>> entry : runningTasks.entrySet()) {
        Map<String, Object> taskInfo = new HashMap<>();
        taskInfo.put("taskId", entry.getKey());
        taskInfo.put("cancelled", entry.getValue().isCancelled());
        taskInfo.put("done", entry.getValue().isDone());
        tasks.add(taskInfo);
    }
    return tasks;
}
```

#### 步骤3: 分析任务执行历史

查询数据库:

```sql
-- 查看最近的任务执行记录
SELECT 
    task_id,
    task_name,
    execution_status,
    start_time,
    end_time,
    TIMESTAMPDIFF(SECOND, start_time, end_time) as duration_seconds,
    executor_node
FROM ke_task_execution_history
WHERE start_time >= DATE_SUB(NOW(), INTERVAL 1 HOUR)
ORDER BY start_time DESC
LIMIT 50;

-- 查看正在执行的任务(没有结束时间)
SELECT 
    task_id,
    task_name,
    execution_status,
    start_time,
    TIMESTAMPDIFF(SECOND, start_time, NOW()) as running_seconds,
    executor_node
FROM ke_task_execution_history
WHERE execution_status = 'RUNNING'
    AND end_time IS NULL
ORDER BY start_time;
```

### 解决方案

#### 方案1: 增加任务超时检测

```java
// 在executeTaskWithTriggerType方法中添加超时检测
private static final long TASK_TIMEOUT_SECONDS = 300; // 5分钟超时

private void executeTaskWithTriggerType(TaskScheduler task, String triggerType) {
    if (runningTasks.containsKey(task.getId())) {
        Future<?> existingTask = runningTasks.get(task.getId());
        
        // 检查任务是否已完成但未清理
        if (existingTask.isDone() || existingTask.isCancelled()) {
            log.warn("任务 {} 已完成但未清理，强制清理", task.getTaskName());
            runningTasks.remove(task.getId());
        } else {
            log.warn("任务 {} 正在执行中，跳过本次执行", task.getTaskName());
            return;
        }
    }
    
    // 继续执行...
}
```

#### 方案2: 添加任务执行时长监控

```java
// 在recordTaskExecutionStart中记录开始时间
private final Map<Long, LocalDateTime> taskStartTimes = new ConcurrentHashMap<>();

private Long recordTaskExecutionStart(TaskScheduler task, String triggerType) {
    taskStartTimes.put(task.getId(), LocalDateTime.now());
    // 原有逻辑...
}

// 添加定期检查超时任务的方法
@Scheduled(fixedRate = 60000) // 每分钟检查一次
public void checkTimeoutTasks() {
    LocalDateTime now = LocalDateTime.now();
    List<Long> timeoutTasks = new ArrayList<>();
    
    for (Map.Entry<Long, LocalDateTime> entry : taskStartTimes.entrySet()) {
        Duration duration = Duration.between(entry.getValue(), now);
        if (duration.getSeconds() > TASK_TIMEOUT_SECONDS) {
            timeoutTasks.add(entry.getKey());
        }
    }
    
    for (Long taskId : timeoutTasks) {
        log.error("任务 {} 执行超时，强制取消", taskId);
        Future<?> future = runningTasks.get(taskId);
        if (future != null) {
            future.cancel(true);
        }
        runningTasks.remove(taskId);
        taskStartTimes.remove(taskId);
    }
}
```

#### 方案3: 优化调度间隔

修改配置,将调度间隔从60秒改为更长时间:

```yaml
# application.yml
spring:
  task:
    scheduling:
      pool:
        size: 20  # 增加线程池大小

efak:
  distributed:
    task:
      offline-timeout: 180  # 增加离线超时时间
      shard-result-wait-time: 60  # 增加分片结果等待时间
```

## 问题二: 分片结果丢失

### 根因分析

#### 2.1 分片结果保存流程

```java
// TaskExecutorManager.java:300-307
// 保存分片结果到Redis
Map<String, Object> shardResult = new HashMap<>();
shardResult.put("nodeId", taskCoordinator.getCurrentNodeId());
shardResult.put("assignedTopicCount", assignedTopicNames.size());
// ...
taskCoordinator.saveShardResult("topic_monitor", shardResult);
```

#### 2.2 分片结果汇总流程

```java
// UnifiedDistributedScheduler.java:417-459
private void aggregateShardResults(TaskScheduler task, TaskExecutionResult result) {
    // 等待其他节点完成任务
    int waitTimeSeconds = taskConfig.getShardResultWaitTime(); // 默认30秒
    
    switch (taskType) {
        case "cluster_monitor":
            aggregatedResult = shardResultAggregationService.aggregateClusterMonitorResults(waitTimeSeconds);
            break;
        // ...
    }
}
```

#### 2.3 可能原因

**原因A: 时序问题**
1. 任务在`executeTask`中通过`taskExecutorManager.executeTask(task)`执行(第299行)
2. 任务执行成功后返回`TaskExecutionResult`
3. 在`aggregateShardResults`中等待30秒
4. **但此时分片结果可能还未保存到Redis** - `saveShardResult`在任务执行方法内部调用

**原因B: Redis键值匹配失败**
```java
// DistributedTaskCoordinator.java:554-575
public Map<String, Object> getAllShardResults(String taskType) {
    String pattern = TASK_SHARD_RESULT_KEY + taskType + ":*";
    Set<String> keys = redisTemplate.keys(pattern);
    // ...
}
```

Redis键格式: `efak:task:shard:result:{taskType}:{nodeId}`
- 如果`nodeId`包含特殊字符,可能导致匹配失败
- `keys()`命令在大量键时性能问题

**原因C: 单节点环境**
- 日志显示只有一个节点在运行
- 单节点情况下,分片逻辑可能不正确

### 诊断步骤

#### 步骤1: 检查Redis中的分片结果

```bash
# 连接Redis
redis-cli

# 查看所有分片结果键
KEYS "efak:task:shard:result:*"

# 查看具体任务的分片结果
KEYS "efak:task:shard:result:topic_monitor:*"
KEYS "efak:task:shard:result:consumer_monitor:*"
KEYS "efak:task:shard:result:cluster_monitor:*"

# 查看具体键的内容
GET "efak:task:shard:result:topic_monitor:{nodeId}"

# 检查键的过期时间
TTL "efak:task:shard:result:topic_monitor:{nodeId}"
```

#### 步骤2: 检查节点注册情况

```bash
# 查看注册的服务节点
HGETALL "efak:services:registry"

# 查看心跳信息
KEYS "efak:services:heartbeat:*"

# 查看在线服务数量
HLEN "efak:services:registry"
```

#### 步骤3: 验证分片逻辑

在单节点环境下测试:

```java
// 添加调试日志
log.info("当前节点ID: {}", taskCoordinator.getCurrentNodeId());
log.info("在线服务列表: {}", taskCoordinator.getOnlineServices());
log.info("唯一在线服务数: {}", taskCoordinator.getUniqueOnlineServiceCount());
```

### 解决方案

#### 方案1: 调整分片结果保存时机

确保分片结果在汇总之前保存:

```java
// UnifiedDistributedScheduler.java:292-320
Future<?> future = schedulerExecutor.submit(() -> {
    Long executionId = null;
    try {
        // 记录任务执行开始
        executionId = recordTaskExecutionStart(task, triggerType);

        // 执行任务
        TaskExecutionResult result = taskExecutorManager.executeTask(task);
        
        // !!! 关键: 分片结果已在executeTask内部保存 !!!

        // 记录任务执行结束
        recordTaskExecutionEnd(executionId, result);

        // 等待足够时间后再汇总分片结果
        Thread.sleep(5000); // 额外等待5秒
        
        // 汇总分片结果
        aggregateShardResults(task, result);

        // 更新任务状态
        updateTaskStatus(task, result.isSuccess() ? "SUCCESS" : "FAILED");

    } catch (Exception e) {
        log.error("任务 {} 执行异常", task.getTaskName(), e);
        // ...
    } finally {
        runningTasks.remove(task.getId());
    }
});
```

#### 方案2: 增强Redis键值查询

```java
// DistributedTaskCoordinator.java
public Map<String, Object> getAllShardResults(String taskType) {
    try {
        String pattern = TASK_SHARD_RESULT_KEY + taskType + ":*";
        Set<String> keys = redisTemplate.keys(pattern);
        
        log.info("查找分片结果: pattern={}, 找到{}个键", pattern, keys != null ? keys.size() : 0);
        
        if (keys != null && !keys.isEmpty()) {
            for (String key : keys) {
                log.info("分片结果键: {}", key);
            }
        }
        
        Map<String, Object> allResults = new HashMap<>();
        if (keys != null) {
            for (String key : keys) {
                Object result = redisTemplate.opsForValue().get(key);
                if (result != null) {
                    String nodeId = key.substring(key.lastIndexOf(":") + 1);
                    allResults.put(nodeId, result);
                    log.info("获取到节点{}的分片结果", nodeId);
                }
            }
        }
        
        return allResults;
    } catch (Exception e) {
        log.error("获取所有分片任务结果异常: taskType={}", taskType, e);
        return Collections.emptyMap();
    }
}
```

#### 方案3: 单节点优化

在单节点环境下,跳过分片结果汇总:

```java
// UnifiedDistributedScheduler.java
private void aggregateShardResults(TaskScheduler task, TaskExecutionResult result) {
    try {
        // 检查是否为单节点环境
        int uniqueServiceCount = taskCoordinator.getUniqueOnlineServiceCount();
        if (uniqueServiceCount == 1) {
            log.info("单节点环境，跳过分片结果汇总");
            return;
        }
        
        // 继续原有逻辑...
    } catch (Exception e) {
        log.error("汇总任务 {} 分片结果失败", task.getTaskName(), e);
    }
}
```

## 综合诊断脚本

创建诊断脚本用于快速检查系统状态:

```bash
#!/bin/bash
# task_diagnosis.sh

echo "=== EFAK 任务调度诊断 ==="
echo ""

echo "1. 检查Redis连接"
redis-cli ping

echo ""
echo "2. 检查注册的服务节点"
redis-cli HGETALL "efak:services:registry"

echo ""
echo "3. 检查心跳信息"
redis-cli KEYS "efak:services:heartbeat:*"

echo ""
echo "4. 检查任务锁"
redis-cli GET "efak:unified:scheduler:lock"

echo ""
echo "5. 检查分片结果"
echo "topic_monitor:"
redis-cli KEYS "efak:task:shard:result:topic_monitor:*"
echo "consumer_monitor:"
redis-cli KEYS "efak:task:shard:result:consumer_monitor:*"
echo "cluster_monitor:"
redis-cli KEYS "efak:task:shard:result:cluster_monitor:*"
echo "alert_monitor:"
redis-cli KEYS "efak:task:shard:result:alert_monitor:*"

echo ""
echo "6. 检查任务执行历史(最近10条)"
mysql -u用户名 -p密码 -D数据库名 -e "
SELECT 
    task_name,
    execution_status,
    start_time,
    end_time,
    TIMESTAMPDIFF(SECOND, start_time, end_time) as duration,
    executor_node
FROM ke_task_execution_history
ORDER BY start_time DESC
LIMIT 10;
"

echo ""
echo "=== 诊断完成 ==="
```

## 配置优化建议

```yaml
# application.yml

# 分布式任务配置
efak:
  distributed:
    task:
      # 节点离线超时时间(秒) - 建议至少2倍于任务执行时间
      offline-timeout: 300
      
      # 分片结果等待时间(秒) - 建议根据任务复杂度调整
      shard-result-wait-time: 60
      
      # 分片结果过期时间(分钟)
      shard-result-expire-minutes: 15

# Spring任务调度配置
spring:
  task:
    scheduling:
      pool:
        # 增加线程池大小以支持并发任务
        size: 20
      # 任务执行器配置
      execution:
        pool:
          core-size: 10
          max-size: 20
          queue-capacity: 100
```

## 监控建议

1. **添加Prometheus指标**
   - 任务执行时长
   - 任务失败率
   - runningTasks大小
   - 分片结果数量

2. **添加告警规则**
   - 任务执行时间超过阈值
   - 连续任务失败
   - runningTasks长时间不为空

3. **日志级别调整**
   ```yaml
   logging:
     level:
       org.kafka.eagle.web.scheduler: DEBUG
       org.kafka.eagle.web.service.ShardResultAggregationService: DEBUG
       org.kafka.eagle.web.service.TaskExecutorManager: DEBUG
   ```

## 后续优化方向

1. **引入任务状态机**
   - PENDING -> RUNNING -> SUCCESS/FAILED
   - 更严格的状态转换控制

2. **改进分片结果存储**
   - 使用Redis Hash代替多个String键
   - 减少`keys()`命令使用,改用`SCAN`

3. **添加任务重试机制**
   - 失败任务自动重试
   - 指数退避策略

4. **分离任务执行和结果汇总**
   - 任务执行完成立即释放资源
   - 独立线程负责结果汇总
