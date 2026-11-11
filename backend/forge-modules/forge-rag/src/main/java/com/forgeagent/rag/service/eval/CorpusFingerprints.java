package com.forgeagent.rag.service.eval;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;

/**
 * R3-4 语料指纹(纯静态):corpus_fingerprint = sha256(
 * 排序后的 (documentId, activeGeneration) 列表 | embeddingModel:dim | chunkFingerprint)。
 *
 * <p>指纹相等才允许门禁进入指标比较;不等说明语料或索引配置变了,
 * 前后两次运行不可比,只能 LOG_ONLY。</p>
 *
 * @param documentId 文档ID
 * @param generation 文档生效代次
 * @author ForgeAgent
 */
public record CorpusFingerprints(long documentId, long generation) {

    /**
     * @param documents        (documentId, activeGeneration) 列表(顺序无关,内部排序)
     * @param embeddingModel   嵌入模型
     * @param vectorDim        向量维度
     * @param chunkFingerprint 知识库分块指纹(R2-5)
     */
    public static String fingerprint(List<CorpusFingerprints> documents,
                                     String embeddingModel, Integer vectorDim,
                                     String chunkFingerprint) {
        StringBuilder sb = new StringBuilder();
        documents.stream()
            .sorted(Comparator.comparingLong(CorpusFingerprints::documentId)
                .thenComparingLong(CorpusFingerprints::generation))
            .forEach(d -> sb.append(d.documentId()).append(':').append(d.generation()).append('\n'));
        sb.append('|').append(embeddingModel == null ? "" : embeddingModel)
            .append(':').append(vectorDim == null ? "" : vectorDim);
        sb.append('|').append(chunkFingerprint == null ? "" : chunkFingerprint);
        return sha256(sb.toString());
    }

    public static String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
