# 消费者组数据采集配置指南

## 采集周期说明

消费者组信息的采集由定时任务完成，默认配置如下：

### 默认配置
- **任务名称**：消费者组监控任务
- **任务类型**：`consumer_monitor`
- **Cron表达式**：`0 */1 * * * ?`
- **采集周期**：**每1分钟执行一次**
- **默认状态**：`disabled`（禁用）
- **超时时间**：300秒（5分钟）

## 启用采集任务

### 方法1：通过数据库直接启用

```sql
-- 启用消费者组监控任务
UPDATE ke_task_scheduler 
SET status = 'enabled' 
WHERE task_type = 'consumer_monitor';
```

### 方法2：通过系统管理界面启用

1. 登录EFAK系统
2. 进入 **系统管理** → **任务调度** 页面
3. 找到"消费者组监控任务"
4. 点击"启用"按钮

## 修改采集周期

如果需要修改采集周期，可以修改Cron表达式：

### 常用Cron表达式示例

| 采集周期 | Cron表达式 | 说明 |
|---------|-----------|------|
| 每30秒 | `*/30 * * * * ?` | 高频采集，数据最实时 |
| 每1分钟（默认） | `0 */1 * * * ?` | 平衡实时性和性能 |
| 每2分钟 | `0 */2 * * * ?` | 降低系统负载 |
| 每5分钟 | `0 */5 * * * ?` | 适合大规模集群 |
| 每10分钟 | `0 */10 * * * ?` | 低频采集，减少开销 |

### 修改SQL示例

```sql
-- 修改为每2分钟采集一次
UPDATE ke_task_scheduler 
SET cron_expression = '0 */2 * * * ?' 
WHERE task_type = 'consumer_monitor';

-- 修改为每5分钟采集一次
UPDATE ke_task_scheduler 
SET cron_expression = '0 */5 * * * ?' 
WHERE task_type = 'consumer_monitor';
```

## 采集任务工作原理

### 采集流程

```
1. 定时任务触发（根据Cron表达式）
   ↓
2. 获取所有集群ID列表
   ↓
3. 遍历每个集群，调用Kafka API获取消费者组信息
   ↓
4. 对每个消费者组，获取以下信息：
   - group_id: 消费者组ID
   - topic_name: 订阅的主题名称
   - state: 消费者组状态（STABLE, EMPTY, DEAD等）
   - logsize: 主题总日志大小
   - offsets: 当前消费位移
   - lags: 消费延迟（logsize - offsets）
   ↓
5. 批量写入ke_consumer_group_topic表
   - cluster_id: 集群ID
   - collect_time: 采集时间戳
   - collect_date: 采集日期（格式：YYYY-MM-DD）
```

### 数据存储

采集的数据存储在 `ke_consumer_group_topic` 表中：

```sql
SELECT 
    cluster_id,
    group_id,
    topic_name,
    state,
    logsize,
    offsets,
    lags,
    collect_time,
    collect_date
FROM ke_consumer_group_topic
ORDER BY collect_time DESC
LIMIT 10;
```

## 数据清理建议

为避免数据过度膨胀，建议配置数据保留策略：

### 保留7天的数据

```sql
-- 手动清理7天前的数据
DELETE FROM ke_consumer_group_topic 
WHERE collect_date < DATE_SUB(CURDATE(), INTERVAL 7 DAY);
```

### 自动清理（通过定时任务）

系统已经内置了"数据清理任务"（`data_cleanup`），可以配置为自动清理历史数据：

```sql
-- 启用数据清理任务
UPDATE ke_task_scheduler 
SET status = 'enabled',
    cron_expression = '0 0 2 * * ?' -- 每天凌晨2点执行
WHERE task_type = 'data_cleanup';
```

## 性能优化建议

### 1. 采集周期调优

- **小规模集群（< 10个消费者组）**：可以设置为每30秒或1分钟
- **中等规模集群（10-50个消费者组）**：建议1-2分钟
- **大规模集群（> 50个消费者组）**：建议3-5分钟

### 2. 数据库索引优化

确保以下索引存在：

```sql
-- 查询最新数据的索引
CREATE INDEX idx_cluster_topic_date 
ON ke_consumer_group_topic(cluster_id, topic_name, collect_date);

-- 时间范围查询索引
CREATE INDEX idx_cluster_time_covering 
ON ke_consumer_group_topic(cluster_id, collect_time, group_id, topic_name);

-- 分组统计索引
CREATE INDEX idx_cluster_group_topic_date 
ON ke_consumer_group_topic(cluster_id, group_id, topic_name, collect_date);
```

### 3. 数据保留策略

