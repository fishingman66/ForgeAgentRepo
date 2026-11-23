package com.forgeagent.customerservice.channel;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A-4:用本地 fake SMTP 真正跑一遍 {@link JakartaMailTransport#send},不 mock 传输层。
 *
 * <p>验证的是 Jakarta Mail 默认握手(EHLO→AUTH→MAIL→RCPT→DATA→QUIT)能收到报文,
 * 以及 A-3 回填的线程头确实写进真实 SMTP 载荷;不涉及任何外部邮箱账号。</p>
 */
@Tag("dev")
class JakartaMailTransportFakeSmtpTest {

    private static final Duration WAIT = Duration.ofSeconds(5);

    private static String config(int port) {
        // imap 三件套是 parse().validate() 的必填项;这里只测 send,填占位值即可
        return "{\"imapHost\":\"imap.example.com\",\"imapUser\":\"u@example.com\","
            + "\"imapPassword\":\"p\",\"replyEnabled\":true,"
            + "\"smtpHost\":\"127.0.0.1\",\"smtpPort\":" + port + ",\"smtpSsl\":false,"
            + "\"smtpUser\":\"bot@example.com\",\"smtpPassword\":\"secret\","
            + "\"fromAddress\":\"bot@example.com\"}";
    }

    @Test
    @DisplayName("A-4:真实 SMTP 发送并带 In-Reply-To/References")
    void sendsThroughFakeSmtpWithThreadHeaders() throws Exception {
        try (FakeSmtpServer smtp = new FakeSmtpServer()) {
            JakartaMailTransport transport = new JakartaMailTransport();

            transport.send(EmailChannelConfig.parse(config(smtp.port())),
                new MailTransport.RawMail("<orig-2026@example.com>", "client@example.com",
                    "Order question", "draft", 1L), "SHIPPED-OK");

            assertNotNull(smtp.awaitCommand("EHLO", WAIT), "应走 EHLO 握手,命令=" + smtp.commands());
            assertNotNull(smtp.awaitCommand("AUTH", WAIT), "应完成 SMTP AUTH,命令=" + smtp.commands());
            assertNotNull(smtp.awaitCommand("MAIL FROM", WAIT), "命令=" + smtp.commands());
            String rcpt = smtp.awaitCommand("RCPT TO", WAIT);
            assertNotNull(rcpt, "命令=" + smtp.commands());
            assertTrue(rcpt.contains("client@example.com"), "收件人必须来自 RawMail.from():" + rcpt);

            String payload = smtp.awaitPayload(WAIT);
            assertNotNull(payload, "fake SMTP 应收到 DATA 载荷,命令=" + smtp.commands());
            assertTrue(payload.contains("Subject: Re: Order question"), payload);
            assertTrue(payload.contains("In-Reply-To: <orig-2026@example.com>"), payload);
            assertTrue(payload.contains("References: <orig-2026@example.com>"), payload);
            assertTrue(payload.contains("SHIPPED-OK"), payload);
        }
    }

    @Test
    @DisplayName("A-4 + A-3:无原始 Message-ID 时不写线程头(老数据退化)")
    void oldMailWithoutMessageIdSkipsThreadHeaders() throws Exception {
        try (FakeSmtpServer smtp = new FakeSmtpServer()) {
            new JakartaMailTransport().send(EmailChannelConfig.parse(config(smtp.port())),
                new MailTransport.RawMail(null, "client@example.com", "Order question", "draft", 1L),
                "SHIPPED-OK");

            String payload = smtp.awaitPayload(WAIT);
            assertNotNull(payload, "命令=" + smtp.commands());
            assertFalse(payload.contains("In-Reply-To"), payload);
            assertFalse(payload.contains("References:"), payload);
            assertTrue(payload.contains("SHIPPED-OK"), payload);
        }
    }
}
