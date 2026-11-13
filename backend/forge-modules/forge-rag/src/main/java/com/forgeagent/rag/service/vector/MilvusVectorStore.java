package com.forgeagent.rag.service.vector;

import com.forgeagent.common.core.exception.ServiceException;
import com.forgeagent.common.core.utils.StringUtils;
import com.forgeagent.rag.config.KnowledgeProperties;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.milvus.v2.client.ConnectConfig;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.common.ConsistencyLevel;
import io.milvus.v2.common.DataType;
import io.milvus.v2.common.IndexParam;
import io.milvus.v2.service.collection.request.AddFieldReq;
import io.milvus.v2.service.collection.request.CreateCollectionReq;
import io.milvus.v2.service.collection.request.DescribeCollectionReq;
import io.milvus.v2.service.collection.request.HasCollectionReq;
import io.milvus.v2.service.collection.request.LoadCollectionReq;
import io.milvus.v2.service.collection.response.DescribeCollectionResp;
import io.milvus.v2.service.vector.request.DeleteReq;
import io.milvus.v2.service.vector.request.SearchReq;
import io.milvus.v2.service.vector.request.UpsertReq;
import io.milvus.v2.service.vector.request.data.BaseVector;
import io.milvus.v2.service.vector.request.data.FloatVec;
import io.milvus.v2.service.vector.response.SearchResp;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Milvus 向量存储实现（gRPC / Milvus Java SDK 3.0.x）。
 *
 * <p><b>为什么是 gRPC 而不是 REST</b>：本地开发用的 milvus-lite 3.x 只实现 gRPC 协议，
 * 19530 端口不提供 HTTP/1.1 REST v2；生产 Milvus 3.0 standalone 同样以 gRPC 为主端口。
 * 统一走 gRPC 后，本地与生产只需改 {@code rag.knowledge.milvus-*} 连接配置，
 * 业务代码、SDK 版本、集合结构完全一致。</p>
 *
 * <p><b>依赖边界</b>：SDK 自带 shaded gRPC/Netty，不与平台已有的 grpc/protobuf 冲突；
 * protobuf-java 由父 pom 统一锁版本。客户端延迟创建，Milvus 未启动时应用仍可正常启动，
 * 由 {@link MilvusSchemaInitializer} 只告警不阻断。</p>
 *
 * <p>集合结构：id(Int64 主键) / knowledge_id(Int64，过滤隔离) / document_id(Int64) /
 * vector(FloatVector, COSINE, AUTOINDEX)。按维度分集合规则见 {@link VectorCollections}。</p>
 *
 * @author ForgeAgent
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "rag.knowledge.vector-store", havingValue = "milvus", matchIfMissing = true)
public class MilvusVectorStore implements VectorStore {

    /** 单次按主键删除的分批大小（filter 表达式不宜过长） */
    private static final int DELETE_BATCH = 500;

    private static final String VECTOR_FIELD = "vector";

    private final KnowledgeProperties properties;

    /** 测试注入的客户端；生产为 null，由 {@link #client()} 懒加载 */
    private final MilvusClientV2 injectedClient;

    private volatile MilvusClientV2 client;

    /**
     * 主集合实际维度：启动时以 Milvus 里已存在的集合为准（配置改了但集合没重建时，
     * 以实际维度为准才不会把写入全部打回），集合不存在时取配置值
     */
    private volatile int primaryDim;

    /** 已确认存在的维度集合缓存，避免每次检索/写入都发一次 has 请求 */
    private final Set<Integer> readyDims = ConcurrentHashMap.newKeySet();

    public MilvusVectorStore(KnowledgeProperties properties) {
        this(properties, null);
    }

    /** 供契约测试注入客户端，不改变生产装配方式 */
    MilvusVectorStore(KnowledgeProperties properties, MilvusClientV2 injectedClient) {
        this.properties = properties;
        this.injectedClient = injectedClient;
        this.primaryDim = configuredDim();
    }

    @Override
    public void ensureCollection() {
        ensureCollection(primaryDim);
    }

    @Override
    public void ensureCollection(int dim) {
        try {
            doEnsureCollection(dim);
        } catch (Exception e) {
            throw wrap(e);
        }
    }

