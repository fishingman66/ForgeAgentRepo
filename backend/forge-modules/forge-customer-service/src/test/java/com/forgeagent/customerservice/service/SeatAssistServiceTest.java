package com.forgeagent.customerservice.service;

import com.forgeagent.customerservice.domain.CsKnowledgeAnswer;
import com.forgeagent.rag.domain.vo.KnowledgeSearchHitVo;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A1:候选话术组装口径(FAQ 优先 / top3 / 截断 / 空候选)。 */
@Tag("dev")
class SeatAssistServiceTest {

    private CsKnowledgeAnswer faq(String question, String answer) {
        CsKnowledgeAnswer faq = new CsKnowledgeAnswer();
        faq.setId(101L);
        faq.setQuestion(question);
        faq.setAnswer(answer);
        return faq;
    }

    private KnowledgeSearchHitVo hit(Long chunkId, String fileName, String content, Double score) {
        KnowledgeSearchHitVo vo = new KnowledgeSearchHitVo();
        vo.setChunkId(chunkId);
        vo.setFileName(fileName);
        vo.setContent(content);
        vo.setScore(score);
        return vo;
    }

    @Test
    void faqTakesPriorityAndLimitsToTop3() {
        List<Map<String, Object>> items = SeatAssistService.buildItems(
            faq("怎么退货", "支持七天无理由退货"),
            List.of(hit(1L, "退货政策", "退货流程…", 0.9),
                hit(2L, "退款说明", "退款时效…", 0.8),
                hit(3L, "换货说明", "换货流程…", 0.7),
                hit(4L, "超出上限", "不应出现", 0.6)));
        assertEquals(3, items.size());
        assertEquals("faq", items.get(0).get("type"));
        assertEquals(1.00, ((Number) items.get(0).get("score")).doubleValue());
        for (int i = 1; i < 3; i++) {
            assertEquals("kb", items.get(i).get("type"));
        }
        assertTrue(items.stream().noneMatch(i -> "超出上限".equals(i.get("title"))));
    }

    @Test
    void kbContentIsTruncatedToSnippet() {
        String longContent = "字".repeat(SeatAssistService.CONTENT_SNIPPET + 50);
        List<Map<String, Object>> items = SeatAssistService.buildItems(null, List.of(hit(9L, "文档", longContent, 0.5)));
        assertEquals(1, items.size());
        String content = (String) items.get(0).get("content");
        assertEquals(SeatAssistService.CONTENT_SNIPPET, content.length());
    }

    @Test
    void emptyCandidatesReturnEmptyList() {
        assertTrue(SeatAssistService.buildItems(null, List.of()).isEmpty());
        assertTrue(SeatAssistService.buildItems(null, null).isEmpty());
    }

    @Test
    void nullScoreFallsBackToZero() {
        List<Map<String, Object>> items = SeatAssistService.buildItems(null,
            List.of(hit(7L, "无分文档", "内容", null)));
        assertEquals(0.0, ((Number) items.get(0).get("score")).doubleValue());
    }
}
