# EFAK 任务诊断脚本
# 用于实时监控任务执行状态

param(
    [string]$Host = "localhost",
    [int]$Port = 8080,
    [string]$Action = "all"
)

$baseUrl = "http://${Host}:${Port}/api/diagnostics"

function Write-Header {
    param([string]$Title)
    Write-Host "`n========================================" -ForegroundColor Cyan
    Write-Host " $Title" -ForegroundColor Cyan
    Write-Host "========================================`n" -ForegroundColor Cyan
}

function Invoke-DiagnosticApi {
    param([string]$Endpoint)
    
    try {
        $url = "$baseUrl/$Endpoint"
        Write-Host "调用接口: $url" -ForegroundColor Gray
        $response = Invoke-RestMethod -Uri $url -Method Get -ErrorAction Stop
        return $response
    } catch {
        Write-Host "错误: $_" -ForegroundColor Red
        return $null
    }
}

function Show-RunningTasks {
    Write-Header "运行中的任务"
    
    $result = Invoke-DiagnosticApi "running-tasks"
    if ($result) {
        Write-Host "时间戳: $($result.timestamp)" -ForegroundColor Yellow
        Write-Host "运行中任务数: $($result.runningTaskCount)" -ForegroundColor Yellow
        
        if ($result.tasks -and $result.tasks.Count -gt 0) {
            Write-Host "`n任务列表:" -ForegroundColor Green
            foreach ($task in $result.tasks) {
                Write-Host "  - 任务ID: $($task.taskId)"
                Write-Host "    已取消: $($task.cancelled)"
                Write-Host "    已完成: $($task.done)"
                Write-Host ""
            }
        } else {
            Write-Host "没有运行中的任务" -ForegroundColor Green
        }
        
        if ($result.schedulerStatus) {
            Write-Host "`n调度器状态:" -ForegroundColor Green
            Write-Host "  启用: $($result.schedulerStatus.enabled)"
            Write-Host "  运行中任务: $($result.schedulerStatus.runningTasks)"
            Write-Host "  注册任务: $($result.schedulerStatus.registeredTasks)"
            Write-Host "  节点ID: $($result.schedulerStatus.nodeId)"
        }
    }
}

function Show-BlockedThreads {
    Write-Header "阻塞的线程"
    
    $result = Invoke-DiagnosticApi "blocked-threads"
    if ($result) {
        Write-Host "时间戳: $($result.timestamp)" -ForegroundColor Yellow
        Write-Host "阻塞线程数: $($result.blockedThreadCount)" -ForegroundColor Yellow
        
        if ($result.blockedThreads -and $result.blockedThreads.Count -gt 0) {
            foreach ($thread in $result.blockedThreads) {
                Write-Host "`n线程信息:" -ForegroundColor Red
                Write-Host "  ID: $($thread.threadId)"
                Write-Host "  名称: $($thread.threadName)"
                Write-Host "  状态: $($thread.threadState)"
                Write-Host "  阻塞次数: $($thread.blockedCount)"
                Write-Host "  等待次数: $($thread.waitedCount)"
                
                if ($thread.waitingOn) {
                    Write-Host "  等待资源: $($thread.waitingOn)" -ForegroundColor Magenta
                }
                
                if ($thread.ownedBy) {
                    Write-Host "  持有者: $($thread.ownedBy)" -ForegroundColor Magenta
                }
                
                if ($thread.keyStackFrames -and $thread.keyStackFrames.Count -gt 0) {
                    Write-Host "`n  关键堆栈帧:" -ForegroundColor Yellow
                    foreach ($frame in $thread.keyStackFrames) {
                        Write-Host "    $frame" -ForegroundColor Gray
                    }
                }
            }
        } else {
            Write-Host "没有阻塞的线程" -ForegroundColor Green
        }
    }
}

function Show-Deadlocks {
    Write-Header "死锁检测"
    
    $result = Invoke-DiagnosticApi "deadlocks"
    if ($result) {
        Write-Host "时间戳: $($result.timestamp)" -ForegroundColor Yellow
        Write-Host "是否有死锁: $($result.hasDeadlock)" -ForegroundColor Yellow
        
        if ($result.hasDeadlock -eq $true) {
            Write-Host "`n检测到死锁!" -ForegroundColor Red
            Write-Host "死锁线程数: $($result.deadlockedThreadCount)" -ForegroundColor Red
            
            if ($result.deadlocks) {
                foreach ($thread in $result.deadlocks) {
                    Write-Host "`n死锁线程:" -ForegroundColor Red
                    Write-Host "  ID: $($thread.threadId)"
                    Write-Host "  名称: $($thread.threadName)"
                    Write-Host "  状态: $($thread.threadState)"
                    Write-Host "  锁名称: $($thread.lockName)"
                    Write-Host "  锁持有者: $($thread.lockOwnerName) (ID: $($thread.lockOwnerId))"
                    
                    if ($thread.stackTrace) {
                        Write-Host "`n  堆栈跟踪:" -ForegroundColor Yellow
                        foreach ($frame in $thread.stackTrace) {
                            Write-Host "    $frame" -ForegroundColor Gray
                        }
                    }
                }
            }
        } else {
            Write-Host "未检测到死锁" -ForegroundColor Green
        }
    }
}

function Show-ThreadPool {
    Write-Header "线程池状态"
    
    $result = Invoke-DiagnosticApi "thread-pool"
    if ($result) {
        Write-Host "时间戳: $($result.timestamp)" -ForegroundColor Yellow
        Write-Host "当前线程数: $($result.threadCount)"
        Write-Host "峰值线程数: $($result.peakThreadCount)"
        Write-Host "总启动线程数: $($result.totalStartedThreadCount)"
        Write-Host "守护线程数: $($result.daemonThreadCount)"
        
        if ($result.threadsByState) {
            Write-Host "`n线程状态分布:" -ForegroundColor Green
            $result.threadsByState.PSObject.Properties | ForEach-Object {
                Write-Host "  $($_.Name): $($_.Value)"
            }
        }
    }
}

function Show-SchedulerStatus {
    Write-Header "调度器状态"
    
    $result = Invoke-DiagnosticApi "scheduler-status"
    if ($result) {
        Write-Host "启用: $($result.enabled)"
        Write-Host "运行中任务: $($result.runningTasks)"
        Write-Host "注册任务: $($result.registeredTasks)"
        Write-Host "节点ID: $($result.nodeId)"
        Write-Host "时间戳: $($result.timestamp)"
    }
}

# 主程序
Write-Host "EFAK 任务诊断工具" -ForegroundColor Cyan
Write-Host "目标: $Host:$Port`n" -ForegroundColor Cyan

switch ($Action.ToLower()) {
    "running" {
        Show-RunningTasks
    }
    "blocked" {
        Show-BlockedThreads
    }
    "deadlock" {
        Show-Deadlocks
    }
    "threadpool" {
        Show-ThreadPool
    }
    "scheduler" {
        Show-SchedulerStatus
    }
    "all" {
        Show-SchedulerStatus
        Show-RunningTasks
        Show-BlockedThreads
        Show-Deadlocks
        Show-ThreadPool
    }
    default {
        Write-Host "未知操作: $Action" -ForegroundColor Red
        Write-Host "可用操作: running, blocked, deadlock, threadpool, scheduler, all" -ForegroundColor Yellow
    }
}

Write-Host "`n诊断完成" -ForegroundColor Green
