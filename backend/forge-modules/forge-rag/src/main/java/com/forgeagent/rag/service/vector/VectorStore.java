package com.forgeagent.rag.service.vector;

import java.util.List;
/**
 * 向量存储抽象(按 knowledge_id 过滤隔离)
 *
 * @author ForgeAgent
 */
public interface VectorStore {

    /**
     * 确保主集合存在(维度取 {@code rag.knowledge.vector-dim};MySQL 后端为空实现)
     */
    void ensureCollection();

    /**
     * 确保指定维度的集合存在,不存在则创建
     *
     * <p>向量库只允许单一维度:知识库各自配置嵌入模型时,维度可能不同,
     * 实现需按维度隔离(见 {@link VectorCollections}),不能共用一份集合。</p>
     *
     * @param dim 向量维度
     */
    void ensureCollection(int dim);

    /**
     * 批量插入/更新向量
     *
     * @param knowledgeId 知识库ID(用于过滤隔离)
     * @param documentId  文档ID
     * @param chunkIds    分块主键列表(与 vectors 一一对应)
     * @param vectors     向量列表
     */
    void upsert(Long knowledgeId, Long documentId, List<Long> chunkIds, List<float[]> vectors);

    /**
     * 按文档ID删除向量
     */
    void deleteByDocumentIds(List<Long> documentIds);

    /**
     * 按知识库ID删除向量
     */
    void deleteByKnowledgeId(Long knowledgeId);

    /**
     * 按分块ID精确删除向量。
     *
     * <p>清理"上一代"分块时必须用这个而不是 {@link #deleteByDocumentIds}:后者会把同文档
     * 新写入的那一代一起删掉(向量库行里没有代次字段,只能按主键精确删)。</p>
     */
    void deleteByChunkIds(List<Long> chunkIds);

    /**
     * 向量相似度检索(不过滤)
     */
    List<VectorHit> search(Long knowledgeId, float[] queryVector, int topK);

    /**
     * 向量相似度检索(带文档可见性过滤,过滤在向量库侧下推,不做召回后过滤)
     *
     * @param knowledgeId 知识库ID
     * @param queryVector 查询向量
     * @param topK        返回条数
     * @param filter      文档过滤;null 表示不过滤
     * @return 命中列表(按相关度降序)
     */
    List<VectorHit> search(Long knowledgeId, float[] queryVector, int topK, DocFilter filter);

    /**
     * 文档过滤条件:ids 为隐藏集(include=false → not in)或允许集(include=true → in)
     */
    record DocFilter(List<Long> ids, boolean include) {
    }

    /**
     * 检索命中条目
     */
    record VectorHit(Long id, Long documentId, Float score) {
    }
}
