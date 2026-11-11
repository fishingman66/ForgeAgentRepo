package com.forgeagent.rag.service.vector;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import com.forgeagent.rag.config.KnowledgeProperties;
import com.forgeagent.common.core.exception.ServiceException;
import com.forgeagent.common.core.utils.StringUtils;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import java.util.ArrayList;
import java.util.List;
/**
 * Milvus 向量存储实现(REST v2 API,端口 19530,单机版)
 *
 * <p>集合结构:id(Int64 主键)/ knowledge_id(Int64,过滤隔离)/ document_id(Int64)/ vector(FloatVector,COSINE)</p>
 *
 * <p><b>按维度分集合</b>:Milvus 一个集合只能有一个向量维度,而知识库允许各自配置嵌入模型。
 * 主集合沿用配置名(维度 = {@code vector-dim},存量升级零迁移),其他维度自动落到
 * {@code {collectionName}_{dim}}。集合名规则见 {@link VectorCollections}。</p>
 *
 * @author ForgeAgent
 */
@Slf4j
@Component
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
    name = "rag.knowledge.vector-store", havingValue = "milvus", matchIfMissing = true)
public class MilvusVectorStore implements VectorStore {

    /**
     * 单次按主键删除的分批大小(filter 表达式不宜过长)
     */
    private static final int DELETE_BATCH = 500;

    private final KnowledgeProperties properties;
    private final RestClient restClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 主集合实际维度:启动时以 Milvus 里已存在的集合为准(配置改了但集合没重建时,
     * 以实际维度为准才不会把写入全部打回),集合不存在时取配置值
     */
    private volatile int primaryDim;

    /**
     * 已确认存在的维度集合缓存,避免每次检索/写入都发一次 has 请求
     */
    private final Set<Integer> readyDims = ConcurrentHashMap.newKeySet();

    public MilvusVectorStore(KnowledgeProperties properties) {
        this.properties = properties;
        this.primaryDim = configuredDim();
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(properties.getMilvusTimeoutMs());
        factory.setReadTimeout(properties.getMilvusTimeoutMs() * 6);
        var builder = RestClient.builder()
            .baseUrl("http://" + properties.getMilvusHost() + ":" + properties.getMilvusPort() + "/v2/vectordb")
            .requestFactory(factory);
        if (StringUtils.isNotBlank(properties.getMilvusToken())) {
            builder.defaultHeader("Authorization", "Bearer " + properties.getMilvusToken());
        }
        this.restClient = builder.build();
    }

    @Override
    public void ensureCollection() {
        ensureCollection(primaryDim);
    }

    @Override
    public void ensureCollection(int dim) {
        int effective = dim > 0 ? dim : primaryDim;
        if (readyDims.contains(effective)) {
            return;
        }
        String name = VectorCollections.nameFor(collectionBase(), effective, primaryDim);
        if (hasCollection(name)) {
            // 主集合:以实际维度校正 primaryDim,避免"配置改了没重建集合"导致写入维度不符
            if (effective == primaryDim) {
                int actual = describeDim(name);
                if (actual > 0 && actual != primaryDim) {
                    log.warn("[Milvus] 主集合 {} 实际维度 {} 与配置 {} 不一致,以实际维度为准;"
                            + "如需切换维度请先删除该集合或改 collection-name",
                        name, actual, primaryDim);
                    primaryDim = actual;
                }
            }
            loadCollection(name);
            readyDims.add(effective);
            return;
        }
        createCollection(name, effective);
        loadCollection(name);
        readyDims.add(effective);
        log.info("[Milvus] 集合 {} 创建成功(dim={}, AUTOINDEX/COSINE)", name, effective);
    }

    @Override
    public void upsert(Long knowledgeId, Long documentId, List<Long> chunkIds, List<float[]> vectors) {
        if (chunkIds.size() != vectors.size()) {
            throw new ServiceException("向量数量与分块数量不一致");
        }
        if (chunkIds.isEmpty()) {
            return;
        }
        int dim = checkUniformDim(vectors);
        ensureCollection(dim);
        String name = VectorCollections.nameFor(collectionBase(), dim, primaryDim);
        ObjectNode req = objectMapper.createObjectNode();
        req.put("collectionName", name);
        ArrayNode data = req.putArray("data");
        for (int i = 0; i < chunkIds.size(); i++) {
            ObjectNode row = data.addObject();
            row.put("id", chunkIds.get(i));
            row.put("knowledge_id", knowledgeId);
            row.put("document_id", documentId);
            ArrayNode vec = row.putArray("vector");
            for (float v : vectors.get(i)) {
                vec.add(v);
            }
        }
        post("/entities/upsert", req, dim);
    }

