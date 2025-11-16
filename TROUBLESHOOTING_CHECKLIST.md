# 消费者组监控任务无数据问题排查清单

## 当前情况
- ✅ 任务日志显示"执行完成"
- ❌ ke_consumer_group_topic 表最近5分钟无新数据

## 排查步骤

### 第1步：检查应用是否已重启并加载新代码
```bash
# 检查应用进程
jps -l | grep efak

# 或者查看进程启动时间
ps -ef | grep efak
```

**预期**：应用在添加日志代码后已重启

---

### 第2步：执行诊断SQL（在数据库客户端中执行）

使用提供的 `diagnosis_consumer_monitor.sql` 文件，关注以下结果：

#### 2.1 任务是否启用？
```sql
SELECT enabled, task_name FROM ke_task_scheduler 
WHERE task_type = 'consumer_monitor';
```
- ✅ enabled = 1（启用）
- ❌ enabled = 0（禁用）→ 需要启用

#### 2.2 任务最后执行时间？
```sql
SELECT last_execute_time, next_execute_time 
FROM ke_task_scheduler 
WHERE task_type = 'consumer_monitor';
```
- 检查 `last_execute_time` 是否在最近5分钟内
- 检查 `next_execute_time` 是否合理

#### 2.3 任务执行历史
```sql
SELECT execution_status, start_time, end_time, result_message, error_message
FROM ke_task_execution_history 
WHERE task_type = 'consumer_monitor'
ORDER BY start_time DESC LIMIT 3;
```

**关键问题**：
- execution_status 是否为 'SUCCESS'？
- result_message 中是否显示 "保存X条记录"？
- 如果 savedCount=0，说明数据采集为空

---

### 第3步：检查是否有Kafka集群配置

```sql
SELECT cluster_id, name, total_nodes, online_nodes 
FROM ke_cluster;
```

**问题诊断**：
- ❌ 结果为空 → **没有配置Kafka集群**（这是最可能的原因）
- ❌ online_nodes = 0 → Kafka集群离线
- ✅ 有集群且online → 继续下一步

---

### 第4步：检查Broker配置

```sql
SELECT cluster_id, COUNT(*) as broker_count
FROM ke_broker_info
GROUP BY cluster_id;
```

**问题诊断**：
- ❌ 结果为空 → **没有配置Broker信息**
- ❌ 所有broker status='offline' → 连接失败
- ✅ 有broker且online → 继续下一步

---

### 第5步：查看应用日志（重启后）

查找关键日志：

```bash
# 在日志文件中搜索
grep -E "消费者组监控|saveConsumerGroup|准备批量插入" logs/efak.log

# 或者在 IDEA 控制台查看
```

**期望看到的日志**：
```
[INFO] 开始执行消费者监控任务 (分片模式)
[INFO] 从数据库获取到 X 个集群
[INFO] 总共 X 个消费者组，当前节点分配到 X 个
[INFO] 开始保存消费者组数据到数据库，数量: X
[INFO] 准备批量插入 X 条记录
[INFO] 样本[0]: cluster=xxx, group=xxx, topic=xxx
[INFO] 执行批量插入，数据量: X
[INFO] 批量插入结果: 影响行数=X
[INFO] 成功保存 X 条消费者组主题数据到数据库
```

**问题诊断日志**：

| 日志内容 | 问题原因 |
|---------|---------|
| "从数据库获取到 0 个集群" | 没有配置Kafka集群 |
| "总共 0 个消费者组" | Kafka没有消费者组 |
| "当前节点分配到 0 个" | 分片问题，节点未分配到任务 |
| "消费者组主题信息为空" | 数据采集失败 |
| "转换后的插入请求为空" | 数据转换失败 |
| "批量插入结果: 影响行数=0" | 数据库插入失败 |
| 出现异常堆栈 | 代码执行异常 |

---

### 第6步：手动触发任务测试

如果任务cron表达式间隔太长，可以：

**方法1：通过API手动触发**
```bash
curl -X POST http://localhost:8080/api/tasks/{taskId}/trigger
```

**方法2：修改为每分钟执行（临时测试）**
```sql
UPDATE ke_task_scheduler 
SET cron_expression = '0 * * * * ?'
WHERE task_type = 'consumer_monitor';
```

