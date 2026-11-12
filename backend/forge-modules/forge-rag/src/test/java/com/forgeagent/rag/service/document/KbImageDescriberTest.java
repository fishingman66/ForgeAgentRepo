package com.forgeagent.rag.service.document;

import com.forgeagent.rag.config.KnowledgeProperties;
import com.forgeagent.rag.service.support.KbVlmConfigResolver;
import com.forgeagent.rag.domain.AgentKnowledge;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * R5-5 图片描述器单测:开关关闭剥离标记、开启但无模型也只剥离、开关判定看库级与全局
 *
 * @author ForgeAgent
 */
@Tag("dev")
class KbImageDescriberTest {

    private final KnowledgeProperties properties = new KnowledgeProperties();
    private final KbVlmConfigResolver resolver = mock(KbVlmConfigResolver.class);
    private final KbImageDescriber describer = new KbImageDescriber(properties, resolver);

    private AgentKnowledge kb(int vlmEnabled) {
        AgentKnowledge kb = new AgentKnowledge();
        kb.setId(1L);
        kb.setVlmEnabled(vlmEnabled);
        return kb;
    }

    private DocumentImage image(int pixels) {
        return new DocumentImage(0, 1, null, "image/png", new byte[]{1, 2, 3}, pixels, pixels);
    }

    @Test
    void disabledStripsMarkers() {
        properties.setVlmEnabled(false);
        KbImageDescriber.Result r = describer.apply("正文\n[[img=0]]\n结尾", List.of(image(400)), kb(1), 1L);
        assertEquals("正文\n\n结尾", r.text());
        assertEquals(0, r.described());
    }

    @Test
    void enabledButNoModelStripsMarkersWithoutFailing() {
        properties.setVlmEnabled(true);
        properties.setVlmModel("");
        KbImageDescriber.Result r = describer.apply("A[[img=0]]B", List.of(image(400)), kb(1), 1L);
        assertEquals("AB", r.text(), "无模型时降级为剥离标记,不阻断解析");
    }

    @Test
    void kbLevelSwitchOffWins() {
        properties.setVlmEnabled(true);
        properties.setVlmModel("vision-model");
        KbImageDescriber.Result r = describer.apply("A[[img=0]]B", List.of(image(400)), kb(0), 1L);
        assertEquals("AB", r.text(), "库级关闭时不描述");
    }

    @Test
    void noResolvedModelIsGraceful() {
        properties.setVlmEnabled(true);
        // 工作空间/平台默认/全局均未命中 → resolver 返回 null
        when(resolver.resolve(any())).thenReturn(null);
        KbImageDescriber.Result r = describer.apply("A[[img=0]]B", List.of(image(400)), kb(1), 1L);
        assertEquals("AB", r.text());
        assertFalse(r.text().contains("图片描述"));
    }

    @Test
    void noMarkerTextUntouched() {
        properties.setVlmEnabled(false);
        KbImageDescriber.Result r = describer.apply("没有图片的正文", List.of(), kb(1), 1L);
        assertEquals("没有图片的正文", r.text());
        assertTrue(r.text().contains("没有图片"));
    }
}
