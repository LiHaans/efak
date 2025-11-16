package org.kafka.eagle.web.controller;

import lombok.extern.slf4j.Slf4j;
import org.kafka.eagle.web.scheduler.UnifiedDistributedScheduler;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * <p>
 * 任务诊断控制器 - 用于诊断任务调度问题
 * </p>
 * @author Mr.SmartLoli
 * @since 2025/11/16
 * @version 5.0.0
 */
@Slf4j
@RestController
@RequestMapping("/api/diagnostics")
public class TaskDiagnosticsController {

    @Autowired
    private UnifiedDistributedScheduler scheduler;

    /**
     * 获取所有线程的堆栈信息
     */
    @GetMapping("/threads")
    public Map<String, Object> getThreadDump() {
        Map<String, Object> result = new HashMap<>();
        
        ThreadMXBean threadMXBean = ManagementFactory.getThreadMXBean();
        ThreadInfo[] threadInfos = threadMXBean.dumpAllThreads(true, true);
        
        List<Map<String, Object>> threadList = new ArrayList<>();
        
        for (ThreadInfo threadInfo : threadInfos) {
            if (threadInfo == null) continue;
            
            String threadName = threadInfo.getThreadName();
            
            // 重点关注调度器相关的线程
            if (threadName.contains("pool") || threadName.contains("scheduling") || 
                threadName.contains("kafka") || threadName.contains("executor")) {
                
                Map<String, Object> threadData = new HashMap<>();
                threadData.put("threadId", threadInfo.getThreadId());
                threadData.put("threadName", threadName);
                threadData.put("threadState", threadInfo.getThreadState().toString());
                threadData.put("blockedTime", threadInfo.getBlockedTime());
                threadData.put("blockedCount", threadInfo.getBlockedCount());
                threadData.put("waitedTime", threadInfo.getWaitedTime());
                threadData.put("waitedCount", threadInfo.getWaitedCount());
                
                // 锁信息
                if (threadInfo.getLockName() != null) {
                    threadData.put("lockName", threadInfo.getLockName());
                    threadData.put("lockOwnerId", threadInfo.getLockOwnerId());
                    threadData.put("lockOwnerName", threadInfo.getLockOwnerName());
                }
                
                // 堆栈跟踪
                StackTraceElement[] stackTrace = threadInfo.getStackTrace();
                List<String> stackTraceList = new ArrayList<>();
                for (int i = 0; i < Math.min(stackTrace.length, 15); i++) {
                    stackTraceList.add(stackTrace[i].toString());
                }
                threadData.put("stackTrace", stackTraceList);
                
                threadList.add(threadData);
            }
        }
        
        result.put("timestamp", LocalDateTime.now());
        result.put("totalThreads", threadInfos.length);
        result.put("relevantThreads", threadList.size());
        result.put("threads", threadList);
        
        return result;
    }

    /**
     * 获取运行中的任务详情
     */
    @GetMapping("/running-tasks")
    public Map<String, Object> getRunningTasks() {
        Map<String, Object> result = new HashMap<>();
        
        try {
            List<Map<String, Object>> runningTasks = scheduler.getRunningTasks();
            
            result.put("timestamp", LocalDateTime.now());
            result.put("runningTaskCount", runningTasks.size());
            result.put("tasks", runningTasks);
            result.put("schedulerStatus", scheduler.getSchedulerStatus());
            
        } catch (Exception e) {
            log.error("获取运行中任务失败", e);
            result.put("error", e.getMessage());
        }
        
        return result;
    }

    /**
     * 检测死锁线程
     */
    @GetMapping("/deadlocks")
    public Map<String, Object> detectDeadlocks() {
        Map<String, Object> result = new HashMap<>();
        
        ThreadMXBean threadMXBean = ManagementFactory.getThreadMXBean();
        long[] deadlockedThreads = threadMXBean.findDeadlockedThreads();
        
        if (deadlockedThreads == null || deadlockedThreads.length == 0) {
            result.put("hasDeadlock", false);
            result.put("message", "未检测到死锁");
        } else {
            result.put("hasDeadlock", true);
            result.put("deadlockedThreadCount", deadlockedThreads.length);
            
            ThreadInfo[] threadInfos = threadMXBean.getThreadInfo(deadlockedThreads, true, true);
            List<Map<String, Object>> deadlockDetails = new ArrayList<>();
            
            for (ThreadInfo threadInfo : threadInfos) {
                if (threadInfo == null) continue;
                
                Map<String, Object> threadData = new HashMap<>();
                threadData.put("threadId", threadInfo.getThreadId());
                threadData.put("threadName", threadInfo.getThreadName());
                threadData.put("threadState", threadInfo.getThreadState().toString());
                threadData.put("lockName", threadInfo.getLockName());
                threadData.put("lockOwnerId", threadInfo.getLockOwnerId());
                threadData.put("lockOwnerName", threadInfo.getLockOwnerName());
                
                StackTraceElement[] stackTrace = threadInfo.getStackTrace();
                List<String> stackTraceList = Arrays.stream(stackTrace)
                    .limit(10)
                    .map(StackTraceElement::toString)
                    .collect(Collectors.toList());
                threadData.put("stackTrace", stackTraceList);
                
                deadlockDetails.add(threadData);
            }
            
            result.put("deadlocks", deadlockDetails);
        }
        
        result.put("timestamp", LocalDateTime.now());
        return result;
    }

