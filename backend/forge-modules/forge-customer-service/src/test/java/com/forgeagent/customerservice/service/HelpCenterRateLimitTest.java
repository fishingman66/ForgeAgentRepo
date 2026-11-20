package com.forgeagent.customerservice.service;

import com.forgeagent.agent.api.customerservice.CsKnowledgeGateway;
import com.forgeagent.customerservice.domain.CsChannel;
import com.forgeagent.customerservice.mapper.HelpCenterMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * B1/F1 公开面安全契约:帮助中心所有入口必须先做渠道校验 + IP 限流 + Origin 白名单。
 *
 * <p>历史缺陷:Controller 注释声称"经 CsChannelGuard IP 预限流",实际只调 require,
 * 任何人都能无成本刷检索。本测试锁死调用顺序,防止回归。</p>
 */
@Tag("dev")
class HelpCenterRateLimitTest {

    private HelpCenterMapper mapper;
    private CsChannelGuard guard;
    private HelpCenterService service;
    private CsChannel channel;

    @BeforeEach
    void setUp() {
        mapper = mock(HelpCenterMapper.class);
        guard = mock(CsChannelGuard.class);
        channel = new CsChannel();
        channel.setWorkspaceId(7L);
        channel.setChannelCode("web");
        channel.setKnowledgeIds("11");
        channel.setStatus("0");
        when(guard.require("web")).thenReturn(channel);
        service = new HelpCenterService(mapper, guard, mock(CsKnowledgeGateway.class));
    }

    @Test
    void listAppliesRateLimitAndOriginCheck() {
        when(mapper.listPublishedDocs(eq(7L), any(), eq("zh_CN"))).thenReturn(List.of());
        when(mapper.listActiveFaqs(7L)).thenReturn(List.of());

        service.list("web", "zh_CN", "1.2.3.4", "https://shop.example.com");

        verify(guard).checkRateLimit(channel, "1.2.3.4");
        verify(guard).checkOrigin(channel, "https://shop.example.com");
    }

    @Test
    void searchAppliesRateLimitBeforeRetrieval() {
        when(mapper.listPublishedDocs(eq(7L), any(), eq(null))).thenReturn(List.of());

        service.search("web", "退款", "zh_CN", "5.6.7.8", "https://shop.example.com");

        verify(guard).checkRateLimit(channel, "5.6.7.8");
        verify(guard).checkOrigin(channel, "https://shop.example.com");
    }

    @Test
    void sitemapXmlAppliesRateLimit() {
        when(mapper.listPublishedDocs(eq(7L), any(), eq(null))).thenReturn(List.of());

        service.sitemapXml("web", "https://help.example.com", "9.9.9.9", "https://shop.example.com");

        verify(guard).checkRateLimit(channel, "9.9.9.9");
        verify(guard).checkOrigin(channel, "https://shop.example.com");
    }
}
