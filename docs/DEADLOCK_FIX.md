# MySQL 死锁问题修复方案

## 问题描述

在 `ke_topic_instant_metrics` 表的批量插入/更新操作中出现 MySQL 死锁错误:

```
MySQLTransactionRollbackException: Deadlock found when trying to get lock; try restarting transaction
SQL: INSERT INTO ke_topic_instant_metrics (cluster_id, topic_name, metric_type, metric_value, last_updated) 
     VALUES (?, ?, ?, ?, NOW()) ... ON DUPLICATE KEY UPDATE ...
```

## 根本原因分析

### 1. 批量 IODKU 锁序不一致
- 并发线程执行 `INSERT ... ON DUPLICATE KEY UPDATE` 时,InnoDB 按 VALUES 顺序逐条加锁
- 不同线程的批次数据顺序不一致时,形成循环等待导致死锁
- 例如: 线程A 按 (K1, K2) 顺序加锁,线程B 按 (K2, K1) 顺序加锁

### 2. 二级唯一索引与主键双重加锁
- 表结构: 自增主键 `id` + 唯一索引 `(cluster_id, topic_name, metric_type)`
- 更新现有行时需同时锁定主键记录和二级索引记录
- 双索引加锁路径更复杂,放大锁竞争

### 3. REPEATABLE READ 隔离级别的 gap/next-key 锁
- MySQL 默认 RR 隔离级别使用间隙锁
- 在热点表上增加死锁概率

### 4. 高并发写入
- `CompletableFuture` 并发采集多个 topic,每个 topic 写入 4 行指标
- 线程池大小: `Runtime.getRuntime().availableProcessors() * 2`

## 解决方案

### 方案 1: 应用层优化 (已实施)

#### 1.1 批量数据稳定排序 ✅
**文件**: `TaskExecutorManager.java`

```java
// 在 processTopic() 方法中,调用 batchUpsertMetrics 前排序
sortMetricsByUniqueKey(topicInstantMetrics);

// 排序规则: cluster_id ASC, topic_name ASC, metric_type ASC
private void sortMetricsByUniqueKey(List<TopicInstantMetrics> metrics) {
    metrics.sort((m1, m2) -> {
        int clusterCompare = compareStrings(m1.getClusterId(), m2.getClusterId());
        if (clusterCompare != 0) return clusterCompare;
        
        int topicCompare = compareStrings(m1.getTopicName(), m2.getTopicName());
        if (topicCompare != 0) return topicCompare;
        
        return compareStrings(m1.getMetricType(), m2.getMetricType());
    });
}
```

**效果**: 确保所有线程对相同/相邻唯一键使用统一加锁顺序,从根本上消除锁循环

#### 1.2 死锁自动重试机制 ✅
**新增文件**: `DeadlockRetryer.java`

```java
// 使用重试机制包装数据库调用
DeadlockRetryer.execute(() -> {
    topicInstantMetricsMapper.batchUpsertMetrics(topicInstantMetrics);
    return null;
});
```

**特性**:
- 自动识别死锁异常 (错误码 1213) 和锁等待超时 (错误码 1205)
- 默认最大重试 3 次
- 指数退避 (50ms → 100ms → 200ms) + 随机抖动 (0-50ms)
- 记录详细的重试日志

#### 1.3 数据库隔离级别优化 ✅
**文件**: `application.yml`

```yaml
spring:
  datasource:
    hikari:
      # 使用 READ COMMITTED 隔离级别减少 gap/next-key 锁
      transaction-isolation: TRANSACTION_READ_COMMITTED
```

**效果**: 
- 避免间隙锁,减少不同键之间的相互影响
- 适合高并发写入场景

## 实施步骤

### 1. 代码变更
- ✅ 创建 `DeadlockRetryer.java` 工具类
- ✅ 修改 `TaskExecutorManager.java` 增加排序和重试逻辑
- ✅ 配置 `application.yml` 设置隔离级别

### 2. 测试验证
```bash
# 运行单元测试
mvn test -Dtest=DeadlockRetryerTest

# 集成测试(需要真实 MySQL 环境)
# - 并发执行 topic 监控任务
# - 观察死锁日志是否减少到 0 或可接受水平
```

### 3. 监控指标
观察以下日志关键词:
- `[死锁重试]` - 重试事件
- `即时指标入库失败(重试后仍失败)` - 最终失败事件

建议添加 Prometheus 指标:
```
ke_deadlock_retry_count{result="success|failure"}
ke_topic_instant_metrics_upsert_duration_seconds
```

## 回滚方案

如果出现问题,可以快速回滚:

### 1. 代码回滚
```bash
# 注释掉排序和重试逻辑,恢复原始代码
git revert <commit-hash>
```

### 2. 配置回滚
```yaml
spring:
  datasource:
    hikari:
      # 恢复为默认 REPEATABLE READ
      # transaction-isolation: TRANSACTION_REPEATABLE_READ
```

## 长期优化建议

### 方案 2: 数据库结构优化 (可选)

将 `(cluster_id, topic_name, metric_type)` 改为**主键**,移除自增 `id`:

```sql
-- 当前表结构
CREATE TABLE ke_topic_instant_metrics (
  id bigint NOT NULL AUTO_INCREMENT,
  cluster_id varchar(64) NOT NULL,
  topic_name varchar(255) NOT NULL,
  metric_type varchar(32) NOT NULL,
  ...
  PRIMARY KEY (id),
  UNIQUE KEY idx_unique_cluster_topic_metric (cluster_id, topic_name, metric_type)
);

-- 优化后表结构
CREATE TABLE ke_topic_instant_metrics (
  cluster_id varchar(64) NOT NULL,
  topic_name varchar(255) NOT NULL,
  metric_type varchar(32) NOT NULL,
  ...
  PRIMARY KEY (cluster_id, topic_name, metric_type)
);
```

**优点**:
- 消除二级索引死锁隐患
- 减少回表查询,提升性能

**缺点**:
- 需要 DDL 变更和数据迁移
- 需要评估对现有代码的影响

## 参考文档

- [MySQL InnoDB 锁机制](https://dev.mysql.com/doc/refman/8.0/en/innodb-locking.html)
- [INSERT ... ON DUPLICATE KEY UPDATE 死锁分析](https://dev.mysql.com/doc/refman/8.0/en/innodb-locks-set.html)
- [事务隔离级别对比](https://dev.mysql.com/doc/refman/8.0/en/innodb-transaction-isolation-levels.html)

## 修改记录

| 日期 | 修改人 | 描述 |
|------|--------|------|
| 2025-11-22 | AI助手 | 实施死锁修复方案 v1.0 |