    /**
     * 获取线程池状态
     */
    @GetMapping("/thread-pool")
    public Map<String, Object> getThreadPoolStatus() {
        Map<String, Object> result = new HashMap<>();
        
        ThreadMXBean threadMXBean = ManagementFactory.getThreadMXBean();
        
        result.put("timestamp", LocalDateTime.now());
        result.put("threadCount", threadMXBean.getThreadCount());
        result.put("peakThreadCount", threadMXBean.getPeakThreadCount());
        result.put("totalStartedThreadCount", threadMXBean.getTotalStartedThreadCount());
        result.put("daemonThreadCount", threadMXBean.getDaemonThreadCount());
        
        // 按状态统计线程
        ThreadInfo[] allThreads = threadMXBean.dumpAllThreads(false, false);
        Map<Thread.State, Long> threadStateCount = Arrays.stream(allThreads)
            .filter(Objects::nonNull)
            .collect(Collectors.groupingBy(
                ThreadInfo::getThreadState,
                Collectors.counting()
            ));
        
        result.put("threadsByState", threadStateCount);
        
        return result;
    }

    /**
     * 获取调度器详细状态
     */
    @GetMapping("/scheduler-status")
    public Map<String, Object> getSchedulerStatus() {
        return scheduler.getSchedulerStatus();
    }

    /**
     * 分析阻塞线程
     */
    @GetMapping("/blocked-threads")
    public Map<String, Object> getBlockedThreads() {
        Map<String, Object> result = new HashMap<>();
        
        ThreadMXBean threadMXBean = ManagementFactory.getThreadMXBean();
        ThreadInfo[] threadInfos = threadMXBean.dumpAllThreads(true, true);
        
        List<Map<String, Object>> blockedThreads = new ArrayList<>();
        
        for (ThreadInfo threadInfo : threadInfos) {
            if (threadInfo == null) continue;
            
            // 查找阻塞或等待状态的线程
            Thread.State state = threadInfo.getThreadState();
            if (state == Thread.State.BLOCKED || state == Thread.State.WAITING || 
                state == Thread.State.TIMED_WAITING) {
                
                String threadName = threadInfo.getThreadName();
                if (threadName.contains("pool") || threadName.contains("scheduling") || 
                    threadName.contains("kafka") || threadName.contains("executor")) {
                    
                    Map<String, Object> threadData = new HashMap<>();
                    threadData.put("threadId", threadInfo.getThreadId());
                    threadData.put("threadName", threadName);
                    threadData.put("threadState", state.toString());
                    threadData.put("blockedCount", threadInfo.getBlockedCount());
                    threadData.put("blockedTime", threadInfo.getBlockedTime());
                    threadData.put("waitedCount", threadInfo.getWaitedCount());
                    threadData.put("waitedTime", threadInfo.getWaitedTime());
                    
                    if (threadInfo.getLockName() != null) {
                        threadData.put("waitingOn", threadInfo.getLockName());
                        if (threadInfo.getLockOwnerName() != null) {
                            threadData.put("ownedBy", threadInfo.getLockOwnerName() + 
                                " (id=" + threadInfo.getLockOwnerId() + ")");
                        }
                    }
                    
                    // 获取关键堆栈信息
                    StackTraceElement[] stackTrace = threadInfo.getStackTrace();
                    if (stackTrace.length > 0) {
                        List<String> keyFrames = new ArrayList<>();
                        for (int i = 0; i < Math.min(stackTrace.length, 10); i++) {
                            String frame = stackTrace[i].toString();
                            // 高亮关键代码位置
                            if (frame.contains("kafka.eagle") || frame.contains("kafka") || 
                                frame.contains("Socket") || frame.contains("Lock")) {
                                keyFrames.add(frame);
                            }
                        }
                        threadData.put("keyStackFrames", keyFrames);
                    }
                    
                    blockedThreads.add(threadData);
                }
            }
        }
        
        result.put("timestamp", LocalDateTime.now());
        result.put("blockedThreadCount", blockedThreads.size());
        result.put("blockedThreads", blockedThreads);
        
        return result;
    }
}
