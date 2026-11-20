package com.forgeagent.customerservice.service;

import com.forgeagent.agent.api.customerservice.CsKnowledgeGateway;
import com.forgeagent.common.core.exception.ServiceException;
import com.forgeagent.customerservice.domain.CsChannel;
import com.forgeagent.customerservice.domain.vo.HelpArticleVo;
import com.forgeagent.customerservice.mapper.CsChannelMapper;
import com.forgeagent.customerservice.mapper.HelpCenterMapper;
import com.forgeagent.customerservice.config.CsModuleProperties;
import com.forgeagent.customerservice.compliance.CsSecretCipher;
import com.forgeagent.rag.domain.vo.KnowledgeSearchHitVo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** B1/F1/F3 帮助中心:搜索多 chunk 合并 / 响应白名单 / 详情防穿越 / locale。 */
@Tag("dev")
class HelpCenterServiceTest {

    private HelpCenterMapper mapper;
    private CsKnowledgeGateway gateway;
    private CsChannelGuard guard;
    private HelpCenterService service;

    @BeforeEach
    void setUp() {
        mapper = mock(HelpCenterMapper.class);
        gateway = mock(CsKnowledgeGateway.class);
        CsChannelMapper channelMapper = mock(CsChannelMapper.class);
        guard = new CsChannelGuard(channelMapper, new CsModuleProperties(), (CsSecretCipher) null);
        service = new HelpCenterService(mapper, guard, gateway);
    }

    private CsChannel channel() {
        CsChannel c = new CsChannel();
        c.setWorkspaceId(7L);
        c.setKnowledgeIds("11,12");
        c.setStatus("0");
        return c;
    }

    private KnowledgeSearchHitVo hit(long docId, String content, double score) {
        KnowledgeSearchHitVo h = new KnowledgeSearchHitVo();
        h.setDocumentId(docId);
        h.setChunkId(1000 + docId);
        h.setFileName("退款说明.md");
        h.setContent(content);
        h.setScore(score);
        return h;
    }

    @Test
    void searchMergesChunksByDocumentAndKeepsHighest() {
        CsChannelMapper cm = mock(CsChannelMapper.class);
        when(cm.selectOne(any())).thenReturn(channel());
        CsChannelGuard g = new CsChannelGuard(cm, new CsModuleProperties(), null);
        HelpCenterService svc = new HelpCenterService(mapper, g, gateway);

        when(gateway.retrieve(eq(7L), any(), eq("退款"))).thenReturn(new CsKnowledgeGateway.RetrievalOutcome(
            List.of(hit(11L, "退款低分片段", 0.3), hit(11L, "退款高分片段", 0.9), hit(12L, "退款另一篇", 0.5)),
            Map.of()));

        List<HelpArticleVo> result = svc.search("web", "退款", "zh_CN");
        assertEquals(2, result.size(), "同一文档两 chunk 应合并为一条");
        HelpArticleVo first = result.stream().filter(a -> a.getSlug().equals("d11")).findFirst().orElseThrow();
        assertTrue(first.getSummary().contains("退款高分片段"), "应保留最高分 chunk 片段");
        assertEquals("退款说明.md", first.getTitle());
        assertNotNull(first.getSegments(), "搜索结果应带结构化高亮片段");
        assertTrue(first.getSegments().stream().anyMatch(HelpArticleVo.Segment::hit), "命中片段应标记 hit");
        // 白名单:slug/title/summary 有值,无内部字段泄漏( VO 结构上就不含 chunkId/score/workspaceId )
        assertNotNull(first.getSlug());
    }

    @Test
    void searchEmptyQueryReturnsEmpty() {
        CsChannelMapper cm = mock(CsChannelMapper.class);
        when(cm.selectOne(any())).thenReturn(channel());
        CsChannelGuard g = new CsChannelGuard(cm, new CsModuleProperties(), null);
        HelpCenterService svc = new HelpCenterService(mapper, g, gateway);
        assertTrue(svc.search("web", "  ", "zh_CN").isEmpty());
        verifyNoInteractions(gateway);
    }

    @Test
    void highlightKeepsRawTextNoHtmlInjection() {
        CsChannelMapper cm = mock(CsChannelMapper.class);
        when(cm.selectOne(any())).thenReturn(channel());
        CsChannelGuard g = new CsChannelGuard(cm, new CsModuleProperties(), null);
        HelpCenterService svc = new HelpCenterService(mapper, g, gateway);
        when(gateway.retrieve(eq(7L), any(), eq("退款"))).thenReturn(new CsKnowledgeGateway.RetrievalOutcome(
            List.of(hit(20L, "<script>alert(1)</script> 退款说明", 0.8)), Map.of()));

        HelpArticleVo vo = svc.search("web", "退款", "zh_CN").get(0);
        StringBuilder joined = new StringBuilder();
        for (HelpArticleVo.Segment seg : vo.getSegments()) {
            joined.append(seg.text());
        }
        assertEquals("<script>alert(1)</script> 退款说明", joined.toString(),
            "高亮只切纯文本片段,不生成/改写 HTML,前端插值渲染才安全");
        assertFalse(vo.getSegments().stream().anyMatch(s -> s.hit() && s.text().contains("<")),
            "命中片段不应嵌入 HTML 标签");
    }

