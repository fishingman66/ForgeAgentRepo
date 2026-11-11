package com.forgeagent.agent.api.customerservice.impl;

import com.forgeagent.agent.api.customerservice.CsKnowledgeGateway;
import com.forgeagent.rag.domain.bo.KnowledgeSearchBo;
import com.forgeagent.rag.domain.vo.KnowledgeSearchHitVo;
import com.forgeagent.rag.service.IAgentKnowledgeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 客服检索契约实现(C0):唯一允许在无登录上下文调用 searchInternal 的入口。
 *
 * <p>每个知识库单独检索,并强制写入 callerWorkspaceId(跨空间读取由 searchInternal 拒绝)。
 * 单库异常按零命中处理并告警,绝不降级为「让模型自由发挥」。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CsKnowledgeGatewayImpl implements CsKnowledgeGateway {

    private final IAgentKnowledgeService knowledgeService;

    @Override
    public List<KnowledgeSearchHitVo> search(Long callerWorkspaceId, List<Long> knowledgeIds, String query) {
        if (knowledgeIds == null || knowledgeIds.isEmpty() || query == null || query.isBlank()) {
            return List.of();
        }
        String safeQuery = query.length() > 1000 ? query.substring(0, 1000) : query;
        List<KnowledgeSearchHitVo> hits = new ArrayList<>();
        for (Long knowledgeId : knowledgeIds) {
            KnowledgeSearchBo bo = new KnowledgeSearchBo();
            bo.setKnowledgeId(knowledgeId);
            bo.setQuery(safeQuery);
            bo.setCallerWorkspaceId(callerWorkspaceId);
            try {
                List<KnowledgeSearchHitVo> part = knowledgeService.searchInternal(bo);
                if (part != null) {
                    hits.addAll(part);
                }
            } catch (Exception e) {
                log.warn("[CS] 知识库检索失败 knowledgeId={} workspaceId={}: {}",
                    knowledgeId, callerWorkspaceId, e.getMessage());
            }
        }
        return hits;
    }

    @Override
    public void recordZeroHit(Long agentId, Long sessionId, Long userId,
                              String query, List<Long> knowledgeIds) {
        try {
            knowledgeService.recordZeroHit(agentId, sessionId, userId, query, query, knowledgeIds);
        } catch (Exception e) {
            log.warn("[CS] 零命中日志写入失败: {}", e.getMessage());
        }
    }
}
