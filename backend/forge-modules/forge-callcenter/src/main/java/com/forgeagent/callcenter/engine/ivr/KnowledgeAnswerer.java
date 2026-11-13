package com.forgeagent.callcenter.engine.ivr;

/**
 * 知识库问答 SPI(C3):agent 节点的回答引擎。
 *
 * <p>默认实现 {@link FaqKnowledgeAnswerer} 包装客服域 FAQ 精确/包含匹配;
 * RAG 全文检索(forge-rag)与 LLM 生成由后续批次按方案 §四 P0-1② 接入
 * (依赖方向:callcenter → customerservice 合法,直连 rag 需评审)。</p>
 */
public interface KnowledgeAnswerer {

    /**
     * 按问题取答案;未命中返回 null(agent 节点走 noAnswerNextId 分支)。
     */
    String answer(Long workspaceId, String question);
}