    @Test
    void detailRejectsFaqStyleSlug() {
        assertThrows(ServiceException.class, () -> service.detail("web", "faq-123"));
        verifyNoInteractions(mapper);
    }

    @Test
    void listMapsPublishedDocWithoutInternalFields() {
        CsChannelMapper cm = mock(CsChannelMapper.class);
        when(cm.selectOne(any())).thenReturn(channel());
        CsChannelGuard g = new CsChannelGuard(cm, new CsModuleProperties(), null);
        HelpCenterService svc = new HelpCenterService(mapper, g, null);
        when(mapper.listPublishedDocs(eq(7L), any(), eq("zh_CN"))).thenReturn(List.of(
            Map.of("id", 55L, "title", "退款流程", "summary", "在订单页申请", "locale", "zh_CN", "source", "doc")));
        List<HelpArticleVo> list = svc.list("web", "zh_CN");
        assertEquals(1, list.size());
        assertEquals("d55", list.get(0).getSlug());
        assertEquals("退款流程", list.get(0).getTitle());
        assertEquals("zh_CN", list.get(0).getLocale());
        assertFalse(list.get(0).isFallback());
    }

    /** F3:英文无内容时必须回退默认语言,而不是返回空列表。 */
    @Test
    void listFallsBackToDefaultLocaleWhenTargetEmpty() {
        CsChannelMapper cm = mock(CsChannelMapper.class);
        when(cm.selectOne(any())).thenReturn(channel());
        CsChannelGuard g = new CsChannelGuard(cm, new CsModuleProperties(), null);
        HelpCenterService svc = new HelpCenterService(mapper, g, null);
        when(mapper.listPublishedDocs(eq(7L), any(), eq("en_US"))).thenReturn(List.of());
        when(mapper.listPublishedDocs(eq(7L), any(), eq("zh_CN"))).thenReturn(List.of(
            Map.of("id", 66L, "title", "退款流程", "summary", "在订单页申请", "locale", "zh_CN", "source", "doc")));
        when(mapper.listActiveFaqs(7L)).thenReturn(List.of());

        List<HelpArticleVo> list = svc.list("web", "en_US");
        assertEquals(1, list.size(), "英文无内容应回退中文,不能空列表");
        assertEquals("zh_CN", list.get(0).getLocale());
        assertTrue(list.get(0).isFallback(), "回退文章必须带 fallback 标记");
    }

    /** F3:即使没有文档,只有默认语言 FAQ 也要作为兜底返回。 */
    @Test
    void listFallsBackToFaqWhenNoDocsAtAll() {
        CsChannelMapper cm = mock(CsChannelMapper.class);
        when(cm.selectOne(any())).thenReturn(channel());
        CsChannelGuard g = new CsChannelGuard(cm, new CsModuleProperties(), null);
        HelpCenterService svc = new HelpCenterService(mapper, g, null);
        when(mapper.listPublishedDocs(eq(7L), any(), any())).thenReturn(List.of());
        when(mapper.listActiveFaqs(7L)).thenReturn(List.of(
            Map.of("title", "怎么退货", "summary", "在订单页申请", "source", "faq")));

        List<HelpArticleVo> list = svc.list("web", "en_US");
        assertEquals(1, list.size());
        assertTrue(list.get(0).isFallback());
        assertEquals("zh_CN", list.get(0).getLocale());
    }

    /** F3:sitemap 必须是绝对 URL,并输出 xhtml:link hreflang 互链。 */
    @Test
    void sitemapXmlContainsAbsoluteLocAndHreflang() {
        CsChannelMapper cm = mock(CsChannelMapper.class);
        when(cm.selectOne(any())).thenReturn(channel());
        CsChannelGuard g = new CsChannelGuard(cm, new CsModuleProperties(), null);
        HelpCenterService svc = new HelpCenterService(mapper, g, null);
        when(mapper.listPublishedDocs(eq(7L), any(), eq(null))).thenReturn(List.of(
            Map.of("id", 77L, "title", "退款流程", "summary", "x", "locale", "en_US")));

        String xml = svc.sitemapXml("web", "https://help.example.com/");
        assertTrue(xml.contains("<loc>https://help.example.com/help/zh_CN</loc>"));
        assertTrue(xml.contains("hreflang=\"en\""));
        assertTrue(xml.contains("hreflang=\"x-default\""));
        assertTrue(xml.contains("<loc>https://help.example.com/help/en_US/d77</loc>"));
        assertFalse(xml.contains("https://help.example.com//"), "baseUrl 末尾斜杠必须收敛");
    }
}
