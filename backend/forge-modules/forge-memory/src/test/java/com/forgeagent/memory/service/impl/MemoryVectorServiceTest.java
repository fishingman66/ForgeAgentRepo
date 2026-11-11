package com.forgeagent.memory.service.impl;

import com.forgeagent.memory.config.MemoryVectorProperties;
import com.forgeagent.memory.domain.AgentMemory;
import com.forgeagent.memory.mapper.AgentMemoryMapper;
import com.forgeagent.rag.service.embedding.EmbeddingFactory;
import com.forgeagent.rag.service.embedding.LocalHashEmbeddingService;
import com.forgeagent.rag.service.vector.VectorCodec;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * M2' MySQL 向量列回归:开关降级、余弦排序、异步写回向量列。
 *
 * @author ForgeAgent
 */
@Tag("dev")
@ExtendWith(MockitoExtension.class)
class MemoryVectorServiceTest {

    private static final int DIM = 384;

    /** 直接拿 logback logger,用于给「未实现后端只告警一次」造一个可断言的接收端 */
    private static final Logger VECTOR_LOGGER =
        (Logger) LoggerFactory.getLogger(MemoryVectorService.class);

    private Level originalLevel;

    @Mock
    private AgentMemoryMapper memoryMapper;
    @Mock
    private EmbeddingFactory embeddingFactory;

    private MemoryVectorProperties properties;
    private MemoryVectorService service;
    private LocalHashEmbeddingService embedding;

    @BeforeAll
    static void initTableInfo() {
        TableInfoTestSupport.initAgentMemory();
    }

    @BeforeEach
    void setUp() {
        properties = new MemoryVectorProperties();
        embedding = new LocalHashEmbeddingService(DIM);
        service = new MemoryVectorService(memoryMapper, embeddingFactory, properties);
    }

    /** 接管 WARN 级别日志;调用方必须在 finally 里 {@link #detachWarnCapture} 还原 */
    private ListAppender<ILoggingEvent> attachWarnCapture() {
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        originalLevel = VECTOR_LOGGER.getLevel();
        VECTOR_LOGGER.setLevel(Level.WARN);
        VECTOR_LOGGER.addAppender(appender);
        return appender;
    }

    private void detachWarnCapture(ListAppender<ILoggingEvent> appender) {
        VECTOR_LOGGER.detachAppender(appender);
        appender.stop();
        VECTOR_LOGGER.setLevel(originalLevel);
    }

    private AgentMemory row(long id, String content, String model) {
        AgentMemory m = new AgentMemory();
        m.setId(id);
        m.setAgentId(7L);
        m.setUserId(42L);
        m.setContent(content);
        m.setFactKey("k" + id);
        m.setImportance(5);
        m.setHitCount(0);
        m.setCreateTime(LocalDateTime.now());
        float[] vec = embedding.embed(List.of(content)).getFirst();
        m.setEmbedding(VectorCodec.encode(vec));
        m.setEmbeddingDim(vec.length);
        m.setEmbeddingModel(model);
        return m;
    }

    @Test
    void disabledBackendFallsBackSilently() {
        properties.setEnabled(false);
        assertTrue(service.search(7L, 42L, "偏好", 5).isEmpty());
        assertFalse(service.enabled());
    }

    @Test
    void unsupportedBackendIsTreatedAsDisabled() {
        properties.setEnabled(true);
        properties.setBackend("pgvector");
        assertTrue(service.search(7L, 42L, "偏好", 5).isEmpty(), "本期不实现的后端不得半启用");
    }

    @Test
    void backendValueIsTrimmedBeforeMatch() {
        properties.setEnabled(true);
        properties.setBackend(" mysql ");
        assertTrue(service.enabled(), "YAML 里的尾空格不应把已实现的后端判成关闭");
    }

    @Test
    void explicitNoneBackendIsSilentOff() {
        ListAppender<ILoggingEvent> appender = attachWarnCapture();
        try {
            properties.setEnabled(true);
            properties.setBackend("none");
            assertFalse(service.enabled());
            assertTrue(appender.list.isEmpty(), "none 是显式关闭,不是配置错误,不应告警");
        } finally {
            detachWarnCapture(appender);
        }
    }

    @Test
    void unsupportedBackendWarnsOncePerValue() {
        ListAppender<ILoggingEvent> appender = attachWarnCapture();
        try {
            properties.setEnabled(true);
            properties.setBackend("pgvector");
            assertFalse(service.enabled());
            assertFalse(service.enabled());
            assertFalse(service.enabled());
            assertEquals(1, appender.list.size(), "同一错值重复调用只告警一次,否则每轮对话都刷屏");
            assertTrue(appender.list.getFirst().getFormattedMessage().contains("pgvector"),
                "告警要点名是哪个后端取值没实现");

            properties.setBackend("milvus");
            assertFalse(service.enabled());
            assertEquals(2, appender.list.size(), "后端取值变化后应重新告警");
        } finally {
            detachWarnCapture(appender);
        }
    }

    @Test
    void searchRanksByCosineSimilarity() {
        properties.setEnabled(true);
        when(embeddingFactory.forModel("local-hash")).thenReturn(embedding);
        when(memoryMapper.selectList(any())).thenReturn(List.of(
            row(1, "用户偏好简洁回复", LocalHashEmbeddingService.MODEL_KEY),
            row(2, "今天天气很好适合出门", LocalHashEmbeddingService.MODEL_KEY)));
        List<MemoryVectorService.Hit> hits = service.search(7L, 42L, "偏好简洁", 5);
        assertFalse(hits.isEmpty());
        assertEquals(1L, hits.getFirst().memory().getId(), "语义更接近的记忆应排在最前");
    }

    @Test
    void staleVectorRowsAreSkippedByDimensionCheck() {
        properties.setEnabled(true);
        when(embeddingFactory.forModel("local-hash")).thenReturn(embedding);
        AgentMemory wrongDim = row(1, "旧模型向量", LocalHashEmbeddingService.MODEL_KEY);
        wrongDim.setEmbeddingDim(128);
        when(memoryMapper.selectList(any())).thenReturn(List.of(wrongDim));
        assertTrue(service.search(7L, 42L, "旧模型", 5).isEmpty(),
            "维度不符的旧向量应跳过而不是算出错分数");
    }

    @Test
    @SuppressWarnings("unchecked")
    void vectorizeAsyncWritesEncodedVector() {
        properties.setEnabled(true);
        when(embeddingFactory.forModel("local-hash")).thenReturn(embedding);
        AgentMemory stored = new AgentMemory();
        stored.setId(11L);
        stored.setContent("用户住在上海");
        when(memoryMapper.selectById(11L)).thenReturn(stored);

        service.vectorizeAsync(11L);

        ArgumentCaptor<com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<AgentMemory>> captor =
            ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper.class);
        verify(memoryMapper, timeout(4000)).update(isNull(), captor.capture());
        String sqlSet = captor.getValue().getSqlSet();
        assertTrue(sqlSet.contains("embedding"), "写回应包含向量列");
        assertTrue(sqlSet.contains("embedding_model"), "写回应记录模型标识");
    }
}
