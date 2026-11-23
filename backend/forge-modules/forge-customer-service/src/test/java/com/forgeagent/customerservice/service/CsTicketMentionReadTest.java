package com.forgeagent.customerservice.service;

import com.forgeagent.agent.mapper.AgentMessageMapper;
import com.forgeagent.customerservice.domain.CsTicket;
import com.forgeagent.customerservice.mapper.CsConversationMapper;
import com.forgeagent.customerservice.mapper.CsQueueMapper;
import com.forgeagent.customerservice.mapper.CsSeatMapper;
import com.forgeagent.customerservice.mapper.CsTicketEventMapper;
import com.forgeagent.customerservice.mapper.CsTicketMapper;
import com.forgeagent.callcenter.common.CsRealtimeTransport;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.LongStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * A-5:@提及已读状态的服务层契约与 SQL 安全属性。
 *
 * <p>SQL 注解用反射读文本断言,而不是连真实 DB——这里要锁的是
 * 「未读过滤」「空间隔离」「登录态身份下发」三条不漂移,纯 SQL 文本即可判别。</p>
 */
@Tag("dev")
class CsTicketMentionReadTest {

    private CsTicketEventMapper eventMapper;
    private CsTicketService service;

    @BeforeEach
    void setUp() {
        eventMapper = mock(CsTicketEventMapper.class);
        service = new CsTicketService(
            mock(CsTicketMapper.class), eventMapper, mock(CsQueueMapper.class), mock(CsSeatMapper.class),
            mock(CsConversationMapper.class), mock(AgentMessageMapper.class), mock(CsRealtimeTransport.class),
            mock(CsatService.class), mock(CsQualityService.class),
            mock(CsEvalFeedbackService.class), mock(org.springframework.context.ApplicationEventPublisher.class));
    }

    @Test
    @DisplayName("A-5:mark-read 把登录态身份与空间下推到 SQL")
    void markReadPassesLoginScopes() {
        when(eventMapper.markMentionsRead(7L, 42L, List.of(11L, 12L))).thenReturn(2);

        int updated = service.markMentionsRead(7L, 42L, List.of(11L, 12L));

        assertEquals(2, updated);
        verify(eventMapper).markMentionsRead(7L, 42L, List.of(11L, 12L));
    }

    @Test
    @DisplayName("A-5:空身份/空 ID 不碰数据库")
    void rejectsEmptyScopes() {
        assertEquals(0, service.markMentionsRead(null, 42L, List.of(1L)));
        assertEquals(0, service.markMentionsRead(7L, null, List.of(1L)));
        assertEquals(0, service.markMentionsRead(7L, 42L, null));
        assertEquals(0, service.markMentionsRead(7L, 42L, List.of()));
        verifyNoInteractions(eventMapper);
    }

    @Test
    @DisplayName("A-5:去空/去重/上限 200,防止超长 IN 列表")
    void sanitizesIds() {
        List<Long> ids = new ArrayList<>();
        ids.add(null);
        ids.add(9L);
        ids.add(9L);
        LongStream.rangeClosed(1, 205).forEach(ids::add);

        service.markMentionsRead(7L, 42L, ids);

        ArgumentCaptor<List<Long>> cap = ArgumentCaptor.forClass(List.class);
        verify(eventMapper).markMentionsRead(eq(7L), eq(42L), cap.capture());
        List<Long> sent = cap.getValue();
        assertEquals(200, sent.size());
        assertTrue(sent.stream().noneMatch(java.util.Objects::isNull), "null ID 不应下推 SQL");
        assertEquals(200, sent.stream().distinct().count(), "重复 ID 应去重");
    }

    @Test
    @DisplayName("A-5:selectMentions 只返未读且 JOIN 工单做空间隔离")
    void selectMentionsSqlFiltersUnreadAndWorkspace() throws Exception {
        Method m = CsTicketEventMapper.class.getMethod("selectMentions",
            Long.class, Long.class, Long.class, int.class);
        String sql = m.getAnnotation(Select.class).value()[0];
        assertTrue(sql.contains("COALESCE(e.read_flag, 0) = 0"), sql);
        assertTrue(sql.contains("t.workspace_id = #{workspaceId}"), sql);
        assertTrue(sql.contains("e.target_user_id = #{userId}"), sql);
        assertTrue(sql.contains("e.id > #{sinceId}"), sql);
    }

    @Test
    @DisplayName("A-5:mark-read SQL 双条件隔离,绝不接收前端身份")
    void markReadSqlJoinsTicketAndUsesBoundIdentity() throws Exception {
        Method m = CsTicketEventMapper.class.getMethod("markMentionsRead",
            Long.class, Long.class, List.class);
        Update ann = m.getAnnotation(Update.class);
        assertTrue(ann != null, "必须是 @Update 批量语句");
        String sql = ann.value()[0];
        assertTrue(sql.stripLeading().startsWith("<script>"), sql);
        assertTrue(sql.contains("SET e.read_flag = 1"), sql);
        assertTrue(sql.contains("JOIN agent_cs_ticket t ON t.id = e.ticket_id"), sql);
        assertTrue(sql.contains("e.target_user_id = #{userId}"), sql);
        assertTrue(sql.contains("t.workspace_id = #{workspaceId}"), sql);
        assertTrue(sql.contains("<foreach collection=\"ids\""), sql);
    }
}