    @Override
    public void deleteByDocumentIds(List<Long> documentIds) {
        if (documentIds == null || documentIds.isEmpty()) {
            return;
        }
        String filter = "document_id in [" + documentIds.stream().map(String::valueOf)
            .collect(Collectors.joining(",")) + "]";
        deleteInManagedCollections(filter);
    }

    @Override
    public void deleteByKnowledgeId(Long knowledgeId) {
        deleteInManagedCollections("knowledge_id == " + knowledgeId);
    }

    @Override
    public void deleteByChunkIds(List<Long> chunkIds) {
        if (chunkIds == null || chunkIds.isEmpty()) {
            return;
        }
        // 主键精确删除:向量库行里没有 generation 字段,按 document_id 删会误伤新写入的一代
        for (int from = 0; from < chunkIds.size(); from += DELETE_BATCH) {
            List<Long> batch = chunkIds.subList(from, Math.min(from + DELETE_BATCH, chunkIds.size()));
            deleteInManagedCollections("id in [" + batch.stream().map(String::valueOf)
                .collect(Collectors.joining(",")) + "]");
        }
    }

    @Override
    public List<VectorHit> search(Long knowledgeId, float[] queryVector, int topK) {
        int dim = queryVector == null ? 0 : queryVector.length;
        if (knowledgeId == null || dim <= 0 || topK <= 0) {
            return List.of();
        }
        ensureCollection(dim);
        String name = VectorCollections.nameFor(collectionBase(), dim, primaryDim);
        ObjectNode req = objectMapper.createObjectNode();
        req.put("collectionName", name);
        ArrayNode data = req.putArray("data");
        ArrayNode vec = data.addArray();
        for (float v : queryVector) {
            vec.add(v);
        }
        req.put("filter", "knowledge_id == " + knowledgeId);
        req.put("limit", topK);
        ArrayNode outputFields = req.putArray("outputFields");
        outputFields.add("id").add("document_id");
        try {
            return doSearch(req);
        } catch (ServiceException e) {
            // 集合被删或 Milvus 重启后未加载:重建/重载后重试一次,避免向量检索静默降级
            if (isRecoverable(e) && readyDims.remove(dim)) {
                log.warn("[Milvus] 集合 {} 不可用({}),重建后重试", name, e.getMessage());
                ensureCollection(dim);
                return doSearch(req);
            }
            throw e;
        }
    }

    private List<VectorHit> doSearch(ObjectNode req) {
        JsonNode resp = post("/entities/search", req);
        List<VectorHit> hits = new ArrayList<>();
        JsonNode arr = resp.path("data");
        if (arr.isArray()) {
            for (JsonNode node : arr) {
                hits.add(new VectorHit(
                    node.path("id").asLong(),
                    node.path("document_id").asLong(),
                    (float) node.path("distance").asDouble()));
            }
        }
        return hits;
    }

    /**
     * 在所有受管集合上执行删除:知识库换过嵌入模型时,旧向量可能留在另一个维度集合里,
     * 只删主集合会留下孤儿数据(重建/删除知识库后仍能被检索命中)
     */
    private void deleteInManagedCollections(String filter) {
        List<String> targets = listManagedCollections();
        if (targets.isEmpty()) {
            log.warn("[Milvus] 未发现任何受管集合,跳过删除(filter={})", filter);
            return;
        }
        for (String name : targets) {
            ObjectNode req = objectMapper.createObjectNode();
            req.put("collectionName", name);
            req.put("filter", filter);
            try {
                post("/entities/delete", req);
            } catch (Exception e) {
                // 单个集合删除失败不阻断其他集合:残留向量由重建/重试兜底,但必须留痕
                log.warn("[Milvus] 集合 {} 删除失败: {}", name, e.getMessage());
            }
        }
    }

    /**
     * 列出受管集合;列表接口不可用时退化为当前主集合(宁可少删也不抛错阻断业务)
     */
    private List<String> listManagedCollections() {
        try {
            JsonNode resp = post("/collections/list", objectMapper.createObjectNode());
            List<String> names = new ArrayList<>();
            for (JsonNode node : resp.path("data")) {
                // 新旧版本返回结构不同:字符串数组 或 [{collectionName: ...}]
                names.add(node.isTextual() ? node.asText() : node.path("collectionName").asText(""));
            }
            List<String> managed = VectorCollections.managedOf(collectionBase(), names);
            if (!managed.isEmpty()) {
                return managed;
            }
        } catch (Exception e) {
            log.warn("[Milvus] 列出集合失败,删除仅作用于主集合: {}", e.getMessage());
        }
        return List.of(VectorCollections.nameFor(collectionBase(), primaryDim, primaryDim));
    }

