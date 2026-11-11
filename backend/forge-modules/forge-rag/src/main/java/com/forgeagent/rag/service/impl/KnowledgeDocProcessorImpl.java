package com.forgeagent.rag.service.impl;

import lombok.RequiredArgsConstructor;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.extern.slf4j.Slf4j;
import com.forgeagent.agentcore.domain.AgentChatAttachment;
import com.forgeagent.rag.domain.AgentKbChunk;
import com.forgeagent.rag.domain.AgentKnowledge;
import com.forgeagent.rag.domain.AgentKnowledgeDocument;
import com.forgeagent.agentcore.mapper.AgentChatAttachmentMapper;
import com.forgeagent.rag.mapper.AgentKbChunkMapper;
import com.forgeagent.rag.mapper.AgentKnowledgeDocumentMapper;
import com.forgeagent.rag.mapper.AgentKnowledgeMapper;
import com.forgeagent.rag.service.KnowledgeDocProcessor;
import com.forgeagent.rag.service.document.CleanRules;
import com.forgeagent.rag.service.document.DocumentCleaner;
import com.forgeagent.rag.service.document.DocumentParser;
import com.forgeagent.rag.service.document.KnowledgeFingerprints;
import com.forgeagent.rag.service.document.DocumentParserFactory;
import com.forgeagent.rag.service.document.ParsedText;
import com.forgeagent.rag.service.document.TextChunker;
import com.forgeagent.rag.service.document.TokenEstimator;
import com.forgeagent.rag.service.embedding.EmbeddingFactory;
import com.forgeagent.rag.service.embedding.EmbeddingService;
import com.forgeagent.rag.service.vector.VectorStore;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
/**
 * 知识库文档异步处理实现
 *
 * <p><b>代次(generation)语义</b>:一份文档在同一时刻只有一个"生效代次"。
 * 首次上传与失败重试用当前代次原地重建;重建索引用「当前代次 + 1」写入新代,
 * 全部写入成功后才切换指针,再异步清理旧代——所以重建期间知识库照常可查,
 * 中途失败也只是新代作废,旧代完好。</p>
 *
 * <p><b>向量同步状态</b>:分块落库即标 PENDING,写入向量库成功后批量置 SYNCED,
 * 失败置 FAILED 并记录 notice;由 {@code KbVectorSyncService} 定时补偿。
 * 不再"失败只打一行 warn 然后当没事发生"。</p>
 *
 * @author ForgeAgent
 */
@Slf4j
@RequiredArgsConstructor
@Component
public class KnowledgeDocProcessorImpl implements KnowledgeDocProcessor {

    private static final String VECTOR_PENDING = com.forgeagent.rag.service.vector.VectorSyncStatus.PENDING;
    private static final String VECTOR_SYNCED = com.forgeagent.rag.service.vector.VectorSyncStatus.SYNCED;
    private static final String VECTOR_FAILED = com.forgeagent.rag.service.vector.VectorSyncStatus.FAILED;
    private static final String VECTOR_SKIPPED = com.forgeagent.rag.service.vector.VectorSyncStatus.SKIPPED;

    /**
     * 页/工作表标记:[[page=3]] / [[sheet=销售表]]
     */
    private static final Pattern PAGE_MARK = Pattern.compile("\\[\\[(page|sheet)=([^\\]]+)\\]\\]");

    /**
     * 解析硬超时:坏文件(损坏的 xref、畸形 XML)能让解析器长时间占满 CPU,
     * 超时后中断并标失败,避免拖住整个摄取队列
     */
    private static final long PARSE_TIMEOUT_MS = 60_000L;

    /**
     * 正文长度上限(字符):超过这个量级的分块与向量化都不再有意义
     */
    private static final int MAX_TEXT_CHARS = 20_000_000;

    /**
     * 解析产物落库上限(字符):mediumtext 16MB 字节,utf8mb4 中文 3 字节/字,
     * 400 万字符 ≈ 12MB,留有余量;超长文档不落产物(重建时重解析,正确性不受影响)
     */
    private static final int PARSED_TEXT_STORE_LIMIT = 4_000_000;

