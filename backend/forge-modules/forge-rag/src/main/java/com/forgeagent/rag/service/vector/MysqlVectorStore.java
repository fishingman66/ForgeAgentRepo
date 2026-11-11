package com.forgeagent.rag.service.vector;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.forgeagent.common.core.exception.ServiceException;
import com.forgeagent.rag.config.KnowledgeProperties;
import com.forgeagent.rag.domain.AgentKbChunk;
import com.forgeagent.rag.mapper.AgentKbChunkMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * MySQL 向量存储(本地开发用):向量存 {@code agent_kb_chunk.embedding},检索时应用层算余弦。
 *
 * <p><b>为什么有这个实现</b>:开发环境不装 Milvus 时,RAG 链路整段不可用——检索恒为空,
 * 对话永远走兜底,连"知识库到底有没有生效"都验证不了。这里把向量存进已有的分块表,
 * 让 MySQL 一个依赖撑起全链路。</p>
 *
 * <p><b>边界</b>:检索是 O(n) 全扫,几千分块约几十毫秒;超过 {@link #SCAN_LIMIT} 会被截断
 * 并告警——那时该上真正的向量库,而不是继续优化 SQL。生产请用 {@code vector-store=milvus}。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "rag.knowledge.vector-store", havingValue = "mysql")
public class MysqlVectorStore implements VectorStore {

    /** 单次检索最多扫描多少分块(防超大知识库把堆和响应时间拉爆)。 */
    private static final int SCAN_LIMIT = 5000;

    private final AgentKbChunkMapper chunkMapper;
    private final com.forgeagent.rag.mapper.AgentKnowledgeDocumentMapper documentMapper;
    private final KnowledgeProperties properties;

    /** 已提示过的维度,避免每篇文章都刷一行日志 */
    private final java.util.Set<Integer> loggedDims = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** 向量列随分块表存在,没有集合要建;这里只打一行日志说明当前走的是哪种存储。 */
    @Override
    public void ensureCollection() {
        ensureCollection(properties.getVectorDim() == null ? 0 : properties.getVectorDim());
    }

    @Override
    public void ensureCollection(int dim) {
        if (loggedDims.add(dim)) {
            log.info("[Vector] 使用 MySQL 向量列(dim={}),跳过集合初始化", dim);
        }
    }

    @Override
    public void upsert(Long knowledgeId, Long documentId, List<Long> chunkIds, List<float[]> vectors) {
        if (chunkIds == null || vectors == null || chunkIds.size() != vectors.size()) {
            throw new ServiceException("向量数量与分块数量不一致");
        }
        int written = 0;
        for (int i = 0; i < chunkIds.size(); i++) {
            float[] vector = vectors.get(i);
            if (vector == null || vector.length == 0) {
                continue;
            }
            // 维度按向量自身长度落库(不再与全局配置比对):检索侧按查询向量长度过滤,
            // 换模型后老向量自动失效而不会算出错误分数,也不再因为全局维度配错整批跳过
            chunkMapper.update(null, new LambdaUpdateWrapper<AgentKbChunk>()
                .eq(AgentKbChunk::getId, chunkIds.get(i))
                .set(AgentKbChunk::getEmbedding, VectorCodec.encode(vector))
                .set(AgentKbChunk::getEmbeddingDim, vector.length));
            written++;
        }
        log.debug("[Vector] 写入向量 documentId={} count={}", documentId, written);
    }

    @Override
    public void deleteByDocumentIds(List<Long> documentIds) {
        if (documentIds == null || documentIds.isEmpty()) {
            return;
        }
        // 分块行通常随后被删,这里只清向量列,避免"分块还在但向量已废"的中间态
        chunkMapper.update(null, new LambdaUpdateWrapper<AgentKbChunk>()
            .in(AgentKbChunk::getDocumentId, documentIds)
            .set(AgentKbChunk::getEmbedding, null)
            .set(AgentKbChunk::getEmbeddingDim, null));
    }

    @Override
    public void deleteByKnowledgeId(Long knowledgeId) {
        if (knowledgeId == null) {
            return;
        }
        chunkMapper.update(null, new LambdaUpdateWrapper<AgentKbChunk>()
            .eq(AgentKbChunk::getKnowledgeId, knowledgeId)
            .set(AgentKbChunk::getEmbedding, null)
            .set(AgentKbChunk::getEmbeddingDim, null));
    }