    private void doEnsureCollection(int dim) {
        int effective = dim > 0 ? dim : primaryDim;
        if (readyDims.contains(effective)) {
            return;
        }
        String name = VectorCollections.nameFor(collectionBase(), effective, primaryDim);
        if (Boolean.TRUE.equals(client().hasCollection(
            HasCollectionReq.builder().collectionName(name).build()))) {
            // 主集合：以实际维度校正 primaryDim，避免「配置改了没重建集合」导致写入维度不符
            if (effective == primaryDim) {
                int actual = describeDim(name);
                if (actual > 0 && actual != primaryDim) {
                    log.warn("[Milvus] 主集合 {} 实际维度 {} 与配置 {} 不一致，以实际维度为准；"
                            + "如需切换维度请先删除该集合或改 collection-name",
                        name, actual, primaryDim);
                    primaryDim = actual;
                }
            }
            loadQuietly(name);
            readyDims.add(effective);
            return;
        }
        createCollection(name, effective);
        loadQuietly(name);
        readyDims.add(effective);
        log.info("[Milvus] 集合 {} 创建成功(dim={}, AUTOINDEX/COSINE, gRPC)", name, effective);
    }

    @Override
    public void upsert(Long knowledgeId, Long documentId, List<Long> chunkIds, List<float[]> vectors) {
        if (chunkIds == null || vectors == null || chunkIds.size() != vectors.size()) {
            throw new ServiceException("向量数量与分块数量不一致");
        }
        if (chunkIds.isEmpty()) {
            return;
        }
        int dim = checkUniformDim(vectors);
        ensureCollection(dim);
        final String name = VectorCollections.nameFor(collectionBase(), dim, primaryDim);
        List<JsonObject> rows = new ArrayList<>(chunkIds.size());
        for (int i = 0; i < chunkIds.size(); i++) {
            JsonObject row = new JsonObject();
            row.addProperty("id", chunkIds.get(i));
            row.addProperty("knowledge_id", knowledgeId);
            row.addProperty("document_id", documentId);
            JsonArray vec = new JsonArray();
            for (float v : vectors.get(i)) {
                vec.add(v);
            }
            row.add(VECTOR_FIELD, vec);
            rows.add(row);
        }
        withRecovery(name, dim, () -> client().upsert(
            UpsertReq.builder().collectionName(name).data(rows).build()));
    }

    @Override
    public void deleteByDocumentIds(List<Long> documentIds) {
        if (documentIds == null || documentIds.isEmpty()) {
            return;
        }
        deleteInManagedCollections("document_id in [" + joinIds(documentIds) + "]");
    }

    @Override
    public void deleteByKnowledgeId(Long knowledgeId) {
        if (knowledgeId == null) {
            return;
        }
        deleteInManagedCollections("knowledge_id == " + knowledgeId);
    }

    @Override
    public void deleteByChunkIds(List<Long> chunkIds) {
        if (chunkIds == null || chunkIds.isEmpty()) {
            return;
        }
        // 主键精确删除：向量库行里没有 generation 字段，按 document_id 删会误伤新写入的一代
        for (int from = 0; from < chunkIds.size(); from += DELETE_BATCH) {
            List<Long> batch = chunkIds.subList(from, Math.min(from + DELETE_BATCH, chunkIds.size()));
            deleteInManagedCollections("id in [" + joinIds(batch) + "]");
        }
    }

    @Override
    public List<VectorHit> search(Long knowledgeId, float[] queryVector, int topK) {
        return search(knowledgeId, queryVector, topK, null);
    }

    @Override
    public List<VectorHit> search(Long knowledgeId, float[] queryVector, int topK, DocFilter filter) {
        int dim = queryVector == null ? 0 : queryVector.length;
        if (knowledgeId == null || dim <= 0 || topK <= 0) {
            return List.of();
        }
        ensureCollection(dim);
        final String name = VectorCollections.nameFor(collectionBase(), dim, primaryDim);
        StringBuilder expr = new StringBuilder("knowledge_id == ").append(knowledgeId);
        if (filter != null && filter.ids() != null && !filter.ids().isEmpty()) {
            expr.append(filter.include() ? " and document_id in [" : " and document_id not in [")
                .append(joinIds(filter.ids())).append(']');
        }
        SearchReq req = SearchReq.builder()
            .collectionName(name)
            .data(List.<BaseVector>of(new FloatVec(queryVector)))
            .annsField(VECTOR_FIELD)
            .filter(expr.toString())
            .outputFields(List.of("id", "document_id"))
            .topK(topK)
            .limit(topK)
            .consistencyLevel(consistencyLevel())
            .build();
        SearchResp resp = withRecovery(name, dim, () -> client().search(req));
        List<VectorHit> hits = new ArrayList<>();
        if (resp == null || resp.getSearchResults() == null) {
            return hits;
        }
        for (List<SearchResp.SearchResult> group : resp.getSearchResults()) {
            if (group == null) {
                continue;
            }
            for (SearchResp.SearchResult item : group) {
                Long id = toLong(item.getId());
                if (id == null) {
                    continue;
                }
                Long docId = toLong(item.getEntity() == null ? null : item.getEntity().get("document_id"));
                hits.add(new VectorHit(id, docId, item.getScore()));
            }
        }
        return hits;
    }

