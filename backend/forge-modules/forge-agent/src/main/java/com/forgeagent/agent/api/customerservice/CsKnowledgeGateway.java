package com.forgeagent.agent.api.customerservice;

import com.forgeagent.rag.domain.vo.KnowledgeSearchHitVo;

import java.util.List;

/**
 * 智能客服 → RAG 的检索契约(S0)。
 *
 * <p>唯一允许在无登录上下文调用 {@code searchInternal} 的入口;实现内部必须写入
 * {@code KnowledgeSearchBo.callerWorkspaceId} 并校验知识库白名单,
 * 不得把 searchInternal 直接暴露成 Controller 端点。</p>
 */
public interface CsKnowledgeGateway {

    /** 免登录检索:限定空间与知识库白名单,返回命中分片。 */
    List<KnowledgeSearchHitVo> search(Long callerWorkspaceId, List<Long> knowledgeIds, String query);

    /** 记录零命中查询,供知识缺口运营(C0 起)。 */
    void recordZeroHit(Long agentId, Long sessionId, Long userId, String query, List<Long> knowledgeIds);
}
