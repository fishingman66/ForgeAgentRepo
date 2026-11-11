package com.forgeagent.customerservice.service;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** P1:语义缓存三道闸门与相似度口径。 */
@Tag("dev")
class CsSemanticCacheServiceTest {

    @Test
    void normalizeStripsPunctuationAndCase() {
        assertEquals("怎么退货", CsSemanticCacheService.normalize("  怎么 退货？！"));
        assertEquals("refundpolicy", CsSemanticCacheService.normalize("Refund-Policy."));
    }

    @Test
    void personalIdentifierBlocksCache() {
        assertTrue(CsSemanticCacheService.hasPersonalIdentifier("订单 12345678 什么时候到"));
        assertTrue(CsSemanticCacheService.hasPersonalIdentifier("13800138000"));
        assertFalse(CsSemanticCacheService.hasPersonalIdentifier("订单 12345 号"));
        assertFalse(CsSemanticCacheService.hasPersonalIdentifier("怎么退货"));
    }

    @Test
    void intentWhitelistIsStrict() {
        assertTrue(CsSemanticCacheService.intentAllowed("RETURN_REFUND", "RETURN_REFUND,INVOICE"));
        assertTrue(CsSemanticCacheService.intentAllowed("invoice", "RETURN_REFUND,INVOICE"));
        assertFalse(CsSemanticCacheService.intentAllowed("ORDER_STATUS", "RETURN_REFUND,INVOICE"));
        assertFalse(CsSemanticCacheService.intentAllowed(null, "RETURN_REFUND"));
        assertFalse(CsSemanticCacheService.intentAllowed("RETURN_REFUND", ""));
    }

    @Test
    void cacheableRequiresAllGates() {
        assertTrue(CsSemanticCacheService.cacheable("怎么退货", "七天无理由退货", "RETURN_REFUND", "RETURN_REFUND"));
        // 意图不在白名单
        assertFalse(CsSemanticCacheService.cacheable("怎么退货", "七天无理由退货", "ORDER_STATUS", "RETURN_REFUND"));
        // 问题含个人标识
        assertFalse(CsSemanticCacheService.cacheable("订单 12345678 怎么退", "七天无理由", "RETURN_REFUND", "RETURN_REFUND"));
        // 答案含个人标识(绝不能把别人的订单信息缓存后发给下一个访客)
        assertFalse(CsSemanticCacheService.cacheable("怎么退货", "你的订单 12345678 已发出", "RETURN_REFUND", "RETURN_REFUND"));
    }

    @Test
    void similarityIsConservative() {
        assertEquals(1.0, CsSemanticCacheService.similarity("怎么退货", "怎么退货"), 0.0001);
        // 差一个字:短句相似度已明显低于 0.95 阈值
        assertTrue(CsSemanticCacheService.similarity("怎么退货", "怎么退款") < 0.95);
        // 换货与退货不该互相命中
        assertTrue(CsSemanticCacheService.similarity("怎么退货", "怎么换货") < 0.95);
        assertEquals(0.0, CsSemanticCacheService.similarity("", "abc"), 0.0001);
    }
}
