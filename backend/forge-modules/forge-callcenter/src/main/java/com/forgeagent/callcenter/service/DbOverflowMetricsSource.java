package com.forgeagent.callcenter.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.forgeagent.callcenter.domain.CsCallMetricDaily;
import com.forgeagent.callcenter.mapper.CsCallMetricDailyMapper;
import com.forgeagent.customerservice.domain.CsQueue;
import com.forgeagent.customerservice.mapper.CsQueueMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Date;
import java.util.List;

/**
 * 溢出指标 DB 实现(P0-3):队列长度/等待时长来自 agent_cs_queue,
 * 呼损率来自 agent_cc_metric_daily(abandon_rate),全部按 workspace 精确过滤。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DbOverflowMetricsSource implements OverflowMetricsSource {

    private final CsQueueMapper queueMapper;
    private final CsCallMetricDailyMapper metricMapper;

    @Override
    public int queueSize(Long workspaceId) {
        if (workspaceId == null) {
            return 0;
        }
        try {
            return Math.toIntExact(queueMapper.selectCount(new LambdaQueryWrapper<CsQueue>()
                .eq(CsQueue::getWorkspaceId, workspaceId)
                .eq(CsQueue::getState, "WAITING")));
        } catch (Exception e) {
            log.warn("[CC] 溢出队列长度查询失败 ws={}: {}", workspaceId, e.getMessage());
            return 0;
        }
    }

    @Override
    public long maxWaitSeconds(Long workspaceId) {
        if (workspaceId == null) {
            return 0;
        }
        try {
            List<CsQueue> waiting = queueMapper.selectList(new LambdaQueryWrapper<CsQueue>()
                .eq(CsQueue::getWorkspaceId, workspaceId)
                .eq(CsQueue::getState, "WAITING"));
            long now = System.currentTimeMillis();
            long max = 0;
            for (CsQueue q : waiting) {
                Date at = q.getEnqueuedAt();
                if (at != null) {
                    max = Math.max(max, (now - at.getTime()) / 1000);
                }
            }
            return max;
        } catch (Exception e) {
            log.warn("[CC] 溢出等待时长查询失败 ws={}: {}", workspaceId, e.getMessage());
            return 0;
        }
    }

    @Override
    public int abandonRatePercent(Long workspaceId) {
        if (workspaceId == null) {
            return 0;
        }
        try {
            Date today = Date.from(LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant());
            CsCallMetricDaily row = metricMapper.selectOne(new LambdaQueryWrapper<CsCallMetricDaily>()
                .eq(CsCallMetricDaily::getWorkspaceId, workspaceId)
                .eq(CsCallMetricDaily::getStatDate, today)
                .last("limit 1"));
            if (row == null || row.getAbandonRate() == null) {
                return 0;
            }
            return row.getAbandonRate().intValue();
        } catch (Exception e) {
            log.warn("[CC] 溢出呼损率查询失败 ws={}: {}", workspaceId, e.getMessage());
            return 0;
        }
    }
}
