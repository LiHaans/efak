# 深度诊断实施指南

## 已完成的工作

### 1. 诊断工具创建
- ✅ `TaskDiagnosticsController` - 提供线程堆栈、死锁检测、阻塞分析等REST接口
- ✅ `TimeoutWrapper` - Kafka操作超时包装器
- ✅ `task_diagnosis.ps1` - PowerShell诊断脚本
- ✅ 增强日志记录 - 在`TaskExecutorManager`中添加详细执行跟踪

### 2. 超时控制
- ✅ 为`KafkaSchemaFactory.listTopicNames()`添加30秒超时
- ✅ 超时时自动跳过该集群，继续处理其他集群

## 下一步行动计划

### 步骤1: 编译并重启应用（必须）

```bash
# 在项目根目录
cd D:\lihang\code\EFAK

# 编译项目
mvn clean package -DskipTests

# 停止当前运行的应用
# (使用任务管理器或者kill进程)

# 启动应用
java -jar efak-web/target/efak-web-*.jar
```

### 步骤2: 实时诊断（应用启动后立即执行）

#### 方式1: 使用PowerShell脚本

```powershell
# 进入scripts目录
cd D:\lihang\code\EFAK\scripts

# 执行完整诊断
.\task_diagnosis.ps1 -Host localhost -Port 8080 -Action all

# 或者只查看阻塞线程
.\task_diagnosis.ps1 -Action blocked

# 或者只检查死锁
.\task_diagnosis.ps1 -Action deadlock
```

#### 方式2: 直接调用REST API

```bash
# 查看运行中的任务
curl http://localhost:8080/api/diagnostics/running-tasks

# 查看阻塞的线程（重点）
curl http://localhost:8080/api/diagnostics/blocked-threads

# 检测死锁
curl http://localhost:8080/api/diagnostics/deadlocks

# 查看线程池状态
curl http://localhost:8080/api/diagnostics/thread-pool

# 获取完整线程堆栈
curl http://localhost:8080/api/diagnostics/threads
```

### 步骤3: 观察增强日志

查看应用日志，关注以下标记:

```
[Topic监控] 步骤2.4: 集群XXX开始调用listTopicNames - 可能阻塞点!!!
```

如果看到这条日志后很长时间没有后续日志，说明确实阻塞在`listTopicNames`。

**预期的正常日志流程**:
```
[Topic监控] 开始执行任务, taskId=1
[Topic监控] 步骤1: 获取集群信息
[Topic监控] 步骤1完成: 获取到1个集群, 耗时50ms
[Topic监控] 步骤2: 开始获取所有主题名称
[Topic监控] 步骤2.1: 处理集群cluster-1
[Topic监控] 步骤2.2: 集群cluster-1获取到3个broker
[Topic监控] 步骤2.3: 集群cluster-1构建KafkaClientInfo
[Topic监控] 步骤2.4: 集群cluster-1开始调用listTopicNames - 可能阻塞点!!!
[Topic监控] 步骤2.5: 集群cluster-1获取到10个主题, 耗时1200ms
```

**如果阻塞，日志会停在**:
```
[Topic监控] 步骤2.4: 集群cluster-1开始调用listTopicNames - 可能阻塞点!!!
(然后30秒后)
ERROR - 获取集群 'cluster-1' 的主题列表超时（>30s）
```

### 步骤4: 根据诊断结果分析

#### 场景A: 线程阻塞在`consumer.listTopics()`

**症状**:
- 阻塞线程堆栈显示卡在`KafkaConsumer.listTopics()`
- 日志显示30秒超时

**可能原因**:
1. Kafka集群不可达（网络问题）
2. Kafka集群过载响应慢
3. Kafka客户端配置问题（request.timeout.ms太长）

**解决方案**:
```yaml
# 在application.yml中添加Kafka客户端超时配置
kafka:
  consumer:
    properties:
      request.timeout.ms: 15000  # 15秒
      session.timeout.ms: 10000  # 10秒
      max.poll.interval.ms: 20000  # 20秒
```

#### 场景B: 线程阻塞在数据库操作

**症状**:
- 堆栈显示卡在数据库相关调用
- 例如: `brokerMapper.getBrokersByClusterId()`

**可能原因**:
1. 数据库连接池耗尽
2. 数据库慢查询
3. 数据库锁等待

**解决方案**:
```yaml
# 优化数据库连接池
spring:
  datasource:
    hikari:
      maximum-pool-size: 20
      minimum-idle: 10
      connection-timeout: 10000  # 10秒
      idle-timeout: 300000
      max-lifetime: 600000
```

检查慢查询:
```sql
-- MySQL
SHOW FULL PROCESSLIST;

-- 查看锁等待
SELECT * FROM information_schema.innodb_locks;
SELECT * FROM information_schema.innodb_lock_waits;
```

#### 场景C: 线程池耗尽

