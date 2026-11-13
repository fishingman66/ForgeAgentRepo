package com.forgeagent.rag.service.vector;

import com.forgeagent.common.core.exception.ServiceException;
import com.forgeagent.rag.config.KnowledgeProperties;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.common.ConsistencyLevel;
import io.milvus.v2.common.IndexParam;
import io.milvus.v2.service.collection.request.CreateCollectionReq;
import io.milvus.v2.service.collection.response.ListCollectionsResp;
import io.milvus.v2.service.vector.request.DeleteReq;
import io.milvus.v2.service.vector.request.SearchReq;
import io.milvus.v2.service.vector.request.UpsertReq;
import io.milvus.v2.service.vector.response.SearchResp;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * MilvusVectorStore 的 gRPC 报文契约：不连真机，用注入客户端锁住
 * filter 下推、维度分集合、批量删除、一致性级别与失败语义。
 */
@Tag("dev")
class MilvusVectorStoreContractTest {

    private MilvusClientV2 client;
    private KnowledgeProperties properties;
    private MilvusVectorStore store;

    @BeforeEach
    void setUp() {
        client = mock(MilvusClientV2.class);
        properties = new KnowledgeProperties();
        properties.setVectorStore("milvus");
        properties.setMilvusHost("127.0.0.1");
        properties.setMilvusPort(19530);
        properties.setCollectionName("agent_kb_chunk");
        properties.setVectorDim(384);
        properties.setMilvusConsistencyLevel("BOUNDED");
        // 主集合已存在，避免走创建分支
        when(client.hasCollection(any())).thenReturn(Boolean.TRUE);
        store = new MilvusVectorStore(properties, client);
    }

    private SearchResp singleHit(long id, long documentId, float score) {
        SearchResp.SearchResult hit = SearchResp.SearchResult.builder()
            .id(id)
            .entity(Map.<String, Object>of("document_id", documentId))
            .score(score)
            .build();
        return SearchResp.builder().searchResults(List.of(List.of(hit))).build();
    }

    @Test
    void searchPushesExcludeFilterDownToMilvus() {
        when(client.search(any())).thenReturn(singleHit(101L, 9L, 0.95f));
        List<VectorStore.VectorHit> hits = store.search(7L, new float[384], 5,
            new VectorStore.DocFilter(List.of(10L, 11L), false));

        ArgumentCaptor<SearchReq> captor = ArgumentCaptor.forClass(SearchReq.class);
        verify(client).search(captor.capture());
        SearchReq req = captor.getValue();
        assertEquals("agent_kb_chunk", req.getCollectionName());
        assertEquals("vector", req.getAnnsField());
        assertEquals("knowledge_id == 7 and document_id not in [10,11]", req.getFilter());
        assertEquals(5, req.getTopK());
        assertEquals(5L, req.getLimit());
        assertEquals(List.of("id", "document_id"), req.getOutputFields());
        assertEquals(ConsistencyLevel.BOUNDED, req.getConsistencyLevel());

        assertEquals(1, hits.size());
        assertEquals(Long.valueOf(101L), hits.get(0).id());
        assertEquals(Long.valueOf(9L), hits.get(0).documentId());
        assertEquals(0.95f, hits.get(0).score());
    }

    @Test
    void searchPushesIncludeFilterDownToMilvus() {
        when(client.search(any())).thenReturn(SearchResp.builder().searchResults(List.of()).build());
        store.search(7L, new float[384], 3, new VectorStore.DocFilter(List.of(9L), true));
        ArgumentCaptor<SearchReq> captor = ArgumentCaptor.forClass(SearchReq.class);
        verify(client).search(captor.capture());
        assertEquals("knowledge_id == 7 and document_id in [9]", captor.getValue().getFilter());
    }

    @Test
    void searchWithoutFilterOnlyScopesKnowledge() {
        when(client.search(any())).thenReturn(SearchResp.builder().searchResults(List.of()).build());
        store.search(7L, new float[384], 3);
        ArgumentCaptor<SearchReq> captor = ArgumentCaptor.forClass(SearchReq.class);
        verify(client).search(captor.capture());
        assertEquals("knowledge_id == 7", captor.getValue().getFilter());
    }

    @Test
    void upsertBuildsRowsWithAllScalarFields() {
        store.upsert(7L, 9L, List.of(101L, 102L),
            List.of(new float[384], new float[384]));
        ArgumentCaptor<UpsertReq> captor = ArgumentCaptor.forClass(UpsertReq.class);
        verify(client).upsert(captor.capture());
        UpsertReq req = captor.getValue();
        assertEquals("agent_kb_chunk", req.getCollectionName());
        assertEquals(2, req.getData().size());
        assertEquals(101L, req.getData().get(0).get("id").getAsLong());
        assertEquals(7L, req.getData().get(0).get("knowledge_id").getAsLong());
        assertEquals(9L, req.getData().get(0).get("document_id").getAsLong());
        assertEquals(384, req.getData().get(0).getAsJsonArray("vector").size());
    }

