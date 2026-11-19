package com.forgeagent.customerservice.service;

import com.forgeagent.customerservice.config.CsModuleProperties;
import com.forgeagent.customerservice.domain.CsConversation;
import com.forgeagent.customerservice.domain.CsCsat;
import com.forgeagent.customerservice.domain.CsMetricDaily;
import com.forgeagent.customerservice.domain.CsTicket;
import com.forgeagent.customerservice.mapper.CsConversationMapper;
import com.forgeagent.customerservice.mapper.CsCsatMapper;
import com.forgeagent.customerservice.mapper.CsMetricDailyMapper;
import com.forgeagent.customerservice.mapper.CsTicketMapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
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

import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * B3:指标分渠道拆行 + 新指标口径——全渠道行(channel_code NULL)+ 每渠道行;
 * 重复进线率 = 同访客 24h 内已有过进线的会话占比;
 * AI 自动解决率 = 未产生转人工工单即终结的会话占比。
 */
@Tag("dev")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CsMetricServiceChannelTest {

    @BeforeAll
    static void warmUpLambdaCache() {
        // 纯 Mockito 无 Spring 上下文时 MP 的 lambda 列名解析需要预热
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, CsConversation.class);
        TableInfoHelper.initTableInfo(assistant, CsMetricDaily.class);
        TableInfoHelper.initTableInfo(assistant, CsTicket.class);
        TableInfoHelper.initTableInfo(assistant, CsCsat.class);
    }

    @Mock
    private CsConversationMapper conversationMapper;

    @Mock
    private CsTicketMapper ticketMapper;

    @Mock
    private CsCsatMapper csatMapper;

    @Mock
    private CsMetricDailyMapper metricMapper;

    @Mock
    private CsModuleProperties properties;

    @InjectMocks
    private CsMetricService service;

    private CsConversation conversation(long id, Long visitorId, String channel, Long ticketId) {
        CsConversation conv = new CsConversation();
        conv.setId(id);
        conv.setVisitorId(visitorId);
        conv.setChannelCode(channel);
        conv.setTicketId(ticketId);
        conv.setCreateTime(new Date());
        return conv;
    }

    @Test
    void rebuildWritesTotalRowAndPerChannelRows() {
        Date now = new Date();
        List<CsConversation> conversations = List.of(
            conversation(1, 11L, "web", null),      // 自助解决(containment)
            conversation(2, 11L, "web", 100L),      // 同访客当日第二次 + 转人工
            conversation(3, 22L, "app", null));
        // 访客 11 在当日之前 24h 内已有进线 → 两条 web 会话都是重复进线
        List<CsConversation> priors = List.of(conversation(9, 11L, "web", null));
        lenient().when(conversationMapper.selectList(any())).thenReturn(conversations)
            .thenReturn(priors);
        lenient().when(ticketMapper.selectList(any())).thenReturn(List.of());
        lenient().when(csatMapper.selectList(any())).thenReturn(List.of());
        lenient().when(properties.getSlaFirstResponseMs()).thenReturn(60000L);
        lenient().when(metricMapper.selectOne(any())).thenReturn(null);

        java.util.List<com.forgeagent.customerservice.domain.vo.CsMetricDailyVo> rows;
        CsMetricService spyService = org.mockito.Mockito.spy(service);
        org.mockito.Mockito.doAnswer(inv -> {
            java.util.List<?> src = inv.getArgument(0);
            java.util.List<Object> out = new java.util.ArrayList<>();
            for (Object o : src) {
                com.forgeagent.customerservice.domain.vo.CsMetricDailyVo vo = new com.forgeagent.customerservice.domain.vo.CsMetricDailyVo();
                org.springframework.beans.BeanUtils.copyProperties(o, vo);
                out.add(vo);
            }
            return out;
        }).when(spyService).toVoList(org.mockito.ArgumentMatchers.anyList());
        rows = spyService.rebuild(1L, now);

        // 全渠道行 + web 行 + app 行
        assertEquals(3, rows.size());
        assertNull(rows.get(0).getChannelCode());
        assertEquals("web", rows.get(1).getChannelCode());
        assertEquals("app", rows.get(2).getChannelCode());

        com.forgeagent.customerservice.domain.vo.CsMetricDailyVo total = rows.get(0);
        // containment:3 条会话 2 条未建单
        assertEquals(66.67, total.getContainmentRate());
        // 重复进线:访客 11 的 2 条会话命中 24h 内已有进线
        assertEquals(66.67, total.getRepeatContactRate());

        com.forgeagent.customerservice.domain.vo.CsMetricDailyVo web = rows.get(1);
        assertEquals(2, web.getBotSessions());
        assertEquals(100.0, web.getRepeatContactRate());
        assertEquals(50.0, web.getContainmentRate());

        com.forgeagent.customerservice.domain.vo.CsMetricDailyVo app = rows.get(2);
        assertEquals(0.0, app.getRepeatContactRate());
        assertEquals(100.0, app.getContainmentRate());

        verify(metricMapper, times(3)).insert(any(CsMetricDaily.class));
    }

    @Test
    void rebuildUpsertsExistingRows() {
        Date now = new Date();
        List<CsConversation> conversations = List.of(conversation(1, 11L, "web", null));
        lenient().when(conversationMapper.selectList(any())).thenReturn(conversations)
            .thenReturn(List.of());
        lenient().when(ticketMapper.selectList(any())).thenReturn(List.of());
        lenient().when(csatMapper.selectList(any())).thenReturn(List.of());
        lenient().when(properties.getSlaFirstResponseMs()).thenReturn(60000L);
        CsMetricDaily existing = new CsMetricDaily();
        existing.setId(88L);
        when(metricMapper.selectOne(any())).thenReturn(existing);

        CsMetricService spyService = org.mockito.Mockito.spy(service);
        org.mockito.Mockito.doReturn(java.util.List.of()).when(spyService)
            .toVoList(org.mockito.ArgumentMatchers.anyList());
        spyService.rebuild(1L, now);

        ArgumentCaptor<CsMetricDaily> captor = ArgumentCaptor.forClass(CsMetricDaily.class);
        verify(metricMapper, times(2)).updateById(captor.capture());
        // 全渠道行与渠道行都复用已存在行主键
        assertEquals(88L, captor.getAllValues().get(0).getId());
        assertEquals(88L, captor.getAllValues().get(1).getId());
    }
}