等待1分钟，观察日志和数据库。

---

## 常见问题及解决方案

### 问题1：没有配置Kafka集群 ⭐最常见⭐

**症状**：
- ke_cluster 表为空
- 日志显示 "从数据库获取到 0 个集群"

**解决方案**：
1. 通过Web界面添加Kafka集群
2. 或执行SQL添加测试集群：
```sql
INSERT INTO ke_cluster (cluster_id, name, cluster_type, auth, total_nodes, online_nodes, created_at, updated_at)
VALUES ('test-cluster', 'Test Kafka Cluster', 'kafka', 'N', 3, 3, NOW(), NOW());

INSERT INTO ke_broker_info (cluster_id, broker_id, host_ip, port, jmx_port, status, created_by)
VALUES 
('test-cluster', 1, 'localhost', 9092, 9999, 'online', 'admin'),
('test-cluster', 2, 'localhost', 9093, 9999, 'online', 'admin'),
('test-cluster', 3, 'localhost', 9094, 9999, 'online', 'admin');
```

---

### 问题2：Kafka没有消费者组

**症状**：
- 集群已配置
- 日志显示 "总共 0 个消费者组"
- savedCount = 0

**原因**：Kafka集群确实没有活跃的消费者组

**解决方案**：
1. 在Kafka上启动一个测试消费者
2. 或者这是正常情况（等待有消费者时数据才会出现）

---

### 问题3：连接池导致连接失败

**症状**：
- 日志中有 "连接失败" 或 "超时" 错误
- 或日志显示 "连接健康检查失败"

**解决方案**：
```sql
-- 检查broker连接信息是否正确
SELECT cluster_id, broker_id, host_ip, port 
FROM ke_broker_info;
```

确认host_ip和port可以从应用服务器访问。

---

### 问题4：应用未重启，新日志代码未生效

**症状**：看不到新增的详细日志

**解决方案**：
1. 重新编译：`mvn clean package -DskipTests`
2. 重启应用

---

### 问题5：分片导致当前节点未分配任务

**症状**：
- 日志显示 "当前节点分配到 0 个消费者组"
- savedCount = 0

**原因**：在多节点部署时，当前节点没有分配到任务

**解决方案**：
- 检查其他节点的日志
- 或者临时禁用分片（仅测试用）

---

## 快速修复建议

**立即执行以下SQL，获取诊断信息：**

```sql
-- 一键诊断查询
SELECT 
    '集群配置' as check_item,
    COUNT(*) as result 
FROM ke_cluster
UNION ALL
SELECT 
    'Broker配置',
    COUNT(*) 
FROM ke_broker_info
UNION ALL
SELECT 
    '消费者组监控任务',
    COUNT(*) 
FROM ke_task_scheduler 
WHERE task_type = 'consumer_monitor' AND enabled = 1
UNION ALL
SELECT 
    '最近执行记录',
    COUNT(*) 
FROM ke_task_execution_history 
WHERE task_type = 'consumer_monitor' 
  AND start_time >= DATE_SUB(NOW(), INTERVAL 1 HOUR)
UNION ALL
SELECT 
    '今日数据量',
    COUNT(*) 
FROM ke_consumer_group_topic 
WHERE collect_date = CURDATE();
```

**根据结果判断：**
- 集群配置 = 0 → **需要先配置Kafka集群**
- 消费者组监控任务 = 0 → **需要启用任务**
- 最近执行记录 = 0 → **任务未执行或cron间隔太长**
- 今日数据量 = 0 且前面都正常 → **需要查看应用日志**

---

## 需要的信息

请提供以下信息以便进一步诊断：

1. ✅ **执行 diagnosis_consumer_monitor.sql 的结果**（前3个查询）
2. ✅ **ke_cluster 表的记录数**
3. ✅ **应用是否已重启**（编译时间戳）
4. ✅ **最新的应用日志**（包含"消费者组"关键字的行）

---

## 提交诊断信息

执行以下命令生成诊断报告：

```bash
# 如果应用正在运行，获取最新100行日志
tail -n 100 logs/efak.log > consumer_monitor_diagnosis.log

# 或从IDEA控制台复制最近的日志
```

然后提供：
1. consumer_monitor_diagnosis.log
2. diagnosis_consumer_monitor.sql 的执行结果
