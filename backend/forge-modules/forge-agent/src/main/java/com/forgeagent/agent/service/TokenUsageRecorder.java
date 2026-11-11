package com.forgeagent.agent.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import com.forgeagent.agent.domain.AgentTokenUsage;
import com.forgeagent.agent.domain.AgentTokenUsageDaily;
import com.forgeagent.agent.mapper.AgentTokenUsageDailyMapper;
import com.forgeagent.agent.mapper.AgentTokenUsageMapper;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

/**
 * Token 用量异步采集器:明细落库 + 日汇总聚合
 * <p>异步写入不阻塞会话主流程,写入失败仅记录日志,不影响 Agent 执行</p>
 *
 * @author ForgeAgent
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TokenUsageRecorder {

    private final AgentTokenUsageMapper usageMapper;
    private final AgentTokenUsageDailyMapper dailyMapper;

    /**
     * 异步记录一条模型调用用量
     *
     * @param usage 用量明细(字段需在调用方线程内组装完毕)
     */
    @Async
    public void record(AgentTokenUsage usage) {
        try {
            usageMapper.insert(usage);
            aggregateDaily(usage);
            // 会话用量实时反馈(UX-03):落库后失效 15s 聚合缓存,下次查询立即拿到最新值
            if (usage.getSessionId() != null) {
                com.forgeagent.common.redis.utils.RedisUtils.deleteObject(
                    com.forgeagent.agent.service.impl.AgentTokenUsageServiceImpl.sessionAggCacheKey(usage.getSessionId()));
            }
        } catch (Exception e) {
            log.error("Token 用量写入失败,已跳过: agentId={}, sessionId={}, err={}",
                usage.getAgentId(), usage.getSessionId(), e.getMessage());
        }
    }

    /**
     * 聚合到日汇总表:单条 INSERT ... ON DUPLICATE KEY UPDATE 原子累加(依赖 uk_dim 唯一键)
     */
    private void aggregateDaily(AgentTokenUsage usage) {
        LocalDate statDate = usage.getCallTime() == null ? LocalDate.now() : usage.getCallTime().toLocalDate();
        AgentTokenUsageDaily daily = new AgentTokenUsageDaily();
        daily.setStatDate(statDate);
        daily.setWorkspaceId(usage.getWorkspaceId());
        daily.setAgentId(usage.getAgentId());
        daily.setModelName(usage.getModelName() == null ? "" : usage.getModelName());
        daily.setUserId(usage.getUserId());
        daily.setSource(usage.getSource() == null ? "chat" : usage.getSource());
        daily.setEstimated(usage.getEstimated() == null ? "1" : usage.getEstimated());
        daily.setPromptTokens(nullToZero(usage.getPromptTokens()));
        daily.setCompletionTokens(nullToZero(usage.getCompletionTokens()));
        daily.setTotalTokens(nullToZero(usage.getTotalTokens()));
        dailyMapper.upsertDaily(daily);
    }

    private int nullToZero(Integer v) {
        return v == null ? 0 : v;
    }
}
