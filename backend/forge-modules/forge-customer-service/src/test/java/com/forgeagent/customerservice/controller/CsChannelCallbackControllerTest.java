package com.forgeagent.customerservice.controller;

import com.forgeagent.callcenter.common.CsChannelSignature;
import com.forgeagent.customerservice.channel.CsInboundMessage;
import com.forgeagent.customerservice.channel.WecomChannelAdapter;
import com.forgeagent.customerservice.config.CsModuleProperties;
import com.forgeagent.customerservice.domain.CsChannel;
import com.forgeagent.customerservice.mapper.CsChannelMapper;
import com.forgeagent.customerservice.service.CsChannelDispatchService;
import com.forgeagent.customerservice.service.CsChannelGuard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A-4:用 MockMvc 真正打 HTTP 回调端点,验证 verify→parse→dispatch 串联。
 *
 * <p>standaloneSetup 不加载 Sa-Token 拦截器,但验签、时间窗、nonce 去重全部走真实
 * {@link CsChannelGuard} 逻辑;只有 DB 与 Redis 两处外部依赖被隔离——
 * require 用 stub 返回渠道,increment 用 spy 模拟原子计数。</p>
 */
@Tag("dev")
class CsChannelCallbackControllerTest {

    private static final String TOKEN = "token123";

    private CsModuleProperties properties;
    private CsChannelGuard guard;
    private CsChannelDispatchService dispatch;
    private MockMvc mvc;
    private AtomicLong nonceSeq;

    @BeforeEach
    void setUp() {
        properties = new CsModuleProperties();
        dispatch = mock(CsChannelDispatchService.class);
        guard = org.mockito.Mockito.spy(new CsChannelGuard(mock(CsChannelMapper.class), properties, null));
        nonceSeq = new AtomicLong();
        // A-4:隔离 Redis 静态客户端,保留 checkNonce 的真实判定逻辑(首次 1,重放 2)
        doAnswer(inv -> nonceSeq.getAndIncrement() == 0 ? 1L : 2L)
            .when(guard).increment(anyString(), any(Duration.class));
        CsChannelCallbackController controller = new CsChannelCallbackController(
            List.of(new WecomChannelAdapter()), guard, dispatch, properties);
        // standaloneSetup 默认 StringHttpMessageConverter 用 ISO-8859-1,会把中文回包写成 ???;
        // 显式换成 UTF-8,与 Spring Boot 生产默认一致
        mvc = MockMvcBuilders.standaloneSetup(controller)
            .setMessageConverters(new StringHttpMessageConverter(StandardCharsets.UTF_8))
            .build();
    }

    private static CsChannel channel() {
        CsChannel c = new CsChannel();
        c.setChannelCode("wx1");
        c.setChannelType("wecom");
        c.setStatus("0");
        c.setChannelSecret(TOKEN);
        return c;
    }

    private static String ts() {
        return String.valueOf(Instant.now().getEpochSecond());
    }

    private static String nonce() {
        return "n-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private static String signature(String timestamp, String n, String extra) {
        return extra == null
            ? CsChannelSignature.sha1(TOKEN, timestamp, n)
            : CsChannelSignature.sha1(TOKEN, timestamp, n, extra);
    }

    private static String textBody() {
        return "<xml><ToUserName>corp</ToUserName><FromUserName>visitor1</FromUserName>"
            + "<CreateTime>1</CreateTime><MsgType>text</MsgType>"
            + "<Content>你好</Content><MsgId>m-1</MsgId></xml>";
    }

    @Test
    @DisplayName("A-4:GET URL 验证走真实验签并回显 echostr")
    void verifyEcho() throws Exception {
        doReturn(channel()).when(guard).require("wx1");
        String timestamp = ts();
        String n = nonce();
        String echo = "echo-123";

        mvc.perform(get("/chat-channel/wecom/wx1")
                .param("timestamp", timestamp).param("nonce", n).param("echostr", echo)
                .param("signature", signature(timestamp, n, echo)))
            .andExpect(status().isOk())
            .andExpect(content().string(echo));

        verify(guard).checkNonce("wx1", n);
        verifyNoInteractions(dispatch);
    }

