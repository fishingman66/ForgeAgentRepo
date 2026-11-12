package com.forgeagent.rag.service.governance;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import com.forgeagent.rag.config.KnowledgeProperties;
import com.forgeagent.rag.domain.AgentKbChunk;
import com.forgeagent.rag.domain.AgentKnowledgeDocument;
import com.forgeagent.rag.mapper.AgentKbChunkMapper;
import com.forgeagent.rag.mapper.AgentKnowledgeDocumentMapper;
import com.forgeagent.rag.service.vector.VectorStore;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Date;
import java.util.List;

/**
 * R5-2 回收站清理:物理删除超过保留期的文档(分块 + 向量 + 原文件 + 行)。
 *
 * <p>治理动作(下架/入回收站/改可见性)只改数据库行,物理清理集中在这里跑,
 * 避免"每次治理都要动向量库"。开关 {@code rag.knowledge.trash.cleanup-enabled} 默认开,
 * 保留期 {@code rag.knowledge.trash.retention-days} 默认 30 天。</p>
 *
 * @author ForgeAgent
 */
@Slf4j
@RequiredArgsConstructor
@Component
public class DocTrashCleanupService {

    private static final int BATCH = 100;

    private final AgentKnowledgeDocumentMapper documentMapper;
    private final AgentKbChunkMapper chunkMapper;
    private final VectorStore vectorStore;
    private final KnowledgeProperties properties;

    @Scheduled(fixedDelayString = "${rag.knowledge.trash.cleanup-interval-ms:3600000}",
        initialDelayString = "${rag.knowledge.trash.cleanup-initial-delay-ms:300000}")
    public void purgeExpired() {
        if (Boolean.FALSE.equals(properties.getTrashCleanupEnabled())) {
            return;
        }
        int retention = properties.getTrashRetentionDays() == null ? 30 : properties.getTrashRetentionDays();
        Date deadline = Date.from(LocalDateTime.now().minusDays(retention)
            .atZone(ZoneId.systemDefault()).toInstant());
        List<AgentKnowledgeDocument> expired = documentMapper.selectList(
            new LambdaQueryWrapper<AgentKnowledgeDocument>()
                .eq(AgentKnowledgeDocument::getTrashed, 1)
                .isNotNull(AgentKnowledgeDocument::getTrashedAt)
                .lt(AgentKnowledgeDocument::getTrashedAt, deadline)
                .last("limit " + BATCH));
        if (expired.isEmpty()) {
            return;
        }
        int purged = 0;
        for (AgentKnowledgeDocument doc : expired) {
            try {
                chunkMapper.delete(new LambdaQueryWrapper<AgentKbChunk>()
                    .eq(AgentKbChunk::getDocumentId, doc.getId()));
                try {
                    vectorStore.deleteByDocumentIds(List.of(doc.getId()));
                } catch (Exception e) {
                    log.warn("[知识库] 回收站清理文档 {} 向量失败(分块已删): {}", doc.getId(), e.getMessage());
                }
                if (doc.getFilePath() != null) {
                    try {
                        Files.deleteIfExists(Path.of(doc.getFilePath()));
                    } catch (Exception e) {
                        log.warn("[知识库] 回收站清理文档 {} 文件失败: {}", doc.getId(), e.getMessage());
                    }
                }
                documentMapper.deleteById(doc.getId());
                purged++;
            } catch (Exception e) {
                log.warn("[知识库] 回收站清理文档 {} 失败: {}", doc.getId(), e.getMessage());
            }
        }
        if (purged > 0) {
            log.info("[知识库] 回收站清理完成,物理删除 {} 篇(保留期 {} 天)", purged, retention);
        }
    }
}