    /**
     * 在所有受管集合上执行删除：知识库换过嵌入模型时，旧向量可能留在另一个维度集合里，
     * 只删主集合会留下孤儿数据（重建/删除知识库后仍能被检索命中）
     */
    private void deleteInManagedCollections(String filter) {
        List<String> targets = listManagedCollections();
        if (targets.isEmpty()) {
            log.warn("[Milvus] 未发现任何受管集合，跳过删除(filter={})", filter);
            return;
        }
        for (String name : targets) {
            try {
                client().delete(DeleteReq.builder().collectionName(name).filter(filter).build());
            } catch (Exception e) {
                // 单个集合删除失败不阻断其他集合：残留向量由重建/重试兜底，但必须留痕
                log.warn("[Milvus] 集合 {} 删除失败: {}", name, e.getMessage());
            }
        }
    }

    /** 列出受管集合；列表接口不可用时退化为当前主集合（宁可少删也不抛错阻断业务） */
    private List<String> listManagedCollections() {
        try {
            List<String> names = client().listCollections().getCollectionNames();
            List<String> managed = VectorCollections.managedOf(collectionBase(), names);
            if (!managed.isEmpty()) {
                return managed;
            }
        } catch (Exception e) {
            log.warn("[Milvus] 列出集合失败，删除仅作用于主集合: {}", e.getMessage());
        }
        return List.of(VectorCollections.nameFor(collectionBase(), primaryDim, primaryDim));
    }

    private void createCollection(String name, int dim) {
        CreateCollectionReq.CollectionSchema schema = MilvusClientV2.CreateSchema();
        schema.addField(AddFieldReq.builder().fieldName("id").dataType(DataType.Int64)
            .isPrimaryKey(Boolean.TRUE).autoID(Boolean.FALSE).build());
        schema.addField(AddFieldReq.builder().fieldName("knowledge_id").dataType(DataType.Int64).build());
        schema.addField(AddFieldReq.builder().fieldName("document_id").dataType(DataType.Int64).build());
        schema.addField(AddFieldReq.builder().fieldName(VECTOR_FIELD).dataType(DataType.FloatVector)
            .dimension(dim).build());
        IndexParam index = IndexParam.builder()
            .fieldName(VECTOR_FIELD)
            .indexType(IndexParam.IndexType.AUTOINDEX)
            .metricType(IndexParam.MetricType.COSINE)
            .build();
        client().createCollection(CreateCollectionReq.builder()
            .collectionName(name)
            .collectionSchema(schema)
            .indexParams(List.of(index))
            .enableDynamicField(Boolean.FALSE)
            .consistencyLevel(consistencyLevel())
            .build());
    }

    /**
     * 加载集合。milvus-lite 会在首次访问时自动加载，部分版本对该 RPC 返回 UNIMPLEMENTED；
     * 因此失败只留 debug 日志，不阻断创建流程。
     */
    private void loadQuietly(String name) {
        try {
            client().loadCollection(LoadCollectionReq.builder().collectionName(name).build());
        } catch (Exception e) {
            log.debug("[Milvus] 集合 {} 加载跳过(Lite 自动加载或不支持该 RPC): {}", name, e.getMessage());
        }
    }

    /** 读取集合实际向量维度；接口不支持或结构变化时返回 -1（调用方按「未知」处理） */
    private int describeDim(String name) {
        try {
            DescribeCollectionResp resp = client().describeCollection(
                DescribeCollectionReq.builder().collectionName(name).build());
            if (resp == null || resp.getCollectionSchema() == null
                || resp.getCollectionSchema().getFieldSchemaList() == null) {
                return -1;
            }
            for (CreateCollectionReq.FieldSchema field : resp.getCollectionSchema().getFieldSchemaList()) {
                if (VECTOR_FIELD.equals(field.getName()) && field.getDimension() != null) {
                    return field.getDimension();
                }
            }
        } catch (Exception e) {
            log.debug("[Milvus] 读取集合 {} 维度失败: {}", name, e.getMessage());
        }
        return -1;
    }

