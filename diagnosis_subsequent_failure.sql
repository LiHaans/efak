-- 后续任务执行失败诊断

-- 1. 查看最近10次任务执行的详细结果
SELECT 
    id,
    start_time,
    end_time,
    execution_status,
    result_message,
    error_message,
    duration
FROM ke_task_execution_history 
WHERE task_type = 'consumer_monitor'
ORDER BY start_time DESC 
LIMIT 10;

-- 2. 查看最近写入的数据及时间分布
SELECT 
    DATE_FORMAT(collect_time, '%H:%i') as time,
    COUNT(*) as count,
    GROUP_CONCAT(DISTINCT group_id) as groups
FROM ke_consumer_group_topic 
WHERE collect_time >= DATE_SUB(NOW(), INTERVAL 30 MINUTE)
GROUP BY DATE_FORMAT(collect_time, '%H:%i')
ORDER BY collect_time DESC;

-- 3. 查看最后一条数据的时间
SELECT 
    MAX(collect_time) as last_data_time,
    TIMESTAMPDIFF(MINUTE, MAX(collect_time), NOW()) as minutes_ago
FROM ke_consumer_group_topic;

-- 4. 统计最近30分钟按分钟的数据量
SELECT 
    DATE_FORMAT(collect_time, '%Y-%m-%d %H:%i') as minute,
    COUNT(*) as record_count
FROM ke_consumer_group_topic 
WHERE collect_time >= DATE_SUB(NOW(), INTERVAL 30 MINUTE)
GROUP BY DATE_FORMAT(collect_time, '%Y-%m-%d %H:%i')
ORDER BY minute DESC;

-- 5. 检查是否有错误消息
SELECT 
    start_time,
    result_message,
    error_message
FROM ke_task_execution_history 
WHERE task_type = 'consumer_monitor'
  AND start_time >= DATE_SUB(NOW(), INTERVAL 30 MINUTE)
  AND (error_message IS NOT NULL OR result_message LIKE '%失败%' OR result_message LIKE '%0条%')
ORDER BY start_time DESC;

-- 6. 查看节点ID是否保持一致
SELECT 
    executor_node,
    COUNT(*) as execution_count,
    MIN(start_time) as first_execution,
    MAX(start_time) as last_execution
FROM ke_task_execution_history 
WHERE task_type = 'consumer_monitor'
  AND start_time >= DATE_SUB(NOW(), INTERVAL 30 MINUTE)
GROUP BY executor_node
ORDER BY first_execution DESC;
