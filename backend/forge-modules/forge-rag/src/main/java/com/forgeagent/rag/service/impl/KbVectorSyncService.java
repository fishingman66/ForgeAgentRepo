package com.forgeagent.rag.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.forgeagent.common.core.utils.StringUtils;
import com.forgeagent.rag.config.KnowledgeProperties;
import com.forgeagent.rag.domain.AgentKbChunk;
import com.forgeagent.rag.domain.AgentKnowledge;
import com.forgeagent.rag.domain.AgentKnowledgeDocument;
import com.forgeagent.rag.mapper.AgentKbChunkMapper;
import com.forgeagent.rag.mapper.AgentKnowledgeDocumentMapper;
import com.forgeagent.rag.mapper.AgentKnowledgeMapper;
import com.forgeagent.rag.service.embedding.EmbeddingFactory;
import com.forgeagent.rag.service.embedding.EmbeddingService;
import com.forgeagent.rag.service.vector.VectorStore;
import com.forgeagent.rag.service.vector.VectorSyncStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 分块向量写入的最终一致性补偿。
 *
 * <p><b>解决什么</b>:分块先落 MySQL、再写向量库,任何一次网络抖动/向量库重启都会让两者不一致。
 * 此前写失败只打一行 warn,文档照样标"可用"——该文档此后永远只有关键词召回,且没人知道。
 * 现在失败分块标 {@code FAILED},这里定时重试;超过重试上限则保留 FAILED 交给人工。</p>
 *
 * <p><b>顺带清理过期代次</b>:重建成功但进程在"切代次后、删旧代前"挂掉时,旧代分块会残留。
 * 这些行不会被检索到(检索只认生效代次),但会白占空间与向量库容量,这里兜底删除。</p>
 *
 * <p>开关:{@code rag.knowledge.sync.compensation-enabled}(默认 true);
 * 扫描间隔 {@code rag.knowledge.sync.compensation-interval-ms}(默认 30s)。
 * 注意间隔不能配 0——@Scheduled 会抛 IllegalArgumentException 导致启动失败。</p>
 *
 * @author ForgeAgent
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KbVectorSyncService {

    /**
     * 单次嵌入请求的分块数(与摄取侧批次保持同一量级)
     */
    private static final int EMBED_BATCH = 16;

    /**
     * 单轮最多清理多少条过期代次分块(避免长时间占用扫描线程)
     */
    private static final int STALE_CLEAN_LIMIT = 200;

    private final AgentKbChunkMapper chunkMapper;
    private final AgentKnowledgeDocumentMapper documentMapper;
    private final AgentKnowledgeMapper knowledgeMapper;
    private final EmbeddingFactory embeddingFactory;
    private final VectorStore vectorStore;
    private final KnowledgeProperties properties;

    @Scheduled(fixedDelayString = "${rag.knowledge.sync.compensation-interval-ms:30000}",
        initialDelayString = "${rag.knowledge.sync.compensation-initial-delay-ms:20000}")
    public void compensate() {
        if (!Boolean.TRUE.equals(properties.getSyncCompensationEnabled())) {
            return;
        }
        try {
            int cleaned = cleanupStaleGenerations();
            int synced = retryVectorWrites();
            if (cleaned > 0 || synced > 0) {
                log.info("[知识库] 向量补偿:清理过期代次 {} 条,补写向量 {} 条", cleaned, synced);
            }
        } catch (Exception e) {
            // 补偿本身失败不能拖垮应用:下一轮重试
            log.warn("[知识库] 向量补偿扫描失败: {}", e.getMessage());
        }
    }

    /**
     * 重试 PENDING(超期未完成)与 FAILED 的分块向量写入
     *
     * @return 本轮成功补写的分块数
     */
    private int retryVectorWrites() {
        int batch = properties.getSyncCompensationBatchSize() == null
            ? 200 : Math.max(properties.getSyncCompensationBatchSize(), 1);
        int maxRetry = properties.getSyncCompensationMaxRetry() == null
            ? 5 : Math.max(properties.getSyncCompensationMaxRetry(), 1);
        int pendingMinutes = properties.getSyncPendingTimeoutMinutes() == null
            ? 5 : Math.max(properties.getSyncPendingTimeoutMinutes(), 1);
        Date cutoff = new Date(System.currentTimeMillis() - pendingMinutes * 60_000L);

        List<AgentKbChunk> rows = chunkMapper.selectList(new LambdaQueryWrapper<AgentKbChunk>()
            .select(AgentKbChunk::getId, AgentKbChunk::getKnowledgeId, AgentKbChunk::getDocumentId,
                AgentKbChunk::getContent, AgentKbChunk::getGeneration,
                AgentKbChunk::getVectorStatus, AgentKbChunk::getVectorRetryCount)
            .in(AgentKbChunk::getVectorStatus, VectorSyncStatus.PENDING, VectorSyncStatus.FAILED)
            .lt(AgentKbChunk::getVectorRetryCount, maxRetry)
            // FAILED 立刻重试;PENDING 要过了宽容期,避免和正在进行的写入打架
            .and(w -> w.eq(AgentKbChunk::getVectorStatus, VectorSyncStatus.FAILED)
                .or().lt(AgentKbChunk::getCreateTime, cutoff))
            .orderByAsc(AgentKbChunk::getId)
            .last("limit " + batch));
        if (rows.isEmpty()) {
            return 0;
        }

        int synced = 0;
        Map<Long, List<AgentKbChunk>> byDocument = rows.stream()
            .filter(c -> c.getDocumentId() != null)
            .collect(Collectors.groupingBy(AgentKbChunk::getDocumentId));
        for (Map.Entry<Long, List<AgentKbChunk>> entry : byDocument.entrySet()) {
            synced += retryDocument(entry.getKey(), entry.getValue());
        }
        return synced;
    }

    /**
     * 按文档重试:先剔除代次已过期的分块(不必再嵌),再按批嵌入并写入
     */
    private int retryDocument(Long documentId, List<AgentKbChunk> candidates) {
        AgentKnowledgeDocument doc = documentMapper.selectById(documentId);
        if (doc == null) {
            // 文档已删除,分块是孤儿:直接清掉,别让补偿扫描一直捞它
            deleteChunks(candidates.stream().map(AgentKbChunk::getId).toList());
            return 0;
        }
        int activeGeneration = doc.getActiveGeneration() == null ? 1 : doc.getActiveGeneration();
        List<Long> staleIds = new ArrayList<>();
        List<AgentKbChunk> alive = new ArrayList<>();
        for (AgentKbChunk chunk : candidates) {
            int generation = chunk.getGeneration() == null ? 1 : chunk.getGeneration();
            if (generation < activeGeneration) {
                staleIds.add(chunk.getId());
            } else {
                alive.add(chunk);
            }
        }
        if (!staleIds.isEmpty()) {
            markStatus(staleIds, VectorSyncStatus.SKIPPED, false);
        }
        if (alive.isEmpty()) {
            return 0;
        }
        AgentKnowledge kb = knowledgeMapper.selectById(doc.getKnowledgeId());
        if (kb == null) {
            return 0;
        }
        int synced = 0;
        for (int from = 0; from < alive.size(); from += EMBED_BATCH) {
            List<AgentKbChunk> part = alive.subList(from, Math.min(from + EMBED_BATCH, alive.size()));
            List<Long> ids = part.stream().map(AgentKbChunk::getId).toList();
            List<String> texts = part.stream()
                .map(c -> StringUtils.defaultString(c.getContent())).toList();
            try {
                EmbeddingService embedding = embeddingFactory.forKnowledge(kb);
                List<float[]> vectors = embedding.embed(texts);
                vectorStore.upsert(kb.getId(), documentId, ids, vectors);
                markStatus(ids, VectorSyncStatus.SYNCED, false);
                synced += part.size();
            } catch (Exception e) {
                markStatus(ids, VectorSyncStatus.FAILED, true);
                log.warn("[知识库] 文档 {} 向量补偿失败(第 {} 次): {}",
                    documentId, retryCountOf(part), e.getMessage());
            }
        }
        return synced;
    }

    /**
     * 清理代次落后于文档生效代次的分块(重建切换后清理中断的兜底)
     */
    private int cleanupStaleGenerations() {
        List<Long> staleIds = chunkMapper.selectStaleGenerationChunkIds(STALE_CLEAN_LIMIT);
        if (staleIds.isEmpty()) {
            return 0;
        }
        deleteChunks(staleIds);
        return staleIds.size();
    }

    private void deleteChunks(List<Long> ids) {
        if (ids.isEmpty()) {
            return;
        }
        try {
            vectorStore.deleteByChunkIds(ids);
        } catch (Exception e) {
            log.warn("[知识库] 清理过期分块向量失败: {}", e.getMessage());
        }
        chunkMapper.delete(new LambdaQueryWrapper<AgentKbChunk>().in(AgentKbChunk::getId, ids));
    }

    /**
     * 批量更新同步状态;{@code increaseRetry} 为 true 时同时把重试次数 +1
     */
    private void markStatus(List<Long> ids, String status, boolean increaseRetry) {
        if (ids.isEmpty()) {
            return;
        }
        for (int from = 0; from < ids.size(); from += 500) {
            List<Long> batch = ids.subList(from, Math.min(from + 500, ids.size()));
            LambdaUpdateWrapper<AgentKbChunk> update = new LambdaUpdateWrapper<AgentKbChunk>()
                .in(AgentKbChunk::getId, batch)
                .set(AgentKbChunk::getVectorStatus, status);
            if (increaseRetry) {
                update.setSql("vector_retry_count = vector_retry_count + 1");
            }
            chunkMapper.update(null, update);
        }
    }

    private int retryCountOf(List<AgentKbChunk> part) {
        return part.stream().map(c -> c.getVectorRetryCount() == null ? 0 : c.getVectorRetryCount())
            .findFirst().orElse(0) + 1;
    }
}
