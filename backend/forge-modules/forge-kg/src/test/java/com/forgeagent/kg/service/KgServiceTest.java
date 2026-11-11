package com.forgeagent.kg.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.forgeagent.kg.config.KgProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * forge-kg 门面回归:后端选择、未实现后端降级、写入收口、召回降级、冲突判定。
 *
 * @author ForgeAgent
 */
@Tag("dev")
@ExtendWith(MockitoExtension.class)
class KgServiceTest {

    private static final Logger KG_LOGGER = (Logger) LoggerFactory.getLogger(KgService.class);
    private static final KgScope SCOPE = KgScope.agent(7L, 42L);

    @Mock
    private KgStore store;

    private KgProperties properties;
    private KgService service;
    private Level originalLevel;

    @BeforeEach
    void setUp() {
        properties = new KgProperties();
        service = new KgService(List.of(store), properties);
    }

    private ListAppender<ILoggingEvent> attachWarnCapture() {
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        originalLevel = KG_LOGGER.getLevel();
        KG_LOGGER.setLevel(Level.WARN);
        KG_LOGGER.addAppender(appender);
        return appender;
    }

    private void detachWarnCapture(ListAppender<ILoggingEvent> appender) {
        KG_LOGGER.detachAppender(appender);
        appender.stop();
        KG_LOGGER.setLevel(originalLevel);
    }

    @Test
    void disabledSwitchShortCircuitsWithoutTouchingStore() {
        properties.setEnabled(false);
        assertFalse(service.enabled());
        assertTrue(service.neighbors(SCOPE, List.of("任意文本"), 5).isEmpty());
        service.ingest(SCOPE, List.of(new KgTriple("A", "使用", "B", null)));
        verifyNoInteractions(store);
    }

    @Test
    void unsupportedBackendWarnsOnceAndStaysOff() {
        ListAppender<ILoggingEvent> appender = attachWarnCapture();
        try {
            properties.setEnabled(true);
            properties.setBackend("neo4j");
            lenient().when(store.backend()).thenReturn("mysql");
            assertFalse(service.enabled());
            assertFalse(service.enabled());
            assertEquals(1, appender.list.size(), "同一未实现后端只告警一次");
            assertTrue(appender.list.getFirst().getFormattedMessage().contains("neo4j"));
        } finally {
            detachWarnCapture(appender);
        }
    }

    @Test
    void mysqlBackendEnablesGraphAndListsAvailableBackends() {
        properties.setEnabled(true);
        properties.setBackend(" mysql ");
        when(store.backend()).thenReturn("mysql");
        assertTrue(service.enabled(), "配置里的空格不应让已实现后端失效");
        assertEquals(List.of("mysql"), service.availableBackends());
    }

    @Test
    void ingestDropsBlankEntitiesAndCapsCount() {
        properties.setEnabled(true);
        properties.setMaxTriplesPerIngest(2);
        when(store.backend()).thenReturn("mysql");
        List<KgTriple> input = new ArrayList<>();
        input.add(new KgTriple("用户", "负责", "ForgeAgent 项目", 11L));
        input.add(new KgTriple(" ", "使用", "MySQL", null));
        input.add(new KgTriple(" ", "。", "  ", null));
        input.add(new KgTriple("用户", "偏好", "简洁回复", null));

        service.ingest(SCOPE, input);

        ArgumentCaptor<List<KgTriple>> captor = ArgumentCaptor.forClass(List.class);
        verify(store).upsert(eq(SCOPE), captor.capture());
        List<KgTriple> accepted = captor.getValue();
        assertEquals(2, accepted.size(), "空实体应丢弃,并按配置截断");
        assertEquals("用户", accepted.getFirst().subject());
        assertEquals(11L, accepted.getFirst().evidenceRef());
    }

    @Test
    void invalidScopeIsRejected() {
        properties.setEnabled(true);
        service.ingest(new KgScope("agent", null, 42L), List.of(
            new KgTriple("用户", "负责", "项目", null)));
        // 缺 scopeId 的写入必须拒绝,否则会污染所有范围
        verifyNoInteractions(store);
    }

    @Test
    void storeFailureDegradesToEmptyResult() {
        properties.setEnabled(true);
        when(store.backend()).thenReturn("mysql");
        when(store.neighbors(eq(SCOPE), anyList(), anyInt()))
            .thenThrow(new IllegalStateException("图库不可用"));
        assertTrue(service.neighbors(SCOPE, List.of("用户在做 ForgeAgent 项目"), 5).isEmpty(),
            "图谱故障只能让关联信息缺席,不能抛到调用方主流程");
    }

    @Test
    void edgesAndRemoveDegradeOnStoreFailure() {
        properties.setEnabled(true);
        when(store.backend()).thenReturn("mysql");
        when(store.edges(eq(SCOPE), anyInt())).thenThrow(new IllegalStateException("图库不可用"));
        when(store.removeEdge(eq(SCOPE), eq(9L))).thenThrow(new IllegalStateException("图库不可用"));
        assertTrue(service.edges(SCOPE, 10).isEmpty(), "关系列表失败只能得到空结果");
        assertFalse(service.removeEdge(SCOPE, 9L), "删除失败按 false 处理,由调用方提示");
    }

    @Test
    void conflictPairsAreExposedToCallers() {
        KgEdge like = KgEdge.of("用户", "用户", "喜欢", "简洁回复", "简洁回复", 3);
        KgEdge dislike = KgEdge.of("用户", "用户", "不喜欢", "简洁回复", "简洁回复", 2);
        assertTrue(service.conflicts(List.of(like, dislike)).contains(KgConflictPairs.keyOf(like)));
        assertFalse(service.conflicts(List.of(like)).contains(KgConflictPairs.keyOf(like)));
    }

    @Test
    void extraConflictPairsComeFromConfig() {
        properties.setExtraConflictPairs(List.of("解决:导致", "无效项", " :空 "));
        KgEdge solved = KgEdge.of("客服机器人", "客服机器人", "解决", "故障X", "故障X", 2);
        KgEdge caused = KgEdge.of("客服机器人", "客服机器人", "导致", "故障X", "故障X", 2);
        KgEdge unrelated = KgEdge.of("客服机器人", "客服机器人", "解答", "故障X", "故障X", 2);

        assertTrue(service.conflicts(List.of(solved, caused)).contains(KgConflictPairs.keyOf(solved)),
            "配置扩展的「A:B」对要参与冲突判定");
        assertFalse(service.conflicts(List.of(unrelated, solved)).contains(KgConflictPairs.keyOf(unrelated)));
        // 解析失败的项(缺冒号/空)被忽略,不影响内置词表
        assertTrue(service.conflicts(List.of(like2(), dislike2())).contains(KgConflictPairs.keyOf(like2())));
    }

    private static KgEdge like2() {
        return KgEdge.of("用户", "用户", "喜欢", "简洁回复", "简洁回复", 1);
    }

    private static KgEdge dislike2() {
        return KgEdge.of("用户", "用户", "不喜欢", "简洁回复", "简洁回复", 1);
    }
}
