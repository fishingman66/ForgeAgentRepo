package com.forgeagent.customerservice.channel;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.forgeagent.customerservice.compliance.CsSecretCipher;
import com.forgeagent.customerservice.config.CsModuleProperties;
import com.forgeagent.customerservice.domain.CsChannel;
import com.forgeagent.customerservice.domain.CsMessageReceipt;
import com.forgeagent.customerservice.domain.CsTicket;
import com.forgeagent.customerservice.domain.CsVisitor;
import com.forgeagent.customerservice.mapper.CsChannelMapper;
import com.forgeagent.customerservice.mapper.CsMessageReceiptMapper;
import com.forgeagent.customerservice.mapper.CsVisitorMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * B5:坐席人工回复的邮件回信钩子。锁四件事:
 * ① 非邮件渠道/停用渠道不发送;② replyEnabled=false 不发送;
 * ③ 收件人取 visitor.externalUserId 且主题带工单号;
 * ④ 发送异常被吞,不影响坐席回复主流程(本组件对外不抛异常)。
 */
@Tag("dev")
@ExtendWith(MockitoExtension.class)
class CsMailReplyDispatcherTest {

    @Mock private CsChannelMapper channelMapper;
    @Mock private CsVisitorMapper visitorMapper;
    @Mock private MailTransport mailTransport;
    @Mock private CsMessageReceiptMapper receiptMapper;

    private CsMailReplyDispatcher dispatcher;

    @BeforeAll
    static void warmUp() {
        MapperBuilderAssistant a = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(a, CsChannel.class);
        TableInfoHelper.initTableInfo(a, CsVisitor.class);
        TableInfoHelper.initTableInfo(a, CsMessageReceipt.class);
    }

    @BeforeEach
    void setUp() {
        // 真实 cipher:非 ENC: 前缀的存量明文原样返回,不需 key
        dispatcher = new CsMailReplyDispatcher(channelMapper, visitorMapper, mailTransport,
            new CsSecretCipher(new CsModuleProperties()), receiptMapper);
    }

    private static CsTicket ticket() {
        CsTicket t = new CsTicket();
        t.setId(88L); t.setTicketNo("T-88"); t.setWorkspaceId(5L);
        t.setChannelCode("mail-main"); t.setVisitorId(99L);
        t.setSessionId(123L); // A-3:回执按 sessionId 查询,夹具必须有值
        return t;
    }

    private static CsChannel channel(String type, String status, String config) {
        CsChannel c = new CsChannel();
        c.setWorkspaceId(5L); c.setChannelCode("mail-main");
        c.setChannelType(type); c.setStatus(status); c.setChannelConfig(config);
        return c;
    }

    private static String config(boolean replyEnabled) {
        // parse() 内部会 validate():IMAP 三件套是必填,SMTP 在 replyEnabled=true 时也必填
        return "{\"replyEnabled\":" + replyEnabled
            + ",\"imapHost\":\"imap.example.com\",\"imapUser\":\"bot@example.com\","
            + "\"imapPassword\":\"secret\","
            + "\"smtpHost\":\"smtp.example.com\",\"smtpPort\":25,"
            + "\"smtpUser\":\"bot@example.com\",\"smtpPassword\":\"secret\","
            + "\"fromAddress\":\"bot@example.com\"}";
    }

    @Test
    void nonEmailChannelSkipped() {
        // 真实 DB 的 where(channelType=email) 过滤非邮件渠道;Mockito 不执行 SQL 条件,故模拟为 null。
        // 但必须同时断言查询条件下推了 channel_type=email,否则测试无法发现条件被误删。
        when(channelMapper.selectOne(any())).thenReturn(null);

        dispatcher.sendReply(ticket(), "客户你好");

        verifyNoInteractions(mailTransport, visitorMapper);
        assertChannelQueryFilters();
    }

    @Test
    void disabledChannelSkipped() {
        // 同上:where(status=0) 过滤停用渠道
        when(channelMapper.selectOne(any())).thenReturn(null);

        dispatcher.sendReply(ticket(), "客户你好");

        verifyNoInteractions(mailTransport, visitorMapper);
        assertChannelQueryFilters();
    }

