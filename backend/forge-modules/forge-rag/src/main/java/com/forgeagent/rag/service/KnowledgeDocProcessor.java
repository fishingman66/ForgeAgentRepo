package com.forgeagent.rag.service;

/**
 * 知识库文档异步处理器:解析 -> 分块 -> 向量化 -> 写入向量库
 *
 * @author ForgeAgent
 */
public interface KnowledgeDocProcessor {

    /**
     * 异步处理文档(沿用文档当前生效代次,原地重建)
     */
    void processAsync(Long documentId);

    /**
     * 异步按指定代次重建文档分块
     *
     * <p>重建走"新代写成功再切指针":调用方先触发本方法,由处理器在成功后把
     * {@code active_generation} 切到新代并清理旧代,期间旧代照常可检索。</p>
     *
     * @param documentId 文档ID
     * @param generation 目标代次(必须大于当前生效代次)
     */
    void processGenerationAsync(Long documentId, int generation);
}