    /**
     * token 计价下的默认预算:中文 1 字≈1 token,400 token 约等于 400 个汉字
     */
    private static final int DEFAULT_TOKEN_CHUNK = 400;

    private static final int DEFAULT_TOKEN_OVERLAP = 60;

    private static final int DEFAULT_TOKEN_PARENT = 1200;

    /**
     * 解析与嵌入分离的线程池(解析是 CPU 密集,嵌入是 IO 等待,混用会互相拖慢)
     */
    @jakarta.annotation.Resource(name = "knowledgeParseExecutor")
    private java.util.concurrent.ExecutorService parseExecutor;

    private final AgentKnowledgeMapper knowledgeMapper;
    private final AgentKnowledgeDocumentMapper documentMapper;
    private final AgentKbChunkMapper chunkMapper;
    private final AgentChatAttachmentMapper attachmentMapper;
    private final EmbeddingFactory embeddingFactory;
    private final VectorStore vectorStore;
    private final TextChunker chunker;

    @Async("knowledgeDocExecutor")
    @Override
    public void processAsync(Long documentId) {
        process(documentId, 0);
    }

    @Async("knowledgeDocExecutor")
    @Override
    public void processGenerationAsync(Long documentId, int generation) {
        process(documentId, generation);
    }

    /**
     * 处理文档:解析 -> 分块 -> 嵌入 -> 落库 -> 写向量 -> 切代次 -> 清旧代
     *
     * @param targetGeneration 目标代次;<=0 表示沿用文档当前生效代次
     */
    public void process(Long documentId, int targetGeneration) {
        AgentKnowledgeDocument doc = documentMapper.selectById(documentId);
        if (doc == null) {
            return;
        }
        AgentKnowledge kb = knowledgeMapper.selectById(doc.getKnowledgeId());
        if (kb == null) {
            markFailed(doc, "所属知识库不存在");
            return;
        }
        int activeGeneration = doc.getActiveGeneration() == null ? 1 : doc.getActiveGeneration();
        int generation = targetGeneration > 0 ? targetGeneration : activeGeneration;
        if (generation < activeGeneration) {
            // 让晚到的旧任务直接作废,避免把已切换的代次又写回去
            log.warn("[知识库] 文档 {} 的目标代次 {} 低于生效代次 {},已跳过", documentId, generation, activeGeneration);
            return;
        }
        try {
            // 解析产物复用(R2-5):文档指纹与知识库当前解析指纹一致且产物可用时,
            // 跳过"解析+清洗"两步,重建成本从「重解析+重嵌」降到「重切+重嵌」
            String parseFp = KnowledgeFingerprints.parseOf(kb.getCleanRules());
            String cached = doc.getParseFingerprint() != null && doc.getParseFingerprint().equals(parseFp)
                ? loadParsedText(documentId) : null;
            String text;
            ParsedText parsed = null;
            List<String> cleanErrors = new ArrayList<>();
            DocumentCleaner.CleanResult cleaned = null;
            if (cached != null && !cached.isBlank()) {
                text = cached;
                log.info("[知识库] 文档 {} 复用解析产物,跳过解析与清洗", documentId);
            } else {
                byte[] bytes = Files.readAllBytes(Path.of(doc.getFilePath()));
                parsed = parseWithTimeout(doc.getFileExt(), bytes);
                text = parsed.text();
                if (text != null && text.length() > MAX_TEXT_CHARS) {
                    text = text.substring(0, MAX_TEXT_CHARS);
                    parsed = new ParsedText(text, parsed.pageCount(), parsed.scannedPages(),
                        parsed.garbledPages(), appendWarning(parsed.warnings(), "正文超长已截断"));
                }
                // 清洗管线(R2-2):页眉页脚/页码 → NFKC → 正则 → PII → 空白归一,规则由库级 clean_rules 决定
                CleanRules cleanRules = CleanRules.parse(kb.getCleanRules(), cleanErrors);
                cleaned = DocumentCleaner.clean(text, cleanRules);
                if (cleaned.changed() || !cleanErrors.isEmpty()) {
                    log.info("[知识库] 文档 {} 清洗完成:页眉页脚 {} 行,页码 {} 行,正则 {} 行,PII {} 行,告警 {}",
                        documentId, cleaned.removedHeader(), cleaned.removedPageNo(),
                        cleaned.regexLines(), cleaned.piiLines(), cleanErrors);
                }
                text = cleaned.text();
            }
            if (text == null || text.isBlank()) {
                // 扫描件没有文本层,直接失败并说明原因:标"成功但 0 块"运营根本发现不了
                throw new IllegalStateException(parsed != null && parsed.scannedPages() > 0
                    ? "文档无可提取文本:" + parsed.scannedPages() + " 页均为扫描件,需 OCR(当前未启用)"
                    : "文档解析结果为空");
            }
            String parseNotice = parsed != null ? parsed.warningSummary() : "";
            if (cleaned != null && (cleaned.changed() || !cleanErrors.isEmpty())) {
                StringBuilder cleanSummary = new StringBuilder();
                if (cleaned.removedHeader() > 0 || cleaned.removedPageNo() > 0) {
                    cleanSummary.append("清洗:页眉页脚 ").append(cleaned.removedHeader())
                        .append(" 行,页码 ").append(cleaned.removedPageNo()).append(" 行");
                }
                if (cleaned.piiLines() > 0) {
                    if (cleanSummary.length() > 0) {
                        cleanSummary.append(";");
                    }
                    cleanSummary.append("PII 打码 ").append(cleaned.piiLines()).append(" 行");
                }
                for (String e : cleanErrors) {
                    if (cleanSummary.length() > 0) {
                        cleanSummary.append(";");
                    }
                    cleanSummary.append(e);
                }
                parseNotice = joinNotices(parseNotice,
                    cleanSummary.length() > 0 ? cleanSummary.toString() : null);
            }
            // 分块口径:单位(char/token)与策略(structure/fixed)由知识库配置,留空按存量口径
            String unit = TextChunker.isKnownUnit(kb.getChunkUnit())
                ? kb.getChunkUnit() : TextChunker.UNIT_CHAR;
            boolean byToken = TextChunker.UNIT_TOKEN.equals(unit);
            String strategy = TextChunker.isKnownStrategy(kb.getSplitStrategy())
                ? kb.getSplitStrategy() : TextChunker.STRATEGY_STRUCTURE;
            boolean structureAware = !TextChunker.STRATEGY_FIXED.equals(strategy);
            int chunkBudget = kb.getChunkSize() == null
                ? (byToken ? DEFAULT_TOKEN_CHUNK : 500) : kb.getChunkSize();
            int overlapBudget = kb.getChunkOverlap() == null
                ? (byToken ? DEFAULT_TOKEN_OVERLAP : 50) : kb.getChunkOverlap();
            // 父子分块:先按父块预算切父块,再在每个父块内切子块;
            // 只有子块参与嵌入与召回,父块作为命中后的上下文容器(small-to-big)
            boolean parentMode = "1".equals(kb.getParentChunkEnabled());
            int parentBudget = kb.getParentChunkSize() == null
                ? (byToken ? DEFAULT_TOKEN_PARENT : 2000) : kb.getParentChunkSize();
            List<String> parentChunks = new ArrayList<>();
            List<String> chunks = new ArrayList<>();
            List<String> chunkPaths = new ArrayList<>();
            List<Integer> chunkParentIndex = new ArrayList<>();
            List<int[]> childOffsets = new ArrayList<>();
            if (parentMode) {
                parentChunks = texts(chunker.pieces(text, parentBudget, 0, byToken, structureAware));
                for (int p = 0; p < parentChunks.size(); p++) {
                    String parentText = parentChunks.get(p);
                    List<TextChunker.ChunkPiece> subs =
                        chunker.pieces(parentText, chunkBudget, overlapBudget, byToken, structureAware);
                    int cursor = 0;
                    for (TextChunker.ChunkPiece sub : subs) {
                        int[] offset = com.forgeagent.rag.service.retrieve.ParentOffsets
                            .locate(parentText, sub.text(), overlapBudget, cursor);
                        if (offset[0] >= 0) {
                            cursor = offset[1];
                        }
                        chunks.add(sub.text());
                        chunkPaths.add(sub.headingPath());
                        chunkParentIndex.add(p);
                        childOffsets.add(offset);
                    }
                }
                if (chunks.isEmpty() && !parentChunks.isEmpty()) {
                    // 父块全部切不出子块(极短文档)时,父块即子块
                    chunks = parentChunks;
                    chunkPaths.clear();
                    chunkParentIndex.clear();
                    childOffsets.clear();
                    for (int p = 0; p < parentChunks.size(); p++) {
                        chunkPaths.add(null);
                        chunkParentIndex.add(p);
                        childOffsets.add(new int[]{0, parentChunks.get(p).length()});
                    }
                }
            } else {
                for (TextChunker.ChunkPiece piece
                    : chunker.pieces(text, chunkBudget, overlapBudget, byToken, structureAware)) {
                    chunks.add(piece.text());
                    chunkPaths.add(piece.headingPath());
                }
            }
            if (chunks.isEmpty()) {
                throw new IllegalStateException("文档分块结果为空");
            }
            EmbeddingService embedding = embeddingFactory.forKnowledge(kb);
            List<float[]> vectors = embedding.embed(chunks);

            // 只清理同一代次的残留(重试场景),旧代次留到切换成功后再删
            cleanupChunks(documentId, generation);
            List<Long> parentChunkIds = new ArrayList<>();
            if (parentMode) {
                for (int p = 0; p < parentChunks.size(); p++) {
                    AgentKbChunk parent = new AgentKbChunk();
                    parent.setKnowledgeId(doc.getKnowledgeId());
                    parent.setDocumentId(documentId);
                    parent.setChunkIndex(p);
                    parent.setContent(parentChunks.get(p));
                    parent.setEnabled(1);
                    parent.setTokenCount(TokenEstimator.estimate(parentChunks.get(p)));
                    parent.setChunkRole("p");
                    parent.setGeneration(generation);
                    // 父块不参与召回,也就不需要向量:标 SKIPPED 让补偿扫描忽略它
                    parent.setVectorStatus(VECTOR_SKIPPED);
                    chunkMapper.insert(parent);
                    parentChunkIds.add(parent.getId());
                }
            }
            List<Long> chunkIds = new ArrayList<>(chunks.size());
            for (int i = 0; i < chunks.size(); i++) {
                AgentKbChunk chunk = new AgentKbChunk();
                chunk.setKnowledgeId(doc.getKnowledgeId());
                chunk.setDocumentId(documentId);
                chunk.setChunkIndex(i);
                chunk.setContent(chunks.get(i));
                chunk.setEnabled(1);
                chunk.setTokenCount(TokenEstimator.estimate(chunks.get(i)));
                chunk.setPageLabel(extractPageLabel(chunks.get(i)));
                chunk.setPageNo(extractPageNo(chunks.get(i)));
                chunk.setSheetName(extractSheetName(chunks.get(i)));
                chunk.setChunkRole("c");
                chunk.setGeneration(generation);
                chunk.setVectorStatus(VECTOR_PENDING);
                chunk.setVectorRetryCount(0);
                if (i < chunkPaths.size()) {
                    chunk.setHeadingPath(chunkPaths.get(i));
                }
                if (parentMode) {
                    chunk.setParentId(parentChunkIds.get(chunkParentIndex.get(i)));
                    int[] offset = childOffsets.size() > i ? childOffsets.get(i) : null;
                    if (offset != null && offset[0] >= 0) {
                        chunk.setParentOffsetStart(offset[0]);
                        chunk.setParentOffsetEnd(offset[1]);
                    }
                }
                chunkMapper.insert(chunk);
                chunkIds.add(chunk.getId());
            }

            // 向量写入失败不再静默:分块保留(关键词仍可召回),状态标记后交给补偿扫描
            String vectorNotice = "";
            try {
                vectorStore.upsert(doc.getKnowledgeId(), documentId, chunkIds, vectors);
                markVectorStatus(chunkIds, VECTOR_SYNCED);
            } catch (Exception e) {
                markVectorStatus(chunkIds, VECTOR_FAILED);
                vectorNotice = "向量写入失败,已排队补偿";
                log.error("[知识库] 文档 {} 写入向量库失败,已标记待补偿: {}", documentId, e.getMessage());
            }
            String notice = joinNotices(parseNotice, vectorNotice);

            // 切换生效代次 + 清理旧代:顺序不能反——先删后切会制造检索空洞
            documentMapper.update(null, new LambdaUpdateWrapper<AgentKnowledgeDocument>()
                .eq(AgentKnowledgeDocument::getId, documentId)
                .set(AgentKnowledgeDocument::getStatus, "1")
                .set(AgentKnowledgeDocument::getChunkCount, chunks.size())
                .set(AgentKnowledgeDocument::getActiveGeneration, generation)
                .set(AgentKnowledgeDocument::getFailReason, null)
                .set(AgentKnowledgeDocument::getNotice, notice)
                // 摄取成功即与当前配置一致:清 stale 标记,并记录/刷新解析产物
                .set(AgentKnowledgeDocument::getConfigStale, 0)
                .set(AgentKnowledgeDocument::getParseFingerprint, parseFp)
                .set(AgentKnowledgeDocument::getParsedText,
                    text.length() <= PARSED_TEXT_STORE_LIMIT ? text : null));
            cleanupStaleGenerations(documentId, generation);
            syncChatAttachment(doc, text, chunks.size(), true, null);
            log.info("[知识库] 文档 {} 处理完成,代次 {} 分块数 {}", documentId, generation, chunks.size());
        } catch (Exception e) {
            log.error("[知识库] 文档 {} 处理失败", documentId, e);
            cleanupChunks(documentId, generation);
            markFailed(doc, e.getMessage());
        }
    }

