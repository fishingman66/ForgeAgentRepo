package com.forgeagent.customerservice.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.forgeagent.customerservice.config.CsModuleProperties;
import com.forgeagent.customerservice.domain.CsConversation;
import com.forgeagent.customerservice.domain.CsCsat;
import com.forgeagent.customerservice.domain.CsMetricDaily;
import com.forgeagent.customerservice.domain.CsTicket;
import com.forgeagent.customerservice.domain.vo.CsFunnelVo;
import com.forgeagent.customerservice.mapper.CsConversationMapper;
import com.forgeagent.customerservice.mapper.CsCsatMapper;
import com.forgeagent.customerservice.mapper.CsMetricDailyMapper;
import com.forgeagent.customerservice.mapper.CsTicketMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A4:服务漏斗三段真实计数——三段同源于 agent_cs_conversation,口径与 containmentRate 一致
 * (未建单会话 = AI 自动解决)。验证计数正确性、workspace 隔离下推、以及 to 含当日的边界归一化。
 */
@Tag("dev")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CsMetricFunnelTest {

    @BeforeAll
    static void warmUpLambdaCache() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, CsConversation.class);
        TableInfoHelper.initTableInfo(assistant, CsMetricDaily.class);
        TableInfoHelper.initTableInfo(assistant, CsTicket.class);
        TableInfoHelper.initTableInfo(assistant, CsCsat.class);
    }

    @Mock private CsConversationMapper conversationMapper;
    @Mock private CsTicketMapper ticketMapper;
    @Mock private CsCsatMapper csatMapper;
    @Mock private CsMetricDailyMapper metricMapper;
    @Mock private CsModuleProperties properties;
    @InjectMocks private CsMetricService service;

    private static CsConversation conv(long id, Long ticketId) {
        CsConversation c = new CsConversation();
        c.setId(id);
        c.setTicketId(ticketId);
        return c;
    }

    private static Date day(String iso) {
        return Date.from(LocalDate.parse(iso).atStartOfDay(ZoneId.systemDefault()).toInstant());
    }

    @Test
    void funnelCountsThreeSegmentsFromSameEntity() {
        when(conversationMapper.selectList(any())).thenReturn(List.of(
            conv(1, null), conv(2, null), conv(3, 100L), conv(4, 101L), conv(5, null)));

        CsFunnelVo vo = service.funnel(7L, day("2026-11-01"), day("2026-11-20"));

        assertEquals(5, vo.getSessions());
        assertEquals(3, vo.getContained());   // ticket_id IS NULL 的三条
        assertEquals(2, vo.getHandoff());     // 已建单两条
        assertEquals(vo.getSessions(), vo.getContained() + vo.getHandoff());
    }

    @Test
    void funnelWithNoConversationsReturnsZeros() {
        when(conversationMapper.selectList(any())).thenReturn(List.of());
        CsFunnelVo vo = service.funnel(7L, day("2026-11-01"), day("2026-11-20"));
        assertEquals(0, vo.getSessions());
        assertEquals(0, vo.getContained());
        assertEquals(0, vo.getHandoff());
    }

    @Test
    void funnelPushesWorkspaceAndInclusiveEndBoundaryToQuery() {
        when(conversationMapper.selectList(any())).thenReturn(List.of());

        service.funnel(42L, day("2026-11-01"), day("2026-11-20"));

        ArgumentCaptor<com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<CsConversation>> captor =
            ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper.class);
        verify(conversationMapper).selectList(captor.capture());
        com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<CsConversation> q = captor.getValue();
        String sql = q.getSqlSegment();
        // workspace 条件下推
        org.junit.jupiter.api.Assertions.assertTrue(sql.contains("workspace_id"), sql);
        org.junit.jupiter.api.Assertions.assertTrue(sql.contains("create_time"), sql);

        // 关键判别:把 to=11-20 归一化成次日 11-21 00:00 作为 < 上界,所选末日全天才会被包含。
        // 只断 sqlSegment 里的 "<" 不够(未按天归一化也会出现 "<"),必须直接断言参数值。
        Date expectedFrom = day("2026-11-01");
        Date expectedEndExclusive = day("2026-11-21");
        java.util.Collection<Object> params = q.getParamNameValuePairs().values();
        org.junit.jupiter.api.Assertions.assertTrue(params.stream().anyMatch(expectedFrom::equals),
            "应含下界 11-01 00:00,实际参数=" + params);
        org.junit.jupiter.api.Assertions.assertTrue(params.stream().anyMatch(expectedEndExclusive::equals),
            "应含归一化后的开区间上界 11-21 00:00(否则漏掉末日全天),实际参数=" + params);
        org.junit.jupiter.api.Assertions.assertTrue(params.stream().noneMatch(day("2026-11-20")::equals),
            "不应把 11-20 00:00 直接当上界,否则末日全天被漏,实际参数=" + params);
    }

    @Test
    void funnelWithNullRangeDoesNotFilterDates() {
        when(conversationMapper.selectList(any())).thenReturn(List.of(conv(1, null), conv(2, 9L)));
        CsFunnelVo vo = service.funnel(7L, null, null);
        assertEquals(2, vo.getSessions());
        assertEquals(1, vo.getContained());
        assertEquals(1, vo.getHandoff());
    }
}
