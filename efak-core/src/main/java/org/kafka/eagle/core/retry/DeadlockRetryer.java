package org.kafka.eagle.core.retry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DeadlockLoserDataAccessException;

import java.sql.SQLException;
import java.time.Duration;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.function.Predicate;

/**
 * 死锁重试工具类
 * 用于处理 MySQL 死锁和锁等待超时场景,提供自动重试机制
 * 
 * @author EFAK
 * @since 2025-11-22
 */
public class DeadlockRetryer {
    
    private static final Logger log = LoggerFactory.getLogger(DeadlockRetryer.class);
    
    /**
     * 默认最大重试次数
     */
    private static final int DEFAULT_MAX_ATTEMPTS = 3;
    
    /**
     * 默认初始退避时间(毫秒)
     */
    private static final long DEFAULT_BASE_BACKOFF_MS = 50L;
    
    /**
     * 默认最大退避时间(毫秒)
     */
    private static final long DEFAULT_MAX_BACKOFF_MS = 2000L;
    
    /**
     * 抖动范围(毫秒)
     */
    private static final long JITTER_MS = 50L;
    
    private static final Random random = new Random();
    
    /**
     * 执行带死锁重试的操作(使用默认配置)
     * 
     * @param action 要执行的操作
     * @param <T> 返回类型
     * @return 执行结果
     * @throws Exception 如果所有重试都失败
     */
    public static <T> T execute(Callable<T> action) throws Exception {
        return execute(action, DEFAULT_MAX_ATTEMPTS, Duration.ofMillis(DEFAULT_BASE_BACKOFF_MS));
    }
    
    /**
     * 执行带死锁重试的操作(自定义配置)
     * 
     * @param action 要执行的操作
     * @param maxAttempts 最大尝试次数
     * @param baseBackoff 初始退避时间
     * @param <T> 返回类型
     * @return 执行结果
     * @throws Exception 如果所有重试都失败
     */
    public static <T> T execute(Callable<T> action, int maxAttempts, Duration baseBackoff) throws Exception {
        return execute(action, DeadlockRetryer::isDeadlockOrLockTimeout, maxAttempts, baseBackoff);
    }
    
    /**
     * 执行带重试的操作(完全自定义)
     * 
     * @param action 要执行的操作
     * @param retryOn 判断是否需要重试的条件
     * @param maxAttempts 最大尝试次数
     * @param baseBackoff 初始退避时间
     * @param <T> 返回类型
     * @return 执行结果
     * @throws Exception 如果所有重试都失败
     */
    public static <T> T execute(Callable<T> action, Predicate<Throwable> retryOn, 
                                int maxAttempts, Duration baseBackoff) throws Exception {
        Exception lastException = null;
        long backoffMs = baseBackoff.toMillis();
        
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return action.call();
            } catch (Exception e) {
                lastException = e;
                
                // 判断是否需要重试
                if (!retryOn.test(e)) {
                    throw e;
                }
                
                // 如果是最后一次尝试,不再重试
                if (attempt >= maxAttempts) {
                    log.error("[死锁重试] 已达最大重试次数 {}, 放弃重试", maxAttempts);
                    break;
                }
                
                // 计算退避时间(指数退避 + 随机抖动)
                long jitter = random.nextInt((int) JITTER_MS);
                long sleepMs = Math.min(backoffMs + jitter, DEFAULT_MAX_BACKOFF_MS);
                
                log.warn("[死锁重试] 第 {}/{} 次尝试失败: {}, 等待 {}ms 后重试", 
                    attempt, maxAttempts, getErrorMessage(e), sleepMs);
                
                // 等待后重试
                try {
                    Thread.sleep(sleepMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("重试等待被中断", ie);
                }
                
                // 指数退避
                backoffMs *= 2;
            }
        }
        
        throw lastException;
    }
    
    /**
     * 判断异常是否为死锁或锁等待超时
     * 
     * @param throwable 异常对象
     * @return true 如果是死锁或锁等待超时
     */
    public static boolean isDeadlockOrLockTimeout(Throwable throwable) {
        if (throwable == null) {
            return false;
        }
        
        // 检查 Spring 的死锁异常
        if (throwable instanceof DeadlockLoserDataAccessException) {
            return true;
        }
        
        // 检查 SQL 异常
        if (throwable instanceof SQLException) {
            SQLException sqlEx = (SQLException) throwable;
            String sqlState = sqlEx.getSQLState();
            int errorCode = sqlEx.getErrorCode();
            
            // MySQL 死锁错误码: 1213
            // MySQL 锁等待超时错误码: 1205
            // SQL State: 40001 (事务回滚 - 死锁)
            // SQL State: 41000 (事务回滚 - 锁等待超时)
            return errorCode == 1213 || errorCode == 1205 
                || "40001".equals(sqlState) || "41000".equals(sqlState);
        }
        
        // 检查异常消息
        String message = throwable.getMessage();
        if (message != null) {
            message = message.toLowerCase();
            if (message.contains("deadlock") || 
                message.contains("lock wait timeout") ||
                message.contains("try restarting transaction")) {
                return true;
            }
        }
        
        // 递归检查 cause
        Throwable cause = throwable.getCause();
        if (cause != null && cause != throwable) {
            return isDeadlockOrLockTimeout(cause);
        }
        
        return false;
    }
    
    /**
     * 获取友好的错误消息
     */
    private static String getErrorMessage(Throwable throwable) {
        if (throwable == null) {
            return "Unknown error";
        }
        
        String message = throwable.getMessage();
        if (message != null && message.length() > 100) {
            return message.substring(0, 100) + "...";
        }
        
        return message != null ? message : throwable.getClass().getSimpleName();
    }
}
