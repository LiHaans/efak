package org.kafka.eagle.core.util;

import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.*;

/**
 * <p>
 * 超时包装器 - 为可能阻塞的操作添加超时控制
 * </p>
 * @author Mr.SmartLoli
 * @since 2025/11/16
 * @version 5.0.0
 */
@Slf4j
public class TimeoutWrapper {

    private static final ExecutorService executor = Executors.newCachedThreadPool(r -> {
        Thread thread = new Thread(r);
        thread.setName("timeout-wrapper-" + System.currentTimeMillis());
        thread.setDaemon(true);
        return thread;
    });

    /**
     * 执行带超时的操作
     *
     * @param callable 要执行的操作
     * @param timeout 超时时间
     * @param unit 时间单位
     * @param operationName 操作名称（用于日志）
     * @return 操作结果
     * @throws TimeoutException 如果操作超时
     * @throws Exception 其他异常
     */
    public static <T> T executeWithTimeout(Callable<T> callable, long timeout, TimeUnit unit, String operationName) 
            throws Exception {
        
        Future<T> future = executor.submit(callable);
        
        try {
            log.debug("开始执行操作: {}, 超时设置: {}ms", operationName, unit.toMillis(timeout));
            T result = future.get(timeout, unit);
            log.debug("操作完成: {}", operationName);
            return result;
        } catch (TimeoutException e) {
            future.cancel(true);
            log.error("操作超时: {}, 超时时间: {}ms", operationName, unit.toMillis(timeout));
            throw new TimeoutException("操作超时: " + operationName + ", 超过 " + unit.toMillis(timeout) + "ms");
        } catch (ExecutionException e) {
            log.error("操作执行失败: {}", operationName, e.getCause());
            throw (Exception) e.getCause();
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            log.error("操作被中断: {}", operationName);
            throw e;
        }
    }

    /**
     * 执行带超时的操作（使用默认30秒超时）
     */
    public static <T> T executeWithTimeout(Callable<T> callable, String operationName) throws Exception {
        return executeWithTimeout(callable, 30, TimeUnit.SECONDS, operationName);
    }

    /**
     * 执行带超时的操作（无返回值）
     */
    public static void executeWithTimeout(Runnable runnable, long timeout, TimeUnit unit, String operationName) 
            throws Exception {
        executeWithTimeout(() -> {
            runnable.run();
            return null;
        }, timeout, unit, operationName);
    }

    /**
     * 关闭执行器（应用关闭时调用）
     */
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
}
