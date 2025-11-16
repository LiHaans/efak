-- ============================================
-- 消费者组监控任务诊断SQL脚本
-- ============================================

-- 1. 检查消费者组监控任务配置
SELECT 
    id,
    task_name,
    task_type,
    cron_expression,
    enabled,
    last_execute_time,
    next_execute_time,
    execute_count,
    success_count,
    fail_count,
    last_execute_result
FROM ke_task_scheduler 
WHERE task_type = 'consumer_monitor';

-- 2. 检查最近的任务执行历史（最近10次）
SELECT 
    id,
    task_name,
    execution_status,
    start_time,
    end_time,
    duration,
    result_message,
    error_message,
    executor_node
FROM ke_task_execution_history 
WHERE task_type = 'consumer_monitor'
ORDER BY start_time DESC 
LIMIT 10;

-- 3. 检查今天的消费者组数据
SELECT 
    collect_date, 
    COUNT(*) as record_count,
    COUNT(DISTINCT cluster_id) as cluster_count,
    COUNT(DISTINCT group_id) as group_count,
    MIN(collect_time) as first_collect,
    MAX(collect_time) as last_collect
FROM ke_consumer_group_topic 
WHERE collect_date = CURDATE()
GROUP BY collect_date;

-- 4. 检查最近5分钟的数据
SELECT COUNT(*) as count_last_5min
FROM ke_consumer_group_topic 
WHERE collect_time >= DATE_SUB(NOW(), INTERVAL 5 MINUTE);

-- 5. 检查最近一小时的数据
SELECT COUNT(*) as count_last_hour
FROM ke_consumer_group_topic 
WHERE collect_time >= DATE_SUB(NOW(), INTERVAL 1 HOUR);

-- 6. 查看最新的10条消费者组数据（如果有）
SELECT 
    cluster_id,
    group_id,
    topic_name,
    state,
    lags,
    collect_time
FROM ke_consumer_group_topic 
ORDER BY collect_time DESC 
LIMIT 10;

-- 7. 检查集群配置
SELECT 
    cluster_id,
    name,
    cluster_type,
    auth,
    total_nodes,
    online_nodes,
    availability,
    created_at
FROM ke_cluster;

-- 8. 检查broker状态
SELECT 
    cluster_id,
    broker_id,
    host_ip,
    port,
    jmx_port,
    status,
    updated_at
FROM ke_broker_info
ORDER BY cluster_id, broker_id;

-- 9. 检查最近失败的任务执行
SELECT 
    id,
    task_name,
    task_type,
    execution_status,
    start_time,
    end_time,
    error_message,
    executor_node
FROM ke_task_execution_history 
WHERE execution_status = 'FAILED'
  AND task_type = 'consumer_monitor'
ORDER BY start_time DESC 
LIMIT 5;

-- 10. 检查是否有正在运行的消费者监控任务
SELECT 
    id,
    task_name,
    execution_status,
    start_time,
    TIMESTAMPDIFF(SECOND, start_time, NOW()) as running_seconds,
    executor_node
FROM ke_task_execution_history 
WHERE execution_status = 'RUNNING'
  AND task_type = 'consumer_monitor'
ORDER BY start_time DESC;

-- ============================================
-- 如果发现问题，可以执行以下操作：
-- ============================================

-- 手动触发一次任务（需要通过API或手动执行Java代码）
-- 或者修改cron表达式为每分钟执行一次（测试用）：
-- UPDATE ke_task_scheduler 
-- SET cron_expression = '0 * * * * ?'
-- WHERE task_type = 'consumer_monitor';

-- 如果任务被禁用，启用它：
-- UPDATE ke_task_scheduler 
-- SET enabled = 1
-- WHERE task_type = 'consumer_monitor';

-- 重置任务状态（如果卡住）：
-- UPDATE ke_task_scheduler 
-- SET last_execute_time = NULL,
--     next_execute_time = NOW()
-- WHERE task_type = 'consumer_monitor';
