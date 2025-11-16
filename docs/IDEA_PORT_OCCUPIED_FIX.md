# IDEA停止后端口占用问题 - 解决方案

## 问题描述

在IDEA中运行Spring Boot应用后，点击"停止"按钮，再次启动时报错：
```
Port 28888 is already in use
```

需要手动在任务管理器中结束Java进程才能再次启动。

## 问题根因

### 1. IDEA停止机制
IDEA的"停止"按钮发送的是**SIGTERM**信号（软终止），而不是SIGKILL（强制终止）。应用收到信号后：
1. 触发Spring的`@PreDestroy`钩子
2. 等待正在执行的请求/任务完成
3. 清理资源并退出

### 2. 应用未能及时关闭的原因

在EFAK项目中，以下因素导致应用无法快速关闭：

#### A. 定时任务正在执行
```java
@Scheduled(fixedRate = 60000) // 每60秒执行一次
public void scanAndExecuteTasks() {
    // 任务执行需要30秒
}
```
- 如果恰好在任务执行中点击停止，需要等待任务完成
- 任务执行时间可能很长（Kafka连接、数据库操作等）

#### B. 线程池未正确关闭
```java
private final ScheduledExecutorService schedulerExecutor = 
    Executors.newScheduledThreadPool(10);
```
- 原始的`@PreDestroy`只调用`shutdown()`，不等待线程池关闭
- 如果线程池中有非守护线程，JVM不会退出

#### C. 外部资源连接未释放
- Kafka Consumer连接可能未关闭
- Redis连接池可能有活跃连接
- 数据库连接池可能有活跃连接

## 解决方案

### 1. 优化线程池关闭逻辑

**修改前**:
```java
@PreDestroy
public void destroy() {
    log.info("销毁统一分布式任务调度器");
    stopScheduler();
    schedulerExecutor.shutdown(); // 只是不再接受新任务
}
```

**修改后**:
```java
@PreDestroy
public void destroy() {
    log.info("开始销毁统一分布式任务调度器");
    
    try {
        // 1. 禁用调度器，阻止新任务提交
        stopScheduler();
        
        // 2. 关闭线程池，不再接受新任务
        schedulerExecutor.shutdown();
        
        // 3. 等待正在执行的任务完成（最多15秒）
        if (!schedulerExecutor.awaitTermination(15, TimeUnit.SECONDS)) {
            log.warn("调度器未能在15秒内正常关闭，强制终止");
            
            // 4. 强制终止所有任务
            List<Runnable> pendingTasks = schedulerExecutor.shutdownNow();
            log.info("强制终止了{}个待执行的任务", pendingTasks.size());
            
            // 5. 再等待10秒确保线程池关闭
            if (!schedulerExecutor.awaitTermination(10, TimeUnit.SECONDS)) {
                log.error("调度器线程池无法关闭，可能存在卡住的线程");
            }
        } else {
            log.info("调度器线程池已正常关闭");
        }
        
        // 6. 关闭TimeoutWrapper的线程池
        org.kafka.eagle.core.util.TimeoutWrapper.shutdown();
        
        log.info("统一分布式任务调度器销毁完成");
        
    } catch (InterruptedException e) {
        log.error("调度器关闭过程被中断", e);
        Thread.currentThread().interrupt();
        schedulerExecutor.shutdownNow();
    }
}
```

### 2. 启用Spring Boot优雅关闭

在`application.yml`中添加：

```yaml
spring:
  # 优雅关闭配置 - 等待正在执行的请求完成
  lifecycle:
    timeout-per-shutdown-phase: 30s

server:
  # 优雅关闭配置 - 解决IDEA停止后端口占用问题
  shutdown: graceful
```

**效果**:
- Spring会等待30秒让所有组件完成关闭流程
- Tomcat会停止接受新请求，但等待现有请求完成
- 所有`@PreDestroy`方法都有足够时间执行

### 3. 确保TimeoutWrapper线程池也关闭

在`TimeoutWrapper.java`中已有shutdown方法：
```java
public static void shutdown() {
    executor.shutdown();
    try {
        if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
            executor.shutdownNow();
        }
    } catch (InterruptedException e) {
        executor.shutdownNow();
        Thread.currentThread().interrupt();
    }
}
```

在`UnifiedDistributedScheduler.destroy()`中调用它。

## 测试验证

### 测试步骤

1. **启动应用**
   ```bash
   # 在IDEA中运行KafkaEagle主类
   ```

2. **等待应用完全启动**（看到"Started KafkaEagle"日志）

