package com.forgeagent.customerservice.service;

import com.forgeagent.customerservice.config.CsModuleProperties;
import com.forgeagent.kg.service.KgEdge;
import com.forgeagent.kg.service.KgScope;
import com.forgeagent.kg.service.KgService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 图谱辅助检索回归(M3 后续):扩展词组装、已有实体过滤、空空间跳过、开关与上限。
 *
 * @author ForgeAgent
 */
@Tag("dev")
@ExtendWith(MockitoExtension.class)
class CsKgQueryExpanderTest {

    private static final KgScope WS = KgScope.workspace(88L, null);

    @Mock
    private KgService kgService;

    private CsModuleProperties properties;
    private CsKgQueryExpander expander;

    @BeforeEach
    void setUp() {
        properties = new CsModuleProperties();
        expander = new CsKgQueryExpander(kgService, properties);
    }

    private static KgEdge edge(String source, String relation, String target, double weight) {
        return new KgEdge(null, source, source, relation, target, target, weight);
    }

    @Test
    void disabledSwitchReturnsOriginalQuery() {
        properties.setKgExpandEnabled(false);
        assertEquals("怎么退款", expander.expandQuery(88L, "怎么退款"),
            "开关关闭时检索 query 不得变化,升级后行为不变");
        verifyNoInteractions(kgService);
    }

    @Test
    void emptyWorkspaceSentinelSkipsGraph() {
        properties.setKgExpandEnabled(true);
        assertEquals("怎么退款", expander.expandQuery(-1L, "怎么退款"),
            "空空间哨兵不该去查图谱,更不该把领域知识挂到不存在的空间");
        verifyNoInteractions(kgService);
    }

    @Test
    void expansionAppendsNeighborEntities() {
        properties.setKgExpandEnabled(true);
        when(kgService.neighbors(eq(WS), anyList(), anyInt())).thenReturn(List.of(
            edge("退款流程", "属于", "售后领域", 2)));

        String query = expander.expandQuery(88L, "怎么退款");

        assertEquals("怎么退款 退款流程 售后领域", query,
            "扩展词拼在原话之后,把口语表述和领域实体接起来");
    }

    @Test
    void entitiesAlreadyInTextAreNotRepeated() {
        properties.setKgExpandEnabled(true);
        when(kgService.neighbors(eq(WS), anyList(), anyInt())).thenReturn(List.of(
            edge("退款流程", "属于", "售后领域", 2)));

        List<String> terms = expander.expand(88L, "退款流程怎么办");

        assertEquals(List.of("售后领域"), terms, "文本里已有的实体再拼一遍只是噪声");
    }

    @Test
    void singleCharEntityAndCapAreRespected() {
        properties.setKgExpandEnabled(true);
        properties.setKgExpandMaxTerms(1);
        when(kgService.neighbors(eq(WS), anyList(), anyInt())).thenReturn(List.of(
            edge("退款流程", "属于", "售后领域", 2),
            edge("退款流程", "需要", "订单号", 1)));

        List<String> terms = expander.expand(88L, "怎么退款");

        assertEquals(List.of("退款流程"), terms, "扩展词按配置截断,防 query 撑爆");
        assertTrue(terms.stream().noneMatch(t -> t.length() < 2), "单字实体不做扩展");
    }

    @Test
    void noNeighborHitReturnsOriginalQuery() {
        properties.setKgExpandEnabled(true);
        when(kgService.neighbors(eq(WS), anyList(), anyInt())).thenReturn(List.of());
        assertEquals("天气不错", expander.expandQuery(88L, "天气不错"));
    }

    @Test
    void expandDetailedExposesWhetherExpansionHappened() {
        properties.setKgExpandEnabled(true);
        // 实体匹配在 store 里,这里用顺序桩模拟:第一次命中邻接,第二次无命中
        when(kgService.neighbors(eq(WS), anyList(), anyInt()))
            .thenReturn(List.of(edge("退款流程", "属于", "售后领域", 2)), List.of());

        CsKgQueryExpander.Expansion hit = expander.expandDetailed(88L, "怎么退款");
        assertEquals(2, hit.terms().size());
        assertEquals("怎么退款 退款流程 售后领域", hit.query());

        CsKgQueryExpander.Expansion miss = expander.expandDetailed(88L, "天气不错");
        assertTrue(miss.terms().isEmpty());
        assertEquals("天气不错", miss.query(), "未扩展时 terms 必须为空,指标埋点靠它区分");
    }
}
