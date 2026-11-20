package com.forgeagent.customerservice.channel;

import com.forgeagent.common.core.exception.ServiceException;
import com.forgeagent.customerservice.compliance.CsSecretCipher;
import com.forgeagent.customerservice.domain.CsChannel;
import com.forgeagent.customerservice.service.CsChannelDispatchService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P3 / B5:邮件 → 会话摄取的编排逻辑。
 *
 * <p>不连真实 IMAP/SMTP:传输层被 mock,只验证「幂等键定长、地址归一、
 * 回信开关、单封失败不中断」这些真正会出事故的点。</p>
 *
 * @author ForgeAgent
 */
@Tag("dev")
@DisplayName("邮件渠道摄取")
class MailIngestServiceTest {

    private MailTransport transport;
    private CsChannelDispatchService dispatch;
    private CsSecretCipher cipher;
    private MailIngestService service;

    private static final String CONFIG = "{\"imapHost\":\"imap.a.com\",\"imapUser\":\"u@a.com\","
        + "\"imapPassword\":\"p\",\"fromAddress\":\"u@a.com\","
        + "\"smtpHost\":\"smtp.a.com\",\"smtpUser\":\"u@a.com\",\"smtpPassword\":\"p\"}";

    @BeforeEach
    void setUp() {
        transport = mock(MailTransport.class);
        dispatch = mock(CsChannelDispatchService.class);
        cipher = mock(CsSecretCipher.class);
        service = new MailIngestService(transport, dispatch, cipher);
    }

    private CsChannel channel() {
        CsChannel channel = new CsChannel();
        channel.setId(1L);
        channel.setChannelCode("support-mail");
        channel.setChannelType("email");
        channel.setChannelConfig("ENC:encrypted");
        channel.setStatus("0");
        return channel;
    }

    @Test
    @DisplayName("正常流程:拉取 → 转会话 → 回信;clientMsgId 为定长幂等键")
    void happyPath() {
        when(cipher.decrypt("ENC:encrypted")).thenReturn(CONFIG);
        when(transport.fetch(any(), anyInt())).thenReturn(List.of(
            new MailTransport.RawMail("<abc@mail>", "张三 <Zhang@Example.COM>", "订单咨询", "我的订单在哪", 1L)));
        when(dispatch.handle(any(), any())).thenReturn("已发货");

        int handled = service.pollChannel(channel());

        assertEquals(1, handled);
        ArgumentCaptor<CsInboundMessage> captor = ArgumentCaptor.forClass(CsInboundMessage.class);
        verify(dispatch).handle(any(), captor.capture());
        CsInboundMessage inbound = captor.getValue();
        assertEquals("zhang@example.com", inbound.externalUserId(), "发件人应归一为小写纯地址");
        assertEquals("email", inbound.msgType());
        assertTrue(inbound.msgId().startsWith("em:"), "幂等键应有前缀");
        assertEquals(35, inbound.msgId().length(), "幂等键必须定长(client_msg_id 仅 64)");
        verify(transport).send(any(), any(), eq("已发货"));
    }

    @Test
    @DisplayName("replyEnabled=false 时只入会话不回信")
    void replyDisabled() {
        String cfg = CONFIG.replace("\"fromAddress\":\"u@a.com\",", "\"replyEnabled\":false,");
        when(cipher.decrypt(anyString())).thenReturn(cfg);
        when(transport.fetch(any(), anyInt())).thenReturn(List.of(
            new MailTransport.RawMail("m1", "a@b.com", "s", "t", 1L)));
        when(dispatch.handle(any(), any())).thenReturn("回复");

        service.pollChannel(channel());
        verify(transport, never()).send(any(), any(), anyString());
    }

    @Test
    @DisplayName("缺发件人的邮件被跳过,不调 dispatch")
    void missingSenderSkipped() {
        when(cipher.decrypt(anyString())).thenReturn(CONFIG);
        when(transport.fetch(any(), anyInt())).thenReturn(List.of(
            new MailTransport.RawMail("m1", "  ", "s", "t", 1L)));

        assertEquals(0, service.pollChannel(channel()));
        verify(dispatch, never()).handle(any(), any());
    }

    @Test
    @DisplayName("单封处理失败不中断其余邮件")
    void oneFailureDoesNotAbortBatch() {
        when(cipher.decrypt(anyString())).thenReturn(CONFIG);
        when(transport.fetch(any(), anyInt())).thenReturn(List.of(
            new MailTransport.RawMail("m1", "a@b.com", "s1", "t1", 1L),
            new MailTransport.RawMail("m2", "c@d.com", "s2", "t2", 2L)));
        when(dispatch.handle(any(), any()))
            .thenThrow(new RuntimeException("模型超时"))
            .thenReturn("ok");

        int handled = service.pollChannel(channel());
        assertEquals(1, handled, "第一封失败,第二封应继续");
    }

