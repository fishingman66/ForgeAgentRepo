package com.forgeagent.memory.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.forgeagent.common.core.utils.StringUtils;
import com.forgeagent.memory.config.MemoryVectorProperties;
import com.forgeagent.memory.domain.AgentMemory;
import com.forgeagent.memory.mapper.AgentMemoryMapper;
import com.forgeagent.rag.service.embedding.EmbeddingFactory;
import com.forgeagent.rag.service.embedding.EmbeddingService;
import com.forgeagent.rag.service.vector.VectorCodec;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * L3 长期语义记忆的向量读写(P3 / M2',MySQL 向量列)。
 *
 * <p>与 RAG 的 {@code MysqlVectorStore} 同构:float32 小端存 BLOB,应用层算余弦,
 * 不新增表与数据源。写路径异步向量化,读路径做语义召回;任一步失败都只告警,
 * 由 {@code AgentMemoryServiceImpl} 回退纯评分召回,不阻断对话。</p>
 *
 * <p>与 {@link MemoryScope} 同包,复用同一套 agent+user 隔离口径。</p>
 *
 * @author ForgeAgent
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MemoryVectorService {

    /** 内容被冲突替代后置的失效标记,让补偿扫描能捞到并重算 */
    public static final String STALE_MODEL = "stale";

    /** 已实现的向量后端:读、写、补偿扫描三处都落在 agent_memory 的 embedding 三列上 */
    public static final String BACKEND_MYSQL = "mysql";

    /** 显式关闭语义的后端取值,与 {@link MemoryVectorProperties#getBackend()} 的约定一致 */
    private static final String BACKEND_NONE = "none";

    private static final ThreadPoolExecutor WORKER = new ThreadPoolExecutor(
        1, 2, 60L, TimeUnit.SECONDS, new ArrayBlockingQueue<>(128),
        r -> {
            Thread t = new Thread(r, "agent-memory-vector");
            t.setDaemon(true);
            return t;
        }, new ThreadPoolExecutor.DiscardPolicy());

    private final AgentMemoryMapper memoryMapper;
    private final EmbeddingFactory embeddingFactory;
    private final MemoryVectorProperties properties;

    /** 已告警过的 backend 取值;enabled() 每轮对话会被调用多次,同一个错值只喊一次 */
    private final AtomicReference<String> warnedBackend = new AtomicReference<>();

    /**
     * 语义召回是否可用;关闭、显式 none、后端未实现时调用方直接走纯评分。
     *
     * <p>后端白名单是「实现是否存在」的闸门,不是功能开关(后者是 {@code memory.vector.enabled}):
     * 本类的读(search)、写(writeVector)、补扫(vectorizePending)都写死在 {@code agent_memory} 的
     * 向量列上,没有第二套实现。对未实现的后端返回 false 才能保证「要么整套生效、要么整套关闭」,
     * 否则会出现配置写着 pgvector、数据实际仍进出 MySQL 列的静默错配。</p>
     *
     * <p>未实现的后端这里告警一次(同值去重、取值变化重新告警),避免运维把「安静地没生效」
     * 误判成「已开启但效果不明显」。{@code none} 是显式关闭,不算配置错误,不告警。</p>
     */
    public boolean enabled() {
        if (!properties.isEnabled()) {
            return false;
        }
        // 配置里手写 YAML 常带尾空格,trim 后再比,免得 mysql 被误判成未实现
        String backend = StringUtils.trim(properties.getBackend());
        if (BACKEND_MYSQL.equalsIgnoreCase(backend)) {
            return true;
        }
        if (!BACKEND_NONE.equalsIgnoreCase(backend)) {
            warnUnsupportedBackend(backend);
        }
        return false;
    }

    /** 未实现后端只告警一次;取值变了(热更新/改配置)重新告警,便于定位是哪次改动引入的 */
    private void warnUnsupportedBackend(String backend) {
        String value = StringUtils.defaultIfBlank(backend, "<空>");
        if (value.equals(warnedBackend.getAndSet(value))) {
            return;
        }
        log.warn("[MemoryVector] memory.vector.backend={} 本期未实现(仅 {} 有向量读写实现),"
            + "语义召回按关闭处理,记忆注入退回纯评分(model={})", value, BACKEND_MYSQL, properties.getModel());
    }

    /** 向量化单条记忆(新增/冲突替代后调用,异步不阻塞对话) */
    public void vectorizeAsync(Long memoryId) {
        if (!enabled() || memoryId == null) {
            return;
        }
        submit(() -> {
            AgentMemory row = memoryMapper.selectById(memoryId);
            writeVector(row, embedding());
        });
    }

    /** 每轮对话前投递一次补偿扫描:捞回异步任务丢失或标记失效的行 */
    public void ensurePendingAsync(Long agentId, Long userId) {
        if (!enabled() || agentId == null) {
            return;
        }
        submit(() -> vectorizePending(agentId, userId));
    }

    /**
     * 语义召回:按 agent+user 范围扫描带向量的记忆,返回相似度降序的 topK。
     * 返回的 {@link Hit} 带完整评分字段,调用方可直接与评分候选池融合。
     */
    public List<Hit> search(Long agentId, Long userId, String query, int topK) {
        if (!enabled() || StringUtils.isBlank(query) || topK <= 0) {
            return List.of();
        }
        try {
            EmbeddingService embedding = embedding();
            List<float[]> vectors = embedding.embed(List.of(query));
            if (vectors.isEmpty() || vectors.getFirst().length == 0) {
                return List.of();
            }
            float[] queryVector = vectors.getFirst();
            int scanLimit = Math.max(1, properties.getScanLimit());
            LambdaQueryWrapper<AgentMemory> wrapper = new LambdaQueryWrapper<AgentMemory>()
                // 显式 select 才能取到 @TableField(select=false) 的 embedding 列
                .select(AgentMemory::getId, AgentMemory::getContent, AgentMemory::getFactKey,
                    AgentMemory::getImportance, AgentMemory::getHitCount, AgentMemory::getCreateTime,
                    AgentMemory::getEmbedding, AgentMemory::getEmbeddingDim, AgentMemory::getEmbeddingModel)
                .isNotNull(AgentMemory::getEmbedding)
                .eq(AgentMemory::getEmbeddingModel, embedding.modelKey())
                .last("limit " + scanLimit);
            MemoryScope.applyReadScope(wrapper, userId);
            // M4:显式 TTL——过期记忆不参与语义召回(与注入过滤同口径)
            wrapper.and(w -> w.isNull(AgentMemory::getExpireAt)
                .or().gt(AgentMemory::getExpireAt, LocalDateTime.now()));
            List<AgentMemory> rows = memoryMapper.selectList(wrapper);
            if (rows.isEmpty()) {
                return List.of();
            }
            if (rows.size() >= scanLimit) {
                log.warn("[MemoryVector] agent {} 记忆向量达到扫描上限 {},结果可能不完整", agentId, scanLimit);
            }
            double minScore = properties.getMinScore();
            List<Hit> hits = new ArrayList<>();
            for (AgentMemory row : rows) {
                if (row.getEmbeddingDim() != null && row.getEmbeddingDim() != queryVector.length) {
                    continue;
                }
                double score = VectorCodec.cosine(queryVector, VectorCodec.decode(row.getEmbedding()));
                if (score >= minScore) {
                    hits.add(new Hit(row, score));
                }
            }
            hits.sort(Comparator.comparingDouble(Hit::score).reversed());
            return hits.size() > topK ? new ArrayList<>(hits.subList(0, topK)) : hits;
        } catch (Exception e) {
            log.warn("[MemoryVector] 语义召回失败,回退评分召回: {}", e.getMessage());
            return List.of();
        }
    }

    /** 补偿扫描:未向量化或模型已失效的行,限量重算 */
    private int vectorizePending(Long agentId, Long userId) {
        EmbeddingService embedding = embedding();
        int limit = Math.max(1, properties.getPendingBatch());
        LambdaQueryWrapper<AgentMemory> pendingQuery = new LambdaQueryWrapper<AgentMemory>()
            .select(AgentMemory::getId, AgentMemory::getContent)
            .eq(AgentMemory::getAgentId, agentId)
            .and(w -> w.isNull(AgentMemory::getEmbedding)
                .or().ne(AgentMemory::getEmbeddingModel, embedding.modelKey()))
            .last("limit " + limit);
        MemoryScope.applyReadScope(pendingQuery, userId);
        List<AgentMemory> rows = memoryMapper.selectList(pendingQuery);
        if (rows.isEmpty()) {
            return 0;
        }
        List<float[]> vectors = embedding.embed(rows.stream()
            .map(r -> StringUtils.defaultString(r.getContent())).toList());
        int written = 0;
        for (int i = 0; i < rows.size() && i < vectors.size(); i++) {
            writeVector(rows.get(i), embedding, vectors.get(i));
            written++;
        }
        log.debug("[MemoryVector] agent {} 补偿向量化 {} 条", agentId, written);
        return written;
    }

    private void writeVector(AgentMemory row, EmbeddingService embedding) {
        if (row == null || StringUtils.isBlank(row.getContent())) {
            return;
        }
        List<float[]> vectors = embedding.embed(List.of(row.getContent()));
        if (vectors.isEmpty()) {
            return;
        }
        writeVector(row, embedding, vectors.getFirst());
    }

    private void writeVector(AgentMemory row, EmbeddingService embedding, float[] vector) {
        if (vector == null || vector.length == 0) {
            return;
        }
        memoryMapper.update(null, new LambdaUpdateWrapper<AgentMemory>()
            .eq(AgentMemory::getId, row.getId())
            .set(AgentMemory::getEmbedding, VectorCodec.encode(vector))
            .set(AgentMemory::getEmbeddingDim, vector.length)
            .set(AgentMemory::getEmbeddingModel, embedding.modelKey()));
    }

    private EmbeddingService embedding() {
        return embeddingFactory.forModel(properties.getModel());
    }

    private void submit(Runnable task) {
        try {
            WORKER.execute(() -> {
                try {
                    task.run();
                } catch (Exception e) {
                    log.warn("[MemoryVector] 向量化任务失败(不影响对话): {}", e.getMessage());
                }
            });
        } catch (Exception e) {
            log.warn("[MemoryVector] 向量化任务投递失败: {}", e.getMessage());
        }
    }

    /** 语义命中条目 */
    public record Hit(AgentMemory memory, double score) {
    }
}