    /** 校验一批向量维度一致，返回维度；不一致直接抛错（写进去只会让检索算错分） */
    private int checkUniformDim(List<float[]> vectors) {
        int dim = 0;
        for (float[] v : vectors) {
            if (v == null || v.length == 0) {
                throw new ServiceException("向量为空，无法写入");
            }
            if (dim == 0) {
                dim = v.length;
            } else if (dim != v.length) {
                throw new ServiceException("同一批向量维度不一致(" + dim + " vs " + v.length + ")");
            }
        }
        return dim;
    }

    /** 集合被删/未加载时自愈一次：清缓存 → 重建/重载 → 重试 */
    private <T> T withRecovery(String name, int dim, Supplier<T> action) {
        try {
            return action.get();
        } catch (Exception first) {
            if (isRecoverable(first) && readyDims.remove(dim)) {
                log.warn("[Milvus] 集合 {} 不可用({})，重建后重试", name, first.getMessage());
                ensureCollection(dim);
                try {
                    return action.get();
                } catch (Exception retry) {
                    throw wrap(retry);
                }
            }
            throw wrap(first);
        }
    }

    private boolean isRecoverable(Exception e) {
        String msg = String.valueOf(e.getMessage()).toLowerCase(Locale.ROOT);
        return msg.contains("not loaded") || msg.contains("load collection") || msg.contains("未加载")
            || msg.contains("not found") || msg.contains("does not exist") || msg.contains("not exist")
            || msg.contains("不存在");
    }

    private ServiceException wrap(Exception e) {
        if (e instanceof ServiceException se) {
            return se;
        }
        return new ServiceException("Milvus 服务不可用:" + e.getMessage());
    }

    /**
     * 延迟创建客户端：Spring 启动阶段不连 Milvus，避免向量库未就绪拖垮整个平台。
     * enablePrecheck=false 关闭构造期的同步探测。
     */
    private MilvusClientV2 client() {
        if (injectedClient != null) {
            return injectedClient;
        }
        MilvusClientV2 c = client;
        if (c == null) {
            synchronized (this) {
                c = client;
                if (c == null) {
                    c = createClient();
                    client = c;
                }
            }
        }
        return c;
    }

    private MilvusClientV2 createClient() {
        int timeout = properties.getMilvusTimeoutMs() == null ? 10_000 : properties.getMilvusTimeoutMs();
        int port = properties.getMilvusPort() == null ? 19_530 : properties.getMilvusPort();
        String scheme = Boolean.TRUE.equals(properties.getMilvusSecure()) ? "https" : "http";
        ConnectConfig.ConnectConfigBuilder builder = ConnectConfig.builder()
            .uri(scheme + "://" + properties.getMilvusHost() + ":" + port)
            .connectTimeoutMs(timeout)
            .rpcDeadlineMs(timeout * 6L)
            .enablePrecheck(false);
        if (StringUtils.isNotBlank(properties.getMilvusToken())) {
            builder.token(properties.getMilvusToken().trim());
        }
        if (StringUtils.isNotBlank(properties.getMilvusDatabase())) {
            builder.dbName(properties.getMilvusDatabase().trim());
        }
        return new MilvusClientV2(builder.build());
    }

    private ConsistencyLevel consistencyLevel() {
        String raw = properties.getMilvusConsistencyLevel();
        if (StringUtils.isBlank(raw)) {
            return ConsistencyLevel.BOUNDED;
        }
        try {
            return ConsistencyLevel.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            log.warn("[Milvus] 未知一致性级别 {}，回退 BOUNDED", raw);
            return ConsistencyLevel.BOUNDED;
        }
    }

    private String collectionBase() {
        return StringUtils.isBlank(properties.getCollectionName())
            ? "agent_kb_chunk" : properties.getCollectionName().trim();
    }

    private int configuredDim() {
        Integer configured = properties.getVectorDim();
        return configured == null || configured <= 0 ? 384 : configured;
    }

    private static String joinIds(List<Long> ids) {
        return ids.stream().map(String::valueOf).collect(Collectors.joining(","));
    }

    private static Long toLong(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    @PreDestroy
    public void shutdown() {
        MilvusClientV2 c = client;
        if (c != null) {
            try {
                c.close();
            } catch (Exception e) {
                log.warn("[Milvus] 客户端关闭失败: {}", e.getMessage());
            }
        }
    }
}