    @Test
    @DisplayName("配置为空 → 解析失败,不静默当作无邮件")
    void blankConfigRejected() {
        CsChannel channel = channel();
        channel.setChannelConfig(null);
        assertThrows(ServiceException.class, () -> service.pollChannel(channel));
    }

    // ---------------- 静态工具 ----------------

    @Test
    @DisplayName("messageKey 定长、稳定、不同 Message-ID 不同值")
    void messageKeyStable() {
        String a = MailIngestService.messageKey("<abc@x>", "a@b.com", "s");
        assertEquals(a, MailIngestService.messageKey("<abc@x>", "a@b.com", "s"));
        assertEquals(35, a.length());
        assertNotEquals(a, MailIngestService.messageKey("<def@x>", "a@b.com", "s"));
    }

    @Test
    @DisplayName("缺 Message-ID 时用 from+subject 兜底,仍定长")
    void messageKeyFallback() {
        String a = MailIngestService.messageKey(null, "a@b.com", "订单");
        assertEquals(a, MailIngestService.messageKey("  ", "a@b.com", "订单"));
        assertEquals(35, a.length());
        assertNotEquals(a, MailIngestService.messageKey(null, "a@b.com", "退款"));
    }

    @Test
    @DisplayName("地址归一:去显示名、转小写、超长截断")
    void normalizeAddress() {
        assertEquals("a@b.com", MailIngestService.normalizeAddress("张三 <A@B.com>"));
        assertEquals("a@b.com", MailIngestService.normalizeAddress("  a@b.com  "));
        assertEquals("", MailIngestService.normalizeAddress(null));
        String longAddress = "x".repeat(300) + "@b.com";
        assertTrue(MailIngestService.normalizeAddress(longAddress).length() <= 128);
    }

    // ---------------- 配置校验 ----------------

    @Test
    @DisplayName("缺 IMAP 必填项直接报错")
    void configRequiresImap() {
        assertThrows(ServiceException.class,
            () -> EmailChannelConfig.parse("{\"imapHost\":\"h\"}"));
        assertThrows(ServiceException.class, () -> EmailChannelConfig.parse("{}"));
        assertThrows(ServiceException.class, () -> EmailChannelConfig.parse(null));
    }

    @Test
    @DisplayName("开启自动回信却缺 SMTP → 报错(避免回信静默丢失)")
    void replyRequiresSmtp() {
        String cfg = "{\"imapHost\":\"h\",\"imapUser\":\"u\",\"imapPassword\":\"p\",\"replyEnabled\":true}";
        ServiceException ex = assertThrows(ServiceException.class, () -> EmailChannelConfig.parse(cfg));
        assertTrue(ex.getMessage().contains("SMTP"));
    }

    @Test
    @DisplayName("maxPerPoll 被夹在 1..100")
    void maxPerPollClamped() {
        String base = "{\"imapHost\":\"h\",\"imapUser\":\"u\",\"imapPassword\":\"p\",\"replyEnabled\":false,";
        assertEquals(100, EmailChannelConfig.parse(base + "\"maxPerPoll\":9999}").maxPerPoll());
        assertEquals(1, EmailChannelConfig.parse(base + "\"maxPerPoll\":0}").maxPerPoll());
    }

    @Test
    @DisplayName("describe() 不泄露密码")
    void describeRedactsSecrets() {
        EmailChannelConfig cfg = EmailChannelConfig.parse(
            "{\"imapHost\":\"h\",\"imapUser\":\"u\",\"imapPassword\":\"SECRET-PWD\",\"replyEnabled\":false}");
        assertFalse(cfg.describe().contains("SECRET-PWD"));
    }

    @Test
    @DisplayName("邮件配置整体加密:落库值不是明文 JSON")
    void configIsEncryptedAtRest() {
        // 由 SeatController 负责加密,这里只断言"密文形态不可被直接 parse"
        assertThrows(ServiceException.class, () -> EmailChannelConfig.parse("ENC:abc"));
    }

    /** 防回归:配置键名与文档一致 */
    @Test
    @DisplayName("示例配置可被完整解析")
    void sampleConfigParses() {
        EmailChannelConfig cfg = EmailChannelConfig.parse(CONFIG);
        assertEquals("imap.a.com", cfg.imapHost());
        assertEquals("INBOX", cfg.imapFolder());
        assertTrue(cfg.imapSsl());
        assertTrue(cfg.replyEnabled());
    }
}