    @Override
    public void deleteByChunkIds(List<Long> chunkIds) {
        if (chunkIds == null || chunkIds.isEmpty()) {
            return;
        }
        // 分批清空向量列:IN 列表过大会被驱动拒绝
        for (int from = 0; from < chunkIds.size(); from += 500) {
            List<Long> batch = chunkIds.subList(from, Math.min(from + 500, chunkIds.size()));
            chunkMapper.update(null, new LambdaUpdateWrapper<AgentKbChunk>()
                .in(AgentKbChunk::getId, batch)
                .set(AgentKbChunk::getEmbedding, null)
                .set(AgentKbChunk::getEmbeddingDim, null));
        }
    }

    @Override
    public List<VectorHit> search(Long knowledgeId, float[] queryVector, int topK) {
        if (knowledgeId == null || queryVector == null || queryVector.length == 0 || topK <= 0) {
            return List.of();
        }
        List<AgentKbChunk> rows = chunkMapper.selectList(new LambdaQueryWrapper<AgentKbChunk>()
            .eq(AgentKbChunk::getKnowledgeId, knowledgeId)
            // enabled 可能为 NULL(历史数据),按"未显式禁用即参与"的口径处理
            .and(w -> w.isNull(AgentKbChunk::getEnabled).or().eq(AgentKbChunk::getEnabled, 1))
            // 父块只提供上下文,不参与召回
            .and(w -> w.isNull(AgentKbChunk::getChunkRole).or().ne(AgentKbChunk::getChunkRole, "p"))
            .isNotNull(AgentKbChunk::getEmbedding)
            .select(AgentKbChunk::getId, AgentKbChunk::getDocumentId, AgentKbChunk::getEmbedding,
                AgentKbChunk::getEmbeddingDim, AgentKbChunk::getGeneration)
            .last("limit " + SCAN_LIMIT));
        if (rows.isEmpty()) {
            return List.of();
        }
        // 只保留"文档当前生效代次"的分块:重建期间新代已落库但未切指针,不能参与召回。
        // 多一次文档查询换正确性——向量库本身不存代次,只能在应用层裁决
        java.util.Set<Long> docIds = rows.stream().map(AgentKbChunk::getDocumentId)
            .filter(java.util.Objects::nonNull).collect(java.util.stream.Collectors.toSet());
        java.util.Map<Long, Integer> activeGeneration = documentMapper.selectByIds(docIds).stream()
            .collect(java.util.stream.Collectors.toMap(
                com.forgeagent.rag.domain.AgentKnowledgeDocument::getId,
                d -> d.getActiveGeneration() == null ? 1 : d.getActiveGeneration()));
        if (rows.size() >= SCAN_LIMIT) {
            log.warn("[Vector] 知识库 {} 分块数达到扫描上限 {}，结果可能不完整，建议改用 Milvus",
                knowledgeId, SCAN_LIMIT);
        }
        List<VectorHit> hits = new ArrayList<>(rows.size());
        for (AgentKbChunk row : rows) {
            // 维度不一致说明是换模型前的老向量,跳过而不是算个错分数
            if (row.getEmbeddingDim() != null && row.getEmbeddingDim() != queryVector.length) {
                continue;
            }
            Integer active = activeGeneration.get(row.getDocumentId());
            int generation = row.getGeneration() == null ? 1 : row.getGeneration();
            if (active == null || active != generation) {
                // 文档已删或该代次已过期(重建中的新代/待清理的旧代)
                continue;
            }
            float[] vector = VectorCodec.decode(row.getEmbedding());
            double score = VectorCodec.cosine(queryVector, vector);
            if (score == 0) {
                continue;
            }
            hits.add(new VectorHit(row.getId(), row.getDocumentId(), (float) score));
        }
        // 同分按主键升序破平:否则同一 query 两次检索的名次会变评测无法复现
        hits.sort(Comparator.comparing(VectorHit::score).reversed()
            .thenComparing(VectorHit::id, Comparator.nullsLast(Comparator.naturalOrder())));
        return hits.size() > topK ? new ArrayList<>(hits.subList(0, topK)) : hits;
    }
}
