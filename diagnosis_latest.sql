-- 最新执行情况诊断

-- 1. 最近20次任务执行（查看节点ID变化）
SELECT 
    id,
    DATE_FORMAT(start_time, '%H:%i:%s') as time,
    execution_status,
    executor_node,
    result_message
FROM ke_task_execution_history 
WHERE task_type = 'consumer_monitor'
ORDER BY start_time DESC 
LIMIT 20;

-- 2. 统计每个节点ID的执行次数（检查是否只有一个稳定的节点ID）
SELECT 
    executor_node,
    COUNT(*) as count,
    MIN(start_time) as first_exec,
    MAX(start_time) as last_exec
FROM ke_task_execution_history 
WHERE task_type = 'consumer_monitor'
  AND start_time >= DATE_SUB(NOW(), INTERVAL 1 HOUR)
GROUP BY executor_node
ORDER BY first_exec DESC;

-- 3. 检查最近的任务执行是否有"分配到消费者组"
SELECT 
    DATE_FORMAT(start_time, '%H:%i:%s') as time,
    result_message,
    CASE 
        WHEN result_message LIKE '%没有分配到消费者组%' THEN '未分配'
        WHEN result_message LIKE '%当前节点处理%' THEN '已分配'
        ELSE '其他'
    END as status
FROM ke_task_execution_history 
WHERE task_type = 'consumer_monitor'
  AND start_time >= DATE_SUB(NOW(), INTERVAL 30 MINUTE)
ORDER BY start_time DESC;

-- 4. 查看最后写入的数据时间
SELECT 
    MAX(collect_time) as last_data,
    COUNT(*) as total_records_today
FROM ke_consumer_group_topic 
WHERE DATE(collect_time) = CURDATE();

-- 5. 查看23:21之后是否还有数据写入
SELECT 
    DATE_FORMAT(collect_time, '%H:%i:%s') as time,
    group_id,
    topic,
    lag
FROM ke_consumer_group_topic 
WHERE collect_time >= '2025-11-16 23:20:00'
ORDER BY collect_time DESC;