    @Test
    void upsertRejectsMismatchedDimensions() {
        assertThrows(ServiceException.class, () -> store.upsert(7L, 9L,
            List.of(101L, 102L), List.of(new float[384], new float[768])));
    }

    @Test
    void upsertRejectsSizeMismatch() {
        assertThrows(ServiceException.class, () -> store.upsert(7L, 9L,
            List.of(101L), List.of(new float[384], new float[384])));
    }

    @Test
    void derivedCollectionUsesDimensionSuffixAndAutoIndex() {
        // 主集合 384 已存在；1024 维应新建 agent_kb_chunk_1024
        when(client.hasCollection(any())).thenReturn(Boolean.FALSE);
        store = new MilvusVectorStore(properties, client);
        store.ensureCollection(1024);

        ArgumentCaptor<CreateCollectionReq> captor = ArgumentCaptor.forClass(CreateCollectionReq.class);
        verify(client).createCollection(captor.capture());
        CreateCollectionReq req = captor.getValue();
        assertEquals("agent_kb_chunk_1024", req.getCollectionName());
        assertEquals(Boolean.FALSE, req.getEnableDynamicField());
        CreateCollectionReq.FieldSchema vectorField = req.getCollectionSchema().getFieldSchemaList().stream()
            .filter(f -> "vector".equals(f.getName())).findFirst().orElseThrow();
        assertEquals(1024, vectorField.getDimension());
        assertEquals(IndexParam.IndexType.AUTOINDEX, req.getIndexParams().get(0).getIndexType());
        assertEquals(IndexParam.MetricType.COSINE, req.getIndexParams().get(0).getMetricType());
    }

    @Test
    void deleteByChunkIdsDeletesAcrossManagedCollectionsOnly() {
        when(client.listCollections()).thenReturn(ListCollectionsResp.builder()
            .collectionNames(List.of("agent_kb_chunk", "agent_kb_chunk_1024", "unrelated"))
            .build());
        store.deleteByChunkIds(List.of(1L, 2L));

        ArgumentCaptor<DeleteReq> captor = ArgumentCaptor.forClass(DeleteReq.class);
        verify(client, times(2)).delete(captor.capture());
        List<String> names = captor.getAllValues().stream().map(DeleteReq::getCollectionName).toList();
        assertTrue(names.contains("agent_kb_chunk"));
        assertTrue(names.contains("agent_kb_chunk_1024"));
        assertFalse(names.contains("unrelated"));
        for (DeleteReq req : captor.getAllValues()) {
            assertEquals("id in [1,2]", req.getFilter());
        }
    }

    @Test
    void deleteByKnowledgeIdUsesKnowledgeFilter() {
        when(client.listCollections()).thenReturn(ListCollectionsResp.builder()
            .collectionNames(List.of("agent_kb_chunk")).build());
        store.deleteByKnowledgeId(7L);
        ArgumentCaptor<DeleteReq> captor = ArgumentCaptor.forClass(DeleteReq.class);
        verify(client).delete(captor.capture());
        assertEquals("knowledge_id == 7", captor.getValue().getFilter());
    }

    @Test
    void unknownConsistencyLevelFallsBackToBounded() {
        properties.setMilvusConsistencyLevel("NOT_A_LEVEL");
        store = new MilvusVectorStore(properties, client);
        when(client.search(any())).thenReturn(SearchResp.builder().searchResults(List.of()).build());
        store.search(7L, new float[384], 3);
        ArgumentCaptor<SearchReq> captor = ArgumentCaptor.forClass(SearchReq.class);
        verify(client).search(captor.capture());
        assertEquals(ConsistencyLevel.BOUNDED, captor.getValue().getConsistencyLevel());
    }

    @Test
    void recoverableFailureRebuildsCollectionAndRetriesOnce() {
        when(client.search(any()))
            .thenThrow(new RuntimeException("collection not found"))
            .thenReturn(singleHit(101L, 9L, 0.9f));
        List<VectorStore.VectorHit> hits = store.search(7L, new float[384], 3);
        assertEquals(1, hits.size());
        verify(client, times(2)).search(any());
    }

    @Test
    void nonRecoverableFailureIsWrappedAsServiceException() {
        when(client.search(any())).thenThrow(new RuntimeException("boom"));
        ServiceException ex = assertThrows(ServiceException.class,
            () -> store.search(7L, new float[384], 3));
        assertTrue(ex.getMessage().contains("Milvus 服务不可用"));
        verify(client, times(1)).search(any());
    }

    @Test
    void constructorDoesNotConnectToMilvus() {
        // 懒加载：没有注入客户端时构造期不应发起任何 RPC，Milvus 未启动也能装配 Bean
        MilvusVectorStore lazy = new MilvusVectorStore(properties);
        assertEquals(384, primaryDimOf(lazy));
    }

    private static int primaryDimOf(MilvusVectorStore target) {
        try {
            java.lang.reflect.Field field = MilvusVectorStore.class.getDeclaredField("primaryDim");
            field.setAccessible(true);
            return field.getInt(target);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