    /**
     * 读取解析产物(parsed_text 是 select=false 的大字段,需要时显式取)
     */
    private String loadParsedText(Long documentId) {
        AgentKnowledgeDocument row = documentMapper.selectOne(
            new LambdaQueryWrapper<AgentKnowledgeDocument>()
                .select(AgentKnowledgeDocument::getParsedText)
                .eq(AgentKnowledgeDocument::getId, documentId));
        return row == null ? null : row.getParsedText();
    }

    /**
     * 带硬超时的解析:超时中断并失败,避免坏文件长期占用解析线程
     */
    private ParsedText parseWithTimeout(String ext, byte[] bytes) throws Exception {
        DocumentParser parser = DocumentParserFactory.get(ext);
        java.util.concurrent.CompletableFuture<ParsedText> future =
            java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                try {
                    return parser.parseRich(bytes);
                } catch (Exception e) {
                    throw new java.util.concurrent.CompletionException(e);
                }
            }, parseExecutor);
        try {
            return future.get(PARSE_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            future.cancel(true);
            throw new IllegalStateException("解析超过 " + (PARSE_TIMEOUT_MS / 1000) + " 秒已中止");
        } catch (java.util.concurrent.ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            if (cause instanceof Exception ex) {
                throw ex;
            }
            throw e;
        }
    }

    private static List<String> texts(List<TextChunker.ChunkPiece> pieces) {
        List<String> out = new ArrayList<>(pieces.size());
        for (TextChunker.ChunkPiece piece : pieces) {
            out.add(piece.text());
        }
        return out;
    }

    private static List<String> appendWarning(List<String> warnings, String extra) {
        List<String> merged = new ArrayList<>(warnings == null ? List.of() : warnings);
        merged.add(extra);
        return merged;
    }

    /**
     * 合并解析告警与向量同步提示(notice 列宽 200,超出截断)
     */
    private static String joinNotices(String parseNotice, String vectorNotice) {
        String joined;
        if (parseNotice == null || parseNotice.isBlank()) {
            joined = vectorNotice == null ? "" : vectorNotice;
        } else if (vectorNotice == null || vectorNotice.isBlank()) {
            joined = parseNotice;
        } else {
            joined = parseNotice + "；" + vectorNotice;
        }
        return joined.length() > 200 ? joined.substring(0, 200) : joined;
    }

    /**
     * 在父块中定位子块偏移:逻辑见 {@link com.forgeagent.rag.service.retrieve.ParentOffsets}
     * (抽出去是为了能用真实分块器做单测)
     */

    /**
     * 批量更新向量同步状态
     */
    void markVectorStatus(List<Long> chunkIds, String status) {
        if (chunkIds == null || chunkIds.isEmpty()) {
            return;
        }
        for (int from = 0; from < chunkIds.size(); from += 500) {
            List<Long> batch = chunkIds.subList(from, Math.min(from + 500, chunkIds.size()));
            chunkMapper.update(null, new LambdaUpdateWrapper<AgentKbChunk>()
                .in(AgentKbChunk::getId, batch)
                .set(AgentKbChunk::getVectorStatus, status));
        }
    }

    /**
     * 删除指定代次的分块与向量(重试/失败回滚用;旧代不动)
     */
    private void cleanupChunks(Long documentId, int generation) {
        List<AgentKbChunk> stale = chunkMapper.selectList(new LambdaQueryWrapper<AgentKbChunk>()
            .select(AgentKbChunk::getId)
            .eq(AgentKbChunk::getDocumentId, documentId)
            .eq(AgentKbChunk::getGeneration, generation));
        if (stale.isEmpty()) {
            return;
        }
        List<Long> ids = stale.stream().map(AgentKbChunk::getId).toList();
        deleteChunksByIds(ids);
    }

    /**
     * 切换成功后清理旧代分块与向量
     */
    private void cleanupStaleGenerations(Long documentId, int activeGeneration) {
        List<AgentKbChunk> stale = chunkMapper.selectList(new LambdaQueryWrapper<AgentKbChunk>()
            .select(AgentKbChunk::getId)
            .eq(AgentKbChunk::getDocumentId, documentId)
            .lt(AgentKbChunk::getGeneration, activeGeneration));
        if (stale.isEmpty()) {
            return;
        }
        List<Long> ids = stale.stream().map(AgentKbChunk::getId).toList();
        deleteChunksByIds(ids);
        log.info("[知识库] 文档 {} 已清理 {} 条旧代分块", documentId, ids.size());
    }

    /**
     * 先删向量库再删行:反过来的话向量行会变成永远找不到的孤儿
     */
    private void deleteChunksByIds(List<Long> ids) {
        try {
            vectorStore.deleteByChunkIds(ids);
        } catch (Exception e) {
            log.warn("[知识库] 清理旧代向量失败(可由重建/删除兜底): {}", e.getMessage());
        }
        chunkMapper.delete(new LambdaQueryWrapper<AgentKbChunk>().in(AgentKbChunk::getId, ids));
    }

    /**
     * 会话附件临时索引处理完成后,回写附件解析状态与摘要信息
     */
    private void syncChatAttachment(AgentKnowledgeDocument doc, String text, int chunkCount, boolean success, String reason) {
        AgentChatAttachment att = attachmentMapper.selectOne(
            new LambdaQueryWrapper<AgentChatAttachment>()
                .eq(AgentChatAttachment::getKnowledgeId, doc.getKnowledgeId())
                .last("limit 1"));
        if (att == null) {
            return;
        }
        AgentChatAttachment update = new AgentChatAttachment();
        update.setId(att.getId());
        if (success) {
            update.setParseStatus("1");
            update.setChunkCount(chunkCount);
            update.setTokenCount(TokenEstimator.estimate(text));
            update.setSummary(buildSummary(text));
            update.setPageCount(countPages(text));
            // updateById 忽略 null,failReason 须显式清空
            attachmentMapper.update(null,
                new LambdaUpdateWrapper<AgentChatAttachment>()
                    .eq(AgentChatAttachment::getId, att.getId())
                    .set(AgentChatAttachment::getParseStatus, "1")
                    .set(AgentChatAttachment::getChunkCount, chunkCount)
                    .set(AgentChatAttachment::getTokenCount, TokenEstimator.estimate(text))
                    .set(AgentChatAttachment::getSummary, update.getSummary())
                    .set(AgentChatAttachment::getPageCount, update.getPageCount())
                    .set(AgentChatAttachment::getFailReason, null));
            return;
        }
        update.setParseStatus("2");
        update.setChunkCount(0);
        String msg = reason == null ? "解析失败" : reason;
        update.setFailReason(msg.length() > 500 ? msg.substring(0, 500) : msg);
        attachmentMapper.updateById(update);
    }

    /**
     * 摘要:优先保留标题与页码/工作表标记形成大纲,并附带每页/每表首行(表头)便于模型判断内容;
     * 无结构化标记(纯文本)时截取正文首段
     */
    private String buildSummary(String text) {
        StringBuilder outline = new StringBuilder();
        boolean awaitFirstLine = false;
        for (String line : text.split("\\R")) {
            String t = line.strip();
            if (t.isEmpty()) {
                continue;
            }
            if (t.startsWith("[[page=") || t.startsWith("[[sheet=")) {
                if (outline.length() > 0) {
                    outline.append('\n');
                }
                outline.append(t);
                awaitFirstLine = true;
                if (outline.length() >= 800) {
                    break;
                }
                continue;
            }
            if (awaitFirstLine) {
                // 标记后的首行:PDF 为该页开头,Excel 为该工作表表头
                outline.append(' ').append(t.length() > 60 ? t.substring(0, 60) : t);
                awaitFirstLine = false;
            } else if (t.startsWith("#")) {
                if (outline.length() > 0) {
                    outline.append('\n');
                }
                outline.append(t);
            }
            if (outline.length() >= 800) {
                break;
            }
        }
        if (outline.length() < 50) {
            outline.setLength(0);
            outline.append(text.strip());
        }
        return outline.length() > 800 ? outline.substring(0, 800) : outline.toString();
    }

    private int countPages(String text) {
        int pages = 0;
        int sheets = 0;
        int idx = 0;
        while ((idx = text.indexOf("[[page=", idx)) >= 0) {
            pages++;
            idx += 7;
        }
        idx = 0;
        while ((idx = text.indexOf("[[sheet=", idx)) >= 0) {
            sheets++;
            idx += 8;
        }
        return Math.max(pages, sheets);
    }

    /**
     * 提取分块的来源定位标记:取分块内首个 [[page=N]] / [[sheet=名称]] 结构标记
     * (标记由 PDF/Excel 解析器写在页/工作表首行,代表分块起始位置),无结构标记返回 null
     */
    private String extractPageLabel(String content) {
        Matcher m = PAGE_MARK.matcher(content == null ? "" : content);
        return m.find() ? m.group(1) + "=" + m.group(2).trim() : null;
    }

    /**
     * 解析页码:仅 page 标记可转数字,sheet 标记不算页码
     */
    Integer extractPageNo(String content) {
        Matcher m = PAGE_MARK.matcher(content == null ? "" : content);
        while (m.find()) {
            if ("page".equals(m.group(1))) {
                try {
                    return Integer.parseInt(m.group(2).trim());
                } catch (NumberFormatException e) {
                    return null;
                }
            }
        }
        return null;
    }

    /**
     * 解析工作表名
     */
    String extractSheetName(String content) {
        Matcher m = PAGE_MARK.matcher(content == null ? "" : content);
        while (m.find()) {
            if ("sheet".equals(m.group(1))) {
                String name = m.group(2).trim();
                return name.isEmpty() ? null : (name.length() > 100 ? name.substring(0, 100) : name);
            }
        }
        return null;
    }

    private void markFailed(AgentKnowledgeDocument doc, String reason) {
        AgentKnowledgeDocument update = new AgentKnowledgeDocument();
        update.setId(doc.getId());
        update.setStatus("2");
        String msg = reason == null ? "未知错误" : reason;
        update.setFailReason(msg.length() > 500 ? msg.substring(0, 500) : msg);
        update.setChunkCount(0);
        documentMapper.updateById(update);
        syncChatAttachment(doc, null, 0, false, msg);
    }
}