3. **点击IDEA的停止按钮**

4. **观察日志输出**，应该看到：
   ```
   开始销毁统一分布式任务调度器
   调度器线程池已正常关闭
   TimeoutWrapper线程池已关闭
   统一分布式任务调度器销毁完成
   ```

5. **检查进程是否退出**
   ```powershell
   netstat -ano | findstr :28888
   # 应该没有输出，或只有TIME_WAIT状态
   ```

6. **立即重新启动**
   ```bash
   # 应该能够成功启动，不会报端口占用
   ```

### 预期结果

✅ **正常关闭**：
- 15-30秒内应用完全退出
- 端口立即释放
- 再次启动无需等待

❌ **异常情况**（如果发生）：
- 超过30秒未退出 → 检查是否有任务死锁
- 端口仍被占用 → 强制终止进程

## IDEA配置优化（可选）

### 1. 设置更长的停止超时

`File → Settings → Build, Execution, Deployment → Debugger`
- **Transport**: Socket
- **Debugger Shutdown Timeout**: 30000 ms

### 2. 启用"Force Terminate"选项

在Run Configuration中：
1. 点击 `Run → Edit Configurations`
2. 选择你的Spring Boot配置
3. 勾选 `Before launch → Build` 下的所有清理选项

### 3. 使用"Stop with Disconnect"

在调试模式下，使用 `Disconnect` 而不是 `Stop`，让应用自然关闭。

## 常见问题排查

### Q1: 应用仍需30秒才能关闭

**原因**: 这是正常的，优雅关闭需要时间
**解决**: 如果想更快关闭，可以：
- 减少`timeout-per-shutdown-phase`
- 减少线程池的`awaitTermination`时间
- 但不建议低于10秒，可能导致数据不一致

### Q2: 偶尔还是会端口占用

**原因**: 任务执行时间过长或死锁
**诊断**:
```bash
# 查看哪个进程占用端口
netstat -ano | findstr :28888

# 查看进程详情
tasklist /FI "PID eq <进程ID>"

# 强制终止
taskkill /F /PID <进程ID>
```

**根本解决**: 
- 为长时间运行的任务添加超时
- 确保所有Kafka/Redis连接都正确关闭
- 检查是否有死锁或无限循环

### Q3: 日志显示"无法关闭"

**症状**:
```
调度器线程池无法关闭，可能存在卡住的线程
```

**排查**:
1. 使用jstack查看线程堆栈：
   ```bash
   jstack <进程ID> > thread_dump.txt
   ```

2. 查找BLOCKED或WAITING状态的线程

3. 检查是否有：
   - 未捕获的阻塞I/O操作
   - Kafka Consumer没有正确close
   - 数据库连接未归还连接池

## 后续优化建议

### 1. 添加健康检查端点

```java
@RestController
public class HealthController {
    
    @Autowired
    private UnifiedDistributedScheduler scheduler;
    
    @GetMapping("/actuator/health/ready")
    public Map<String, Object> readiness() {
        Map<String, Object> health = new HashMap<>();
        health.put("status", scheduler.isHealthy() ? "UP" : "DOWN");
        health.put("runningTasks", scheduler.getRunningTasks().size());
        return health;
    }
}
```

在关闭时，Kubernetes或Docker可以先调用这个端点判断应用是否准备关闭。

### 2. 任务执行超时保护

为所有长时间运行的任务添加超时：
```java
Future<?> future = executor.submit(task);
try {
    future.get(5, TimeUnit.MINUTES); // 5分钟超时
} catch (TimeoutException e) {
    future.cancel(true);
    log.error("任务超时被强制取消");
}
```

### 3. 监控线程池状态

```java
@Scheduled(fixedRate = 60000)
public void monitorThreadPool() {
    ThreadPoolExecutor pool = (ThreadPoolExecutor) schedulerExecutor;
    log.info("线程池状态: active={}, queue={}, completed={}",
        pool.getActiveCount(),
        pool.getQueue().size(),
        pool.getCompletedTaskCount());
}
```

## 总结

通过以上修改：
1. ✅ 线程池会等待任务完成后再关闭（最多15秒）
2. ✅ 超时后强制终止所有任务
3. ✅ Spring Boot优雅关闭确保所有组件都有时间清理
4. ✅ IDEA停止后应用在30秒内完全退出
5. ✅ 端口立即释放，可以马上重启

如果仍有问题，使用`jstack`和线程dump分析哪些线程阻止了JVM退出。
