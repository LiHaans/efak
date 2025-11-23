package org.kafka.eagle.core.retry;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DeadlockLoserDataAccessException;

import java.sql.SQLException;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * DeadlockRetryer 单元测试
 */
public class DeadlockRetryerTest {

    @Test
    public void testSuccessOnFirstAttempt() throws Exception {
        AtomicInteger attempts = new AtomicInteger(0);
        
        String result = DeadlockRetryer.execute(() -> {
            attempts.incrementAndGet();
            return "success";
        });
        
        assertEquals("success", result);
        assertEquals(1, attempts.get());
    }

    @Test
    public void testSuccessAfterRetry() throws Exception {
        AtomicInteger attempts = new AtomicInteger(0);
        
        String result = DeadlockRetryer.execute(() -> {
            int attempt = attempts.incrementAndGet();
            if (attempt < 2) {
                throw new DeadlockLoserDataAccessException("模拟死锁", new SQLException("Deadlock", "40001", 1213));
            }
            return "success";
        });
        
        assertEquals("success", result);
        assertEquals(2, attempts.get());
    }

    @Test
    public void testMaxRetriesExceeded() {
        AtomicInteger attempts = new AtomicInteger(0);
        
        assertThrows(DeadlockLoserDataAccessException.class, () -> {
            DeadlockRetryer.execute(() -> {
                attempts.incrementAndGet();
                throw new DeadlockLoserDataAccessException("模拟死锁", new SQLException("Deadlock", "40001", 1213));
            });
        });
        
        // 默认最大重试次数是3
        assertEquals(3, attempts.get());
    }

    @Test
    public void testNonRetryableException() {
        AtomicInteger attempts = new AtomicInteger(0);
        
        assertThrows(IllegalArgumentException.class, () -> {
            DeadlockRetryer.execute(() -> {
                attempts.incrementAndGet();
                throw new IllegalArgumentException("非死锁异常");
            });
        });
        
        // 非死锁异常不应该重试
        assertEquals(1, attempts.get());
    }

    @Test
    public void testIsDeadlockOrLockTimeout() {
        // 测试 Spring 死锁异常
        assertTrue(DeadlockRetryer.isDeadlockOrLockTimeout(
            new DeadlockLoserDataAccessException("test", null)
        ));
        
        // 测试 MySQL 死锁错误码 1213
        assertTrue(DeadlockRetryer.isDeadlockOrLockTimeout(
            new SQLException("Deadlock", "40001", 1213)
        ));
        
        // 测试 MySQL 锁等待超时错误码 1205
        assertTrue(DeadlockRetryer.isDeadlockOrLockTimeout(
            new SQLException("Lock wait timeout", "41000", 1205)
        ));
        
        // 测试异常消息包含 deadlock
        assertTrue(DeadlockRetryer.isDeadlockOrLockTimeout(
            new RuntimeException("Deadlock found when trying to get lock")
        ));
        
        // 测试普通异常
        assertFalse(DeadlockRetryer.isDeadlockOrLockTimeout(
            new IllegalArgumentException("test")
        ));
        
        // 测试 null
        assertFalse(DeadlockRetryer.isDeadlockOrLockTimeout(null));
    }

    @Test
    public void testCustomRetryConfiguration() throws Exception {
        AtomicInteger attempts = new AtomicInteger(0);
        
        String result = DeadlockRetryer.execute(() -> {
            int attempt = attempts.incrementAndGet();
            if (attempt < 2) {
                throw new SQLException("Deadlock", "40001", 1213);
            }
            return "success";
        }, 5, Duration.ofMillis(10));
        
        assertEquals("success", result);
        assertEquals(2, attempts.get());
    }
}
