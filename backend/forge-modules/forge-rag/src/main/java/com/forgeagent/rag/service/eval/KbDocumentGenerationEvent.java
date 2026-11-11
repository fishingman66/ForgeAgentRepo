package com.forgeagent.rag.service.eval;

/**
 * 文档换代/删除事件(R3-1 自动打标):文档重建切换生效代次或被删除后发布,
 * 由评测服务监听并对其引用该文档的用例重跑覆盖判定,不达标标 EVIDENCE_STALE。
 *
 * <p>用事件而不是直接注入 KbEvalService:Processor → KbEvalService →
 * IAgentKnowledgeService 会构成循环依赖。</p>
 *
 * @param knowledgeId 知识库ID
 * @param documentId  文档ID
 * @author ForgeAgent
 */
public record KbDocumentGenerationEvent(Long knowledgeId, Long documentId) {
}
