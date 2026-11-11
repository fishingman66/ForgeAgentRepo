package com.forgeagent.agent.service;

import lombok.extern.slf4j.Slf4j;
import com.forgeagent.agent.domain.vo.AgentTriggerVo;
import com.forgeagent.agent.event.TriggerChangedEvent;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 触发器调度器:基于 Spring {@link ThreadPoolTaskScheduler} 动态注册/注销 Cron 任务
 *
 * <p>应用启动后加载启用中的定时触发器,配置变更时通过事件驱动全量刷新。
 * 不依赖外部调度中间件,进程内自包含。</p>
 *
 * <p>体检 R1:调度线程只做投递,长执行(工作流 LLM 单节点可达 300s)交给独立有界线程池。
 * 此前 4 个长 cron 并行即可占满调度池,第 5 个触发器到点不执行;投递队列满时跳过本次触发
 * (G3 幂等键已保证同分钟不重复),并记录告警,不做堆积导致延迟无上限。</p>
 *
 * @author ForgeAgent
 */
@Slf4j
@Component
public class TriggerScheduler implements ApplicationRunner {

    private final IAgentTriggerService triggerService;
    private final ThreadPoolTaskScheduler scheduler;
    private final Map<Long, ScheduledFuture<?>> tasks = new ConcurrentHashMap<>();

    /** 长执行投递池:2-4 线程 + 32 队列,满则拒绝(放弃本次触发,不排队) */
    private static final ExecutorService CRON_DISPATCH = new ThreadPoolExecutor(
        2, 4, 60L, TimeUnit.SECONDS, new ArrayBlockingQueue<>(32),
        r -> {
            Thread t = new Thread(r, "agent-cron-run");
            t.setDaemon(true);
            return t;
        }, new ThreadPoolExecutor.AbortPolicy());

    public TriggerScheduler(IAgentTriggerService triggerService) {
        this.triggerService = triggerService;
        ThreadPoolTaskScheduler pool = new ThreadPoolTaskScheduler();
        pool.setPoolSize(4);
        pool.setThreadNamePrefix("agent-trigger-");
        pool.setDaemon(true);
        pool.setWaitForTasksToCompleteOnShutdown(false);
        pool.initialize();
        this.scheduler = pool;
    }

    @Override
    public void run(ApplicationArguments args) {
        refreshAll();
    }

    /**
     * 配置变更后刷新:新增启用项注册、停用或删除项注销
     */
    @EventListener
    public void onChange(TriggerChangedEvent event) {
        refreshAll();
    }

    /**
     * 全量刷新 Cron 任务注册(幂等,可重复调用)
     */
    public synchronized void refreshAll() {
        try {
            List<AgentTriggerVo> enabled = triggerService.listEnabledCronTriggers();
            Set<Long> keep = new HashSet<>();
            for (AgentTriggerVo t : enabled) {
                keep.add(t.getId());
                if (tasks.containsKey(t.getId())) {
                    continue;
                }
                register(t);
            }
            tasks.keySet().stream().filter(id -> !keep.contains(id)).toList().forEach(this::cancel);
            log.info("[触发器] 调度刷新完成,当前注册 {} 个定时任务", tasks.size());
        } catch (Exception e) {
            log.error("[触发器] 调度刷新失败: {}", e.getMessage());
        }
    }

    private void register(AgentTriggerVo t) {
        try {
            CronTrigger cron = new CronTrigger(t.getCronExpr());
            ScheduledFuture<?> future = scheduler.schedule(() -> {
                // 体检 R1:仅投递,调度线程立即归还;长执行异常不外泄到调度器
                try {
                    CRON_DISPATCH.execute(() -> {
                        try {
                            triggerService.executeCron(t.getId());
                        } catch (Exception e) {
                            log.warn("[触发器] 定时任务 {} 执行异常: {}", t.getName(), e.getMessage());
                        }
                    });
                } catch (RejectedExecutionException full) {
                    log.warn("[触发器] 执行队列已满,跳过本次触发 {}", t.getName());
                }
            }, cron);
            if (future != null) {
                tasks.put(t.getId(), future);
                log.info("[触发器] 注册定时任务 {} ({})", t.getName(), t.getCronExpr());
            }
        } catch (Exception e) {
            log.warn("[触发器] Cron 表达式非法,跳过注册 {}: {}", t.getName(), e.getMessage());
        }
    }

    private void cancel(Long triggerId) {
        ScheduledFuture<?> future = tasks.remove(triggerId);
        if (future != null) {
            future.cancel(false);
        }
    }
}