    @Test
    @DisplayName("A-4:GET 签名错误返回 403,且不做 nonce 去重")
    void verifyBadSignatureRejected() throws Exception {
        doReturn(channel()).when(guard).require("wx1");
        String n = nonce();

        mvc.perform(get("/chat-channel/wecom/wx1")
                .param("timestamp", ts()).param("nonce", n).param("echostr", "e")
                .param("signature", "wrong-signature"))
            .andExpect(status().isForbidden())
            .andExpect(content().string("fail"));

        verify(guard, times(0)).checkNonce(anyString(), anyString());
    }

    @Test
    @DisplayName("A-4:时间窗外的签名被拒绝")
    void verifyStaleTimestampRejected() throws Exception {
        doReturn(channel()).when(guard).require("wx1");
        String n = nonce();
        String stale = "1";

        mvc.perform(get("/chat-channel/wecom/wx1")
                .param("timestamp", stale).param("nonce", n).param("echostr", "e")
                .param("signature", signature(stale, n, "e")))
            .andExpect(status().isForbidden())
            .andExpect(content().string("fail"));
    }

    @Test
    @DisplayName("A-4:POST 真实验签→解析→分发→XML 回包")
    void postDispatchesAndFormatsXml() throws Exception {
        doReturn(channel()).when(guard).require("wx1");
        doReturn("已收到").when(dispatch).handle(any(), any());
        String timestamp = ts();
        String n = nonce();

        mvc.perform(post("/chat-channel/wecom/wx1")
                .param("timestamp", timestamp).param("nonce", n)
                .param("signature", signature(timestamp, n, null))
                .contentType(MediaType.APPLICATION_XML).content(textBody()))
            .andExpect(status().isOk())
            // standaloneSetup 未挂 Spring Boot 的 UTF-8 编码配置,content().string() 会按
            // ISO-8859-1 解码把中文变成 ???;这里按字节取回并用 UTF-8 解码断言真实报文
            .andExpect(result -> org.junit.jupiter.api.Assertions.assertTrue(
                new String(result.getResponse().getContentAsByteArray(), java.nio.charset.StandardCharsets.UTF_8)
                    .contains("<![CDATA[已收到]]>"),
                "回包应带分发结果: " + result.getResponse().getContentAsString()));

        ArgumentCaptor<CsInboundMessage> cap = ArgumentCaptor.forClass(CsInboundMessage.class);
        verify(dispatch).handle(any(), cap.capture());
        assertEquals("m-1", cap.getValue().msgId());
        assertEquals("visitor1", cap.getValue().externalUserId());
        assertEquals("你好", cap.getValue().content());
        assertEquals("corp", cap.getValue().toUser());
        assertEquals("text", cap.getValue().msgType());
        verify(guard).checkNonce("wx1", n);
    }

    @Test
    @DisplayName("A-4:POST 验签失败不进分发")
    void postBadSignatureSkipsDispatch() throws Exception {
        doReturn(channel()).when(guard).require("wx1");
        String n = nonce();

        mvc.perform(post("/chat-channel/wecom/wx1")
                .param("timestamp", ts()).param("nonce", n).param("signature", "bad")
                .contentType(MediaType.APPLICATION_XML).content(textBody()))
            .andExpect(status().isForbidden())
            .andExpect(content().string("fail"));

        verifyNoInteractions(dispatch);
    }

    @Test
    @DisplayName("A-4:同一 nonce 重放第二次被 403 拒绝")
    void nonceReplayRejected() throws Exception {
        doReturn(channel()).when(guard).require("wx1");
        doReturn("已收到").when(dispatch).handle(any(), any());
        String timestamp = ts();
        String n = "replay-fixed";

        String sig = signature(timestamp, n, null);
        mvc.perform(post("/chat-channel/wecom/wx1")
                .param("timestamp", timestamp).param("nonce", n).param("signature", sig)
                .contentType(MediaType.APPLICATION_XML).content(textBody()))
            .andExpect(status().isOk());

        mvc.perform(post("/chat-channel/wecom/wx1")
                .param("timestamp", timestamp).param("nonce", n).param("signature", sig)
                .contentType(MediaType.APPLICATION_XML).content(textBody()))
            .andExpect(status().isForbidden())
            .andExpect(content().string("fail"));

        assertNotNull(nonceSeq, "计数序列应被消费两次");
        assertEquals(2L, nonceSeq.get(), "两次请求都应进入 increment");
        verify(dispatch, times(1)).handle(any(), any());
    }
}