**症状**:
- `/api/diagnostics/thread-pool`显示大量WAITING或TIMED_WAITING状态线程
- `runningTasks`数量等于线程池大小(10)

**解决方案**:
```java
// 在UnifiedDistributedScheduler中修改线程池大小
private final ScheduledExecutorService schedulerExecutor = 
    Executors.newScheduledThreadPool(20);  // 从10增加到20
```

#### 场景D: 死锁

**症状**:
- `/api/diagnostics/deadlocks`返回`hasDeadlock: true`

**解决方案**:
根据死锁堆栈分析具体的锁竞争情况，可能需要调整代码逻辑。

### 步骤5: 临时修复 - 清理卡住的任务

如果诊断确认任务确实卡住了，可以执行以下SQL清理:

```sql
-- 查看RUNNING状态的任务
SELECT * FROM ke_task_execution_history 
WHERE execution_status = 'RUNNING' 
  AND end_time IS NULL
ORDER BY start_time;

-- 将长时间RUNNING的任务标记为超时失败
UPDATE ke_task_execution_history
SET execution_status = 'TIMEOUT',
    end_time = NOW(),
    error_message = '任务执行超时，手动清理',
    duration = TIMESTAMPDIFF(SECOND, start_time, NOW()) * 1000
WHERE execution_status = 'RUNNING'
  AND end_time IS NULL
  AND start_time < DATE_SUB(NOW(), INTERVAL 5 MINUTE);

-- 确认清理结果
SELECT execution_status, COUNT(*) as count
FROM ke_task_execution_history
GROUP BY execution_status;
```

**注意**: 这只是清理数据库记录，不会停止Java进程中卡住的线程。应用需要重启。

## 常见问题排查

### Q1: 超时包装器不起作用

**可能原因**: 
- Kafka Consumer的底层Socket操作不响应中断
- 需要在Kafka配置中设置更短的超时时间

**解决方法**:
修改`KafkaStoragePlugin.buildConsumerProps()`，添加:
```java
props.put("request.timeout.ms", "15000");
props.put("session.timeout.ms", "10000");
props.put("connections.max.idle.ms", "20000");
```

### Q2: 日志不输出增强信息

**可能原因**: 日志级别配置不正确

**解决方法**:
```yaml
logging:
  level:
    org.kafka.eagle.web.service.TaskExecutorManager: INFO
    org.kafka.eagle.core.api.KafkaSchemaFactory: INFO
```

### Q3: 诊断接口无法访问

**可能原因**: 应用未启动或端口不对

**解决方法**:
```bash
# 检查应用是否运行
netstat -an | findstr :8080

# 检查应用日志
tail -f logs/efak-web.log
```

## 预期诊断结果

基于当前症状(所有任务RUNNING状态3000+秒)，**最可能的诊断结果**是:

### 场景1: Kafka连接阻塞 (概率 80%)
- `consumer.listTopics()`无限期阻塞
- Kafka集群可能不可达或响应极慢
- 堆栈会显示卡在`NetworkClient`或`KafkaConsumer`相关调用

**立即行动**:
1. 检查Kafka集群是否正常
2. 验证网络连接性
3. 查看Kafka客户端配置

### 场景2: 数据库连接池耗尽 (概率 15%)
- 早期任务占用了所有数据库连接
- 后续任务等待连接
- 堆栈会显示卡在HikariCP或JDBC相关调用

**立即行动**:
1. 检查数据库连接池配置
2. 查看数据库慢查询
3. 检查是否有锁等待

### 场景3: 线程池设计问题 (概率 5%)
- 10个线程池线程都在执行长任务
- 新任务无法获得线程执行
- 但根据日志，任务似乎已经开始执行，所以不太可能

## 后续优化建议

无论诊断结果如何，建议实施以下改进:

1. **任务执行隔离**
   - 不同类型的任务使用独立线程池
   - 防止一种任务阻塞影响其他任务

2. **断路器模式**
   - 对Kafka连接添加断路器
   - 连续失败后自动跳过该集群

3. **任务心跳机制**
   - 任务执行期间定期更新心跳
   - 超过阈值自动终止

4. **增强监控**
   - 添加Prometheus指标
   - 实时监控任务执行时长

5. **优雅降级**
   - 单个集群失败不影响其他集群
   - 任务超时自动取消并记录

## 诊断报告模板

执行诊断后，请按以下格式提供报告:

```
### 诊断时间
2025-11-16 20:XX:XX

### 运行中的任务
- 任务数: X
- 任务详情: [...]

### 阻塞线程
- 阻塞线程数: X
- 关键堆栈: [...]

### 死锁检测
- 是否死锁: 是/否

### 关键日志
[最近10分钟的关键日志]

### 初步结论
[根据上述信息的初步判断]
```

请在完成编译重启后，立即运行诊断并提供诊断报告。
