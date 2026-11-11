package com.forgeagent.rag.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
/**
 * 知识库模块异步线程池配置
 *
 * @author ForgeAgent
 */
@Configuration
public class KnowledgeAsyncConfig {

    /**
     * 知识库文档解析/向量化线程池
     */
    @Bean("knowledgeDocExecutor")
    public Executor knowledgeDocExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("kb-doc-");
        executor.initialize();
        return executor;
    }

    /**
     * 文档解析线程池:与摄取主线程池分开。
     *
     * <p>解析是 CPU 密集(几十分钟的坏文件也常见),嵌入是 IO 等待;混在一个池里会互相拖慢。
     * 队列很短:解析任务本该很快,堆队列只会在坏文件上变成雪崩。</p>
     */
    @Bean(name = "knowledgeParseExecutor", destroyMethod = "shutdown")
    public ExecutorService knowledgeParseExecutor() {
        return new java.util.concurrent.ThreadPoolExecutor(
            2, 2, 60L, java.util.concurrent.TimeUnit.SECONDS,
            new java.util.concurrent.LinkedBlockingQueue<>(8),
            r -> {
                Thread t = new Thread(r, "kb-parse");
                t.setDaemon(true);
                return t;
            },
            new java.util.concurrent.ThreadPoolExecutor.AbortPolicy());
    }

    /**
     * 精排专用线程池:队列为 0 + 拒绝策略。
     *
     * <p>精排有硬超时,排队会白烧掉调用方的超时预算——排不进去就应该立刻失败并回退粗排,
     * 而不是在队列里等到超时才发现没意义。</p>
     */
    @Bean(name = "kbRerankExecutor", destroyMethod = "shutdown")
    public ExecutorService kbRerankExecutor() {
        return new java.util.concurrent.ThreadPoolExecutor(
            2, 4, 60L, java.util.concurrent.TimeUnit.SECONDS,
            new java.util.concurrent.SynchronousQueue<>(),
            r -> {
                Thread t = new Thread(r, "kb-rerank");
                t.setDaemon(true);
                return t;
            },
            new java.util.concurrent.ThreadPoolExecutor.AbortPolicy());
    }

    /**
     * 知识库统计类异步执行器(命中计数/零命中日志等低频写,单线程串行避免行锁竞争)
     */
    @Bean(name = "kbHitCountExecutor", destroyMethod = "shutdown")
    public ExecutorService kbHitCountExecutor() {
        return Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "kb-hit-count");
            t.setDaemon(true);
            return t;
        });
    }
}
