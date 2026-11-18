package com.forgeagent.agent.api.customerservice;

import com.forgeagent.rag.domain.vo.KnowledgeSearchHitVo;

import java.util.List;
import java.util.Map;

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

    /**
     * A2:链路同构检索——走 ChatRetrievalSupport 完整管线(阈值 / 跨库 RRF / 邻块扩展 /
     * 父块注入 / token 预算),产出可直接交回对话链复用的结果,客服闸门与模型应答同源。
     * 免登录口径:callerWorkspaceId 归属校验,不依赖 Sa-Token。
     */
    RetrievalOutcome retrieve(Long callerWorkspaceId, List<Long> knowledgeIds, String query);

    /**
     * 链路同构检索结果:hits 与 contextByChunk(邻块/父块上下文)成对复用,
     * 单独只拿 hits 会丢失资料块上下文,禁止拆开传
     */
    record RetrievalOutcome(List<KnowledgeSearchHitVo> hits, Map<Long, String> contextByChunk) {
    }

    /** 记录零命中查询,供知识缺口运营(C0 起)。 */
    void recordZeroHit(Long agentId, Long sessionId, Long userId, String query, List<Long> knowledgeIds);
}
