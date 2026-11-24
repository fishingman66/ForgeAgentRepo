package com.forgeagent.common.redis.utils;

import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * 定时任务跨实例互斥工具：解决多实例部署下 {@code @Scheduled} 任务重复执行。
 *
 * <p>只给有共享副作用（DB / OSS / 向量库）的任务使用；纯本实例内存清理任务不得用本工具，
 * 否则会阻止其他实例清理各自本地缓存，反而引入缺陷。</p>
 *
 * <p>语义：不等待（tryLock 0），拿不到锁说明别的实例正在处理，本轮直接跳过；
 * 租约到期自动释放，防任务卡死导致永久不再调度。异常不外泄，避免单轮异常中断后续调度。</p>
 */
@Slf4j
public final class ScheduledTaskLock {

    private ScheduledTaskLock() {
    }

    /**
     * 在跨实例互斥下执行任务。
     *
     * @param lockKey 全局唯一锁键，建议以 {@code forge:sched:} 前缀
     * @param lease   锁租约（须大于任务预期耗时，小于调度间隔）
     * @param task    任务体
     * @return true=本实例执行了；false=别的实例持有锁被跳过，或执行出现异常
     */
    public static boolean tryRun(String lockKey, Duration lease, Runnable task) {
        RLock lock;
        try {
            lock = RedisUtils.getClient().getLock(lockKey);
        } catch (Exception | LinkageError e) {
            // Redis 不可用时跳过有副作用的定时任务，比多实例重复删除更安全。
            // LinkageError:纯单测无 Redis 上下文时 RedisUtils 静态初始化会抛 ExceptionInInitializerError,
            // 项目既有静态工具类降级写法同为 Exception | LinkageError。
            log.warn("[调度锁] Redis 不可用，跳过本轮任务 key={}: {}", lockKey, e.getMessage());
            return false;
        }
        boolean acquired = false;
        try {
            acquired = lock.tryLock(0, lease.toMillis(), TimeUnit.MILLISECONDS);
            if (!acquired) {
                return false;
            }
            task.run();
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("[调度锁] 等待锁被中断 key={}", lockKey);
            return false;
        } catch (Exception e) {
            log.warn("[调度锁] 任务执行异常 key={}: {}", lockKey, e.getMessage());
            return false;
        } finally {
            if (acquired && lock.isHeldByCurrentThread()) {
                try {
                    lock.unlock();
                } catch (Exception e) {
                    log.warn("[调度锁] 释放锁失败 key={}: {}", lockKey, e.getMessage());
                }
            }
        }
    }
}
