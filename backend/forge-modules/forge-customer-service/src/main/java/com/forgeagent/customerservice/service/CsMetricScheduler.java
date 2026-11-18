package com.forgeagent.customerservice.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.forgeagent.customerservice.config.ConditionalOnCustomerServiceEnabled;
import com.forgeagent.customerservice.config.CsModuleProperties;
import com.forgeagent.customerservice.domain.CsConversation;
import com.forgeagent.customerservice.mapper.CsConversationMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * B3:客服日指标定时重建(默认关,独立 boolean 开关——
 * 与全项目纪律一致:调度参数不承担「0=关闭」语义,避免配 0 抛异常起不来)。
 *
 * <p>每日 04:00(与留存清理 03:00 错峰)重建前一日的全渠道 + 分渠道指标;
 * 逐空间执行,单空间失败不影响其他。开关与 cron 都在
 * {@code agent.customer-service.metric-rebuild-*} 下调整。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnCustomerServiceEnabled
public class CsMetricScheduler {

    private final CsConversationMapper conversationMapper;
    private final CsMetricService metricService;
    private final CsModuleProperties properties;

    @Scheduled(cron = "${agent.customer-service.metric-rebuild-cron:0 0 4 * * ?}")
    public void rebuildYesterday() {
        if (!Boolean.TRUE.equals(properties.getMetricRebuildEnabled())) {
            return;
        }
        Date yesterday = dayStart(LocalDate.now(ZoneId.systemDefault()).minusDays(1));
        Set<Long> workspaceIds = distinctWorkspaceIds();
        for (Long workspaceId : workspaceIds) {
            try {
                metricService.rebuild(workspaceId, yesterday);
            } catch (Exception e) {
                log.warn("[CS] 指标定时重建失败 workspaceId={}: {}", workspaceId, e.getMessage());
            }
        }
        log.info("[CS] 指标定时重建完成 statDate={} workspaces={}", yesterday, workspaceIds.size());
    }

    private Set<Long> distinctWorkspaceIds() {
        Set<Long> ids = new HashSet<>();
        List<CsConversation> rows = conversationMapper.selectList(
            new LambdaQueryWrapper<CsConversation>()
                .select(CsConversation::getWorkspaceId)
                .groupBy(CsConversation::getWorkspaceId));
        for (CsConversation row : rows) {
            if (row.getWorkspaceId() != null) {
                ids.add(row.getWorkspaceId());
            }
        }
        return ids;
    }

    private static Date dayStart(LocalDate day) {
        return Date.from(day.atStartOfDay(ZoneId.systemDefault()).toInstant());
    }
}