    /**
     * 校验一批向量维度一致,返回维度;不一致直接抛错(写进去只会让检索算错分)
     */
    private int checkUniformDim(List<float[]> vectors) {
        int dim = 0;
        for (float[] v : vectors) {
            if (v == null || v.length == 0) {
                throw new ServiceException("向量为空,无法写入");
            }
            if (dim == 0) {
                dim = v.length;
            } else if (dim != v.length) {
                throw new ServiceException("同一批向量维度不一致(" + dim + " vs " + v.length + ")");
            }
        }
        return dim;
    }

    /**
     * 是否属于"集合不可用"(未加载/已被删),这两类可以重建集合后重试一次
     */
    private boolean isRecoverable(ServiceException e) {
        String msg = e.getMessage() == null ? "" : e.getMessage().toLowerCase();
        return msg.contains("not loaded") || msg.contains("load collection") || msg.contains("未加载")
            || msg.contains("not found") || msg.contains("does not exist") || msg.contains("不存在");
    }

    private String collectionBase() {
        return StringUtils.isBlank(properties.getCollectionName())
            ? "agent_kb_chunk" : properties.getCollectionName().trim();
    }

    private int configuredDim() {
        Integer configured = properties.getVectorDim();
        return configured == null || configured <= 0 ? 384 : configured;
    }

    private boolean hasCollection(String name) {
        ObjectNode req = objectMapper.createObjectNode();
        req.put("collectionName", name);
        JsonNode resp = post("/collections/has", req);
        return resp.path("data").path("has").asBoolean(false);
    }

    /**
     * 读取集合实际向量维度;接口不支持或结构变化时返回 -1(调用方按"未知"处理)
     */
    private int describeDim(String name) {
        try {
            ObjectNode req = objectMapper.createObjectNode();
            req.put("collectionName", name);
            JsonNode resp = post("/collections/describe", req);
            for (JsonNode field : resp.path("data").path("fields")) {
                if (!"vector".equals(field.path("fieldName").asText())) {
                    continue;
                }
                JsonNode dimNode = field.path("elementTypeParams").path("dim");
                if (dimNode.isMissingNode() || dimNode.isNull()) {
                    dimNode = field.path("params").path("dim");
                }
                String raw = dimNode.isMissingNode() || dimNode.isNull() ? "" : dimNode.asText("");
                if (!raw.isBlank()) {
                    return Integer.parseInt(raw.trim());
                }
            }
        } catch (Exception e) {
            log.debug("[Milvus] 读取集合 {} 维度失败: {}", name, e.getMessage());
        }
        return -1;
    }

    private void createCollection(String name, int dim) {
        ObjectNode req = objectMapper.createObjectNode();
        req.put("collectionName", name);
        ObjectNode schema = req.putObject("schema");
        schema.put("autoId", false);
        schema.put("enabledDynamicField", false);
        ArrayNode fields = schema.putArray("fields");
        fields.addObject().put("fieldName", "id").put("dataType", "Int64").put("isPrimary", true);
        fields.addObject().put("fieldName", "knowledge_id").put("dataType", "Int64");
        fields.addObject().put("fieldName", "document_id").put("dataType", "Int64");
        fields.addObject().put("fieldName", "vector").put("dataType", "FloatVector")
            .putObject("elementTypeParams").put("dim", String.valueOf(dim));
        ArrayNode indexParams = req.putArray("indexParams");
        indexParams.addObject().put("fieldName", "vector").put("indexType", "AUTOINDEX").put("metricType", "COSINE");
        post("/collections/create", req);
    }

    private void loadCollection(String name) {
        ObjectNode req = objectMapper.createObjectNode();
        req.put("collectionName", name);
        post("/collections/load", req);
    }

    /**
     * 写操作同样带一次自愈重试:集合被删/未加载时重建再写一次,避免分批写入中途半失败
     */
    private JsonNode post(String path, ObjectNode req, int dim) {
        try {
            return post(path, req);
        } catch (ServiceException e) {
            if (isRecoverable(e) && readyDims.remove(dim)) {
                log.warn("[Milvus] 写入时集合不可用({}),重建后重试", e.getMessage());
                ensureCollection(dim);
                return post(path, req);
            }
            throw e;
        }
    }

    private JsonNode post(String path, ObjectNode req) {
        try {
            String body = restClient.post()
                .uri(path)
                .contentType(MediaType.APPLICATION_JSON)
                .body(objectMapper.writeValueAsString(req))
                .retrieve()
                .body(String.class);
            JsonNode resp = objectMapper.readTree(body);
            int code = resp.path("code").asInt(-1);
            if (code != 0) {
                throw new ServiceException("Milvus 调用失败(code=" + code + "):" + resp.path("message").asText());
            }
            return resp;
        } catch (ServiceException e) {
            throw e;
        } catch (Exception e) {
            throw new ServiceException("Milvus 服务不可用:" + e.getMessage());
        }
    }
}
