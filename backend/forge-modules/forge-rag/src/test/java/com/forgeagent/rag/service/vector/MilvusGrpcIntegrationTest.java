package com.forgeagent.rag.service.vector;

import io.milvus.v2.client.ConnectConfig;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.service.collection.request.DropCollectionReq;
import io.milvus.v2.service.collection.request.HasCollectionReq;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Milvus gRPC 真机闭环：连本机 19530（milvus-lite 或 standalone），验证
 * 建集合 → 写入 → 过滤检索 → 删除。
 *
 * <p>默认跳过，只有显式传 {@code -Dmilvus.it=true} 才执行，避免 CI 依赖外部服务。
 * 集合名固定为 {@code forge_it_grpc}，跑完必定 drop，不碰业务集合。</p>
 */
@Tag("dev")
@EnabledIfSystemProperty(named = "milvus.it", matches = "true")
class MilvusGrpcIntegrationTest {

    private static final String COLLECTION = "forge_it_grpc";

    private MilvusClientV2 client;
    private MilvusVectorStore store;

    @BeforeEach
    void setUp() {
        com.forgeagent.rag.config.KnowledgeProperties properties =
            new com.forgeagent.rag.config.KnowledgeProperties();
        properties.setVectorStore("milvus");
        properties.setMilvusHost("127.0.0.1");
        properties.setMilvusPort(19530);
        properties.setMilvusToken("");
        properties.setMilvusConsistencyLevel("STRONG");
        properties.setCollectionName(COLLECTION);
        properties.setVectorDim(4);

        client = new MilvusClientV2(ConnectConfig.builder()
            .uri("http://127.0.0.1:19530")
            .connectTimeoutMs(5000)
            .rpcDeadlineMs(15000)
            .enablePrecheck(false)
            .build());
        store = new MilvusVectorStore(properties, client);
        dropIfExists();
    }

    @AfterEach
    void tearDown() {
        dropIfExists();
        try {
            client.close();
        } catch (Exception ignored) {
            // 清理失败不影响断言结果
        }
    }

    @Test
    void gRpcFullLoopWorksOnLiteAndStandalone() {
        float[] first = {1.0f, 0.0f, 0.0f, 0.0f};
        float[] second = {0.0f, 1.0f, 0.0f, 0.0f};
        store.ensureCollection(4);
        store.upsert(7L, 9L, List.of(101L, 102L), List.of(first, second));
        store.upsert(7L, 10L, List.of(103L), List.of(second));
        store.upsert(8L, 11L, List.of(104L), List.of(first));

        List<VectorStore.VectorHit> all = store.search(7L, first, 10);
        assertEquals(3, all.size(), "按 knowledge_id 隔离，只应召回库 7 的 3 个分块");
        assertEquals(101L, all.get(0).id(), "同向量应排第一");
        assertEquals(9L, all.get(0).documentId());

        List<VectorStore.VectorHit> excluded = store.search(7L, first, 10,
            new VectorStore.DocFilter(List.of(10L), false));
        assertTrue(excluded.stream().noneMatch(h -> Long.valueOf(103L).equals(h.id())),
            "document_id not in [10] 必须下推到 Milvus");

        List<VectorStore.VectorHit> included = store.search(7L, first, 10,
            new VectorStore.DocFilter(List.of(9L), true));
        assertEquals(2, included.size(), "document_id in [9] 应只保留 2 个分块");

        store.deleteByKnowledgeId(7L);
        assertTrue(store.search(7L, first, 10).isEmpty(), "删除后库 7 应无命中");
        assertEquals(1, store.search(8L, first, 10).size(), "删除不能误伤库 8");
    }

    @Test
    void dimensionShardingUsesDerivedCollection() {
        float[] v4 = {1.0f, 0.0f, 0.0f, 0.0f};
        // 主维度配置为 4；用 8 维写入时应落到 {base}_8，不污染主集合
        float[] v8 = {1.0f, 0, 0, 0, 0, 0, 0, 0};
        store.ensureCollection(4);
        store.upsert(7L, 9L, List.of(201L), List.of(v4));
        store.upsert(7L, 9L, List.of(202L), List.of(v8));

        assertEquals(1, store.search(7L, v4, 10).size());
        assertEquals(1, store.search(7L, v8, 10).size());
        assertTrue(Boolean.TRUE.equals(client.hasCollection(
            HasCollectionReq.builder().collectionName(COLLECTION + "_8").build())),
            "8 维应自动建带维度后缀的派生集合");
    }

    private void dropIfExists() {
        try {
            if (Boolean.TRUE.equals(client.hasCollection(
                HasCollectionReq.builder().collectionName(COLLECTION).build()))) {
                client.dropCollection(DropCollectionReq.builder().collectionName(COLLECTION).build());
            }
            if (Boolean.TRUE.equals(client.hasCollection(
                HasCollectionReq.builder().collectionName(COLLECTION + "_8").build()))) {
                client.dropCollection(DropCollectionReq.builder().collectionName(COLLECTION + "_8").build());
            }
        } catch (Exception e) {
            throw new IllegalStateException("清理测试集合失败", e);
        }
    }
}