    /** 断言渠道查询确实下推了 workspace/code/type=email/status=0 四个条件。 */
    private void assertChannelQueryFilters() {
        ArgumentCaptor<com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<CsChannel>> cap =
            ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper.class);
        verify(channelMapper).selectOne(cap.capture());
        String sql = cap.getValue().getSqlSegment();
        assertTrue(sql.contains("workspace_id"), sql);
        assertTrue(sql.contains("channel_code"), sql);
        assertTrue(sql.contains("channel_type"), sql);
        assertTrue(sql.contains("status"), sql);
    }

    @Test
    void replyDisabledSkipped() {
        when(channelMapper.selectOne(any())).thenReturn(channel("email", "0", config(false)));

        dispatcher.sendReply(ticket(), "客户你好");

        verifyNoInteractions(mailTransport, visitorMapper);
    }

    @Test
    void sendsToVisitorEmailWithTicketSubject() {
        when(channelMapper.selectOne(any())).thenReturn(channel("email", "0", config(true)));
        CsVisitor v = new CsVisitor(); v.setExternalUserId("  client@example.com  ");
        when(visitorMapper.selectById(99L)).thenReturn(v);

        dispatcher.sendReply(ticket(), "客户你好,已处理");

        ArgumentCaptor<MailTransport.RawMail> cap = ArgumentCaptor.forClass(MailTransport.RawMail.class);
        ArgumentCaptor<EmailChannelConfig> cfgCap = ArgumentCaptor.forClass(EmailChannelConfig.class);
        verify(mailTransport).send(cfgCap.capture(), cap.capture(), eq("客户你好,已处理"));
        assertEquals("client@example.com", cap.getValue().from(), "收件人应去空格");
        assertTrue(cap.getValue().subject().contains("T-88"), "主题应带工单号");
        assertNull(cap.getValue().messageId(), "老数据无原始 Message-ID 时应退化为按地址回信");
    }

    @Test
    @DisplayName("A-3:回执带原始 Message-ID 时人工回信回填线程头")
    void threadsReplyToOriginalMessageId() {
        when(channelMapper.selectOne(any())).thenReturn(channel("email", "0", config(true)));
        CsVisitor v = new CsVisitor(); v.setExternalUserId("client@example.com");
        when(visitorMapper.selectById(99L)).thenReturn(v);
        CsMessageReceipt receipt = new CsMessageReceipt();
        receipt.setRawMessageId("<orig-2026@example.com>");
        when(receiptMapper.selectOne(any())).thenReturn(receipt);

        dispatcher.sendReply(ticket(), "客户你好,已处理");

        ArgumentCaptor<MailTransport.RawMail> cap = ArgumentCaptor.forClass(MailTransport.RawMail.class);
        verify(mailTransport).send(any(), cap.capture(), eq("客户你好,已处理"));
        assertEquals("<orig-2026@example.com>", cap.getValue().messageId());
        ArgumentCaptor<com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<CsMessageReceipt>> qCap =
            ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper.class);
        verify(receiptMapper).selectOne(qCap.capture());
        String sql = qCap.getValue().getSqlSegment();
        assertTrue(sql.contains("session_id"), sql);
        assertTrue(sql.contains("raw_message_id"), sql);
    }

    @Test
    @DisplayName("A-3:agentSessionId 未分配时用 -conversationId 口径查回执")
    void fallsBackToNegativeConversationId() {
        when(channelMapper.selectOne(any())).thenReturn(channel("email", "0", config(true)));
        CsVisitor v = new CsVisitor(); v.setExternalUserId("client@example.com");
        when(visitorMapper.selectById(99L)).thenReturn(v);
        CsTicket t = ticket(); t.setSessionId(null); t.setConversationId(321L);

        dispatcher.sendReply(t, "客户你好");

        ArgumentCaptor<com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<CsMessageReceipt>> qCap =
            ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper.class);
        verify(receiptMapper).selectOne(qCap.capture());
        // MP 把值参数化为 MPGENVAL1,getSqlSegment 里看不到字面 -321;且参数映射由 getSqlSegment 触发填充
        String sql = qCap.getValue().getSqlSegment();
        assertTrue(sql.contains("session_id"), sql);
        assertTrue(qCap.getValue().getParamNameValuePairs().containsValue(-321L),
            String.valueOf(qCap.getValue().getParamNameValuePairs()));
    }

    @Test
    void missingVisitorEmailSkipped() {
        when(channelMapper.selectOne(any())).thenReturn(channel("email", "0", config(true)));
        when(visitorMapper.selectById(99L)).thenReturn(new CsVisitor());

        dispatcher.sendReply(ticket(), "客户你好");

        verifyNoInteractions(mailTransport);
    }

    @Test
    void sendFailureSwallowed() {
        when(channelMapper.selectOne(any())).thenReturn(channel("email", "0", config(true)));
        CsVisitor v = new CsVisitor(); v.setExternalUserId("client@example.com");
        when(visitorMapper.selectById(99L)).thenReturn(v);
        doThrow(new com.forgeagent.common.core.exception.ServiceException("SMTP 挂了"))
            .when(mailTransport).send(any(), any(), anyString());

        assertDoesNotThrow(() -> dispatcher.sendReply(ticket(), "客户你好"));
    }
}
