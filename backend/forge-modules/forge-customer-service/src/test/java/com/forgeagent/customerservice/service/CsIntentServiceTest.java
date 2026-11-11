package com.forgeagent.customerservice.service;

import com.forgeagent.customerservice.domain.CsIntent;
import com.forgeagent.rag.domain.vo.KnowledgeSearchHitVo;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/** A3:意图规则匹配与检索加权口径(优先级 / 分隔符 / 正则 / 停用 / 重排)。 */
@Tag("dev")
class CsIntentServiceTest {

    private CsIntent intent(Long id, String code, String keywords, String ruleType, int priority) {
        CsIntent intent = new CsIntent();
        intent.setId(id);
        intent.setIntentCode(code);
        intent.setIntentName(code);
        intent.setKeywords(keywords);
        intent.setRuleType(ruleType);
        intent.setPriority(priority);
        intent.setEnabled("1");
        return intent;
    }

    private KnowledgeSearchHitVo hit(Long chunkId, String content, Double score) {
        KnowledgeSearchHitVo vo = new KnowledgeSearchHitVo();
        vo.setChunkId(chunkId);
        vo.setFileName("doc-" + chunkId);
        vo.setContent(content);
        vo.setScore(score);
        return vo;
    }

    @Test
    void firstMatchByPriorityWins() {
        List<CsIntent> rules = List.of(
            intent(1L, "HUMAN_HANDOFF", "转人工,人工客服", "KEYWORD", 5),
            intent(2L, "RETURN_REFUND", "退货,退款", "KEYWORD", 10));
        // 「退款太慢了，我要转人工」两规则都命中，取优先级更高的转人工
        assertEquals("HUMAN_HANDOFF",
            CsIntentService.match(rules, "退款太慢了，我要转人工").getIntentCode());
    }

    @Test
    void separatorsAreCompatible() {
        List<CsIntent> rules = List.of(intent(1L, "RETURN_REFUND", "退货，退款；换货、退钱", "KEYWORD", 10));
        assertEquals("RETURN_REFUND", CsIntentService.match(rules, "我要退款").getIntentCode());
        assertEquals(4, CsIntentService.splitKeywords("退货，退款；换货、退钱").size());
    }

    @Test
    void regexRuleMatches() {
        List<CsIntent> rules = List.of(intent(1L, "ORDER_NO", "\\b[A-Z]{2}\\d{8,}\\b", "REGEX", 10));
        assertEquals("ORDER_NO", CsIntentService.match(rules, "订单号 AB12345678 查一下").getIntentCode());
        assertNull(CsIntentService.match(rules, "订单号查一下"));
    }

    @Test
    void disabledRuleIsSkipped() {
        CsIntent disabled = intent(1L, "RETURN_REFUND", "退货", "KEYWORD", 10);
        disabled.setEnabled("0");
        List<CsIntent> rules = List.of(disabled, intent(2L, "ORDER_STATUS", "订单", "KEYWORD", 20));
        assertNull(CsIntentService.match(rules, "我要退货"));
        assertEquals("ORDER_STATUS", CsIntentService.match(rules, "订单到哪了").getIntentCode());
    }

    @Test
    void noMatchAndBlankReturnNull() {
        List<CsIntent> rules = List.of(intent(1L, "RETURN_REFUND", "退货", "KEYWORD", 10));
        assertNull(CsIntentService.match(rules, "你好"));
        assertNull(CsIntentService.match(rules, "  "));
        assertNull(CsIntentService.match(List.of(), "退货"));
    }

    @Test
    void reweightBoostsIntentKeywordsAndSorts() {
        List<KnowledgeSearchHitVo> hits = List.of(
            hit(1L, "物流时效说明", 0.72),
            hit(2L, "退货流程：请到订单页发起退货申请", 0.70));
        List<KnowledgeSearchHitVo> ordered = CsIntentService.reweight(hits, "退货,退款", "KEYWORD");
        assertEquals(2L, ordered.get(0).getChunkId());
        assertEquals(0.75, ordered.get(0).getScore(), 0.0001);
        assertEquals(0.72, ordered.get(1).getScore(), 0.0001);
    }

    @Test
    void reweightSkipsRegexAndNullIntent() {
        List<KnowledgeSearchHitVo> hits = List.of(hit(1L, "退货流程", 0.70));
        assertEquals(0.70, CsIntentService.reweight(hits, "退货", "REGEX").get(0).getScore(), 0.0001);
        assertSame(hits, CsIntentService.reweight(hits, (CsIntentService.Match) null));
        assertEquals(List.of(), CsIntentService.reweight(null, "退货", "KEYWORD"));
    }

    @Test
    void composeQueryKeepsRecentTurnsBeforeCurrent() {
        String merged = CsIntentService.composeQuery("就这个",
            List.of("第一轮", "我要退货", "再问一句", "多出来的第四轮"));
        // 只保留最近 3 轮,顺序为「历史在前、当前在尾」
        assertEquals("我要退货 再问一句 多出来的第四轮 就这个", merged);
    }

    @Test
    void multiTurnFallbackMatchesIntentFromHistory() {
        List<CsIntent> rules = List.of(intent(1L, "RETURN_REFUND", "退货,退款", "KEYWORD", 10));
        // 指代句自身不含关键词,拼上历史后命中
        assertNull(CsIntentService.match(rules, "就这个"));
        assertEquals("RETURN_REFUND",
            CsIntentService.match(rules, CsIntentService.composeQuery("就这个", List.of("我要退货"))).getIntentCode());
        // 没有历史时拼出来就是原句,行为不变
        assertEquals("就这个", CsIntentService.composeQuery("就这个", List.of()));
    }
}