| 数据量级 | 建议保留天数 | 说明 |
|---------|------------|------|
| 小（< 1000条/天） | 30天 | 可保留更长时间用于分析 |
| 中（1000-10000条/天） | 7-14天 | 平衡存储和查询性能 |
| 大（> 10000条/天） | 3-7天 | 定期清理避免性能下降 |

## 监控采集任务状态

### 查看任务执行历史

```sql
-- 查看最近10次执行记录
SELECT 
    task_name,
    execution_status,
    start_time,
    end_time,
    duration,
    result_message,
    error_message
FROM ke_task_execution_history
WHERE task_type = 'consumer_monitor'
ORDER BY start_time DESC
LIMIT 10;
```

### 查看任务统计信息

```sql
-- 查看任务统计
SELECT 
    task_name,
    status,
    cron_expression,
    last_execute_time,
    next_execute_time,
    execute_count,
    success_count,
    fail_count,
    last_execute_result
FROM ke_task_scheduler
WHERE task_type = 'consumer_monitor';
```

## 验证采集是否正常

### 1. 检查是否有新数据写入

```sql
-- 查看最近1小时的采集数据
SELECT 
    COUNT(*) as record_count,
    COUNT(DISTINCT group_id) as group_count,
    COUNT(DISTINCT topic_name) as topic_count,
    MIN(collect_time) as first_collect,
    MAX(collect_time) as last_collect
FROM ke_consumer_group_topic
WHERE collect_time >= DATE_SUB(NOW(), INTERVAL 1 HOUR);
```

### 2. 查看各集群采集情况

```sql
-- 按集群统计采集数据
SELECT 
    cluster_id,
    COUNT(*) as record_count,
    COUNT(DISTINCT group_id) as group_count,
    MAX(collect_time) as last_collect
FROM ke_consumer_group_topic
WHERE collect_date = CURDATE()
GROUP BY cluster_id;
```

### 3. 检查数据采集间隔

```sql
-- 查看最近5次采集的时间间隔
SELECT 
    collect_time,
    TIMESTAMPDIFF(SECOND, 
        LAG(collect_time) OVER (ORDER BY collect_time), 
        collect_time
    ) as interval_seconds
FROM (
    SELECT DISTINCT collect_time 
    FROM ke_consumer_group_topic 
    ORDER BY collect_time DESC 
    LIMIT 5
) t
ORDER BY collect_time DESC;
```

预期结果应该接近60秒（如果是每1分钟采集）。

## 故障排查

### 问题1：采集任务已启用但没有数据

**可能原因**：
1. 调度器未启动
2. Redis连接失败
3. Kafka连接失败
4. 任务执行报错

**排查步骤**：
```sql
-- 1. 检查任务状态
SELECT * FROM ke_task_scheduler WHERE task_type = 'consumer_monitor';

-- 2. 检查执行历史
SELECT * FROM ke_task_execution_history 
WHERE task_type = 'consumer_monitor' 
ORDER BY start_time DESC LIMIT 5;

-- 3. 查看应用日志
-- 搜索关键词：consumer_monitor, ConsumerMonitorExecutor
```

### 问题2：数据采集延迟很大

**可能原因**：
1. 消费者组数量过多
2. Kafka响应慢
3. 数据库写入慢
4. 超时时间设置不合理

**解决方案**：
```sql
-- 增加超时时间
UPDATE ke_task_scheduler 
SET timeout = 600  -- 改为10分钟
WHERE task_type = 'consumer_monitor';

-- 或者增加采集间隔
UPDATE ke_task_scheduler 
SET cron_expression = '0 */3 * * * ?'  -- 改为每3分钟
WHERE task_type = 'consumer_monitor';
```

### 问题3：主题详情页面仍然显示空

**可能原因**：
1. 数据库中确实没有该主题的消费者组数据
2. 该主题没有消费者组订阅

**验证方法**：
```sql
-- 检查特定主题的消费者组数据
SELECT * FROM ke_consumer_group_topic 
WHERE topic_name = 'your_topic_name' 
ORDER BY collect_time DESC;

-- 如果查询结果为空，说明：
-- 1. 该主题确实没有消费者组
-- 2. 或者采集任务还未采集到该主题的消费者组
```

## 相关配置文件

- **任务调度器配置**：`UnifiedDistributedScheduler.java`
- **任务执行器**：`TaskExecutorManager.java`
- **消费者组采集实现**：需查看具体的`ConsumerMonitorExecutor`实现
- **数据库初始化脚本**：`efak-web/src/main/resources/sql/ke.sql`（第382-411行）

## 总结

1. **默认采集周期**：每1分钟一次
2. **默认状态**：禁用，需要手动启用
3. **数据保留**：建议保留7-30天
4. **性能优化**：根据集群规模调整采集周期
5. **监控验证**：定期检查采集任务执行状态和数据更新情况

启用采集任务后，等待1-2分钟，主题详情页面应该就能看到消费者组信息了。
