package com.forgeagent.rag.service.eval;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R3-2 命中判定器单测:归一化、跨块拼合、最小匹配长度、阈值边界
 *
 * @author ForgeAgent
 */
@Tag("dev")
class SpanHitJudgeTest {

    @Test
    void normalizeHandlesNfkcWhitespaceMaskAndCase() {
        // 全角字母/数字 NFKC 半角化;空白与掩码 * 去除;英文小写
        assertEquals("abc,123", SpanHitJudge.normalize("ＡＢＣ，１２３"));
        // 掩码脱敏号去掉 * 后只剩可见位
        assertEquals("1385678", SpanHitJudge.normalize("138****5678"));
        assertEquals("hello", SpanHitJudge.normalize(" He ll O "));
    }

    @Test
    void overlapRatioFullMatchIsOne() {
        String span = "退货政策:签收后七天内可无理由退货";
        double r = SpanHitJudge.overlapRatio("根据规定,退货政策:签收后七天内可无理由退货,运费自理", span);
        assertEquals(1.0, r, 1e-9);
        assertTrue(SpanHitJudge.overlapRatio("完全无关的内容", span) < 0.2);
        assertEquals(0.0, SpanHitJudge.overlapRatio("anything", ""), 1e-9);
    }

    @Test
    void crossChunkSpanCoveredByUnionNotBySingleMax() {
        // span 横跨切分边界:两块各含一半,拼起来才完整
        String span = "售后服务包括退换货与维修两大类";
        String first = "常见问题解答。售后服务包括退换货";
        String second = "与维修两大类,详见第五章。";
        double singleMax = Math.max(
            SpanHitJudge.overlapRatio(first, span),
            SpanHitJudge.overlapRatio(second, span));
        // 单块最大覆盖只有约一半(会被系统性低估)
        assertTrue(singleMax <= 0.6, "单块最大覆盖=" + singleMax);
        double union = SpanHitJudge.coverage(List.of(first, second), span);
        assertEquals(1.0, union, 1e-9, "区间并集应完整覆盖");
    }

    @Test
    void coveragePrefixAccumulationRespectsThreshold() {
        String span = "会员积分年底清零规则说明";
        String c1 = "会员积分年底清零规则说明的一部分前缀内容";
        double cov1 = SpanHitJudge.coverage(List.of(c1), span);
        assertTrue(cov1 >= SpanHitJudge.HIT_COVERAGE_THRESHOLD, "第一块即达标:" + cov1);
        // 恰好一半:等于阈值(>= 判命中,边界语义)
        double cov2 = SpanHitJudge.coverage(List.of("会员积分年底"), span);
        assertEquals(0.5, cov2, 1e-9);
        // 不足阈值
        double cov3 = SpanHitJudge.coverage(List.of("会员积分"), span);
        assertTrue(cov3 < SpanHitJudge.HIT_COVERAGE_THRESHOLD, "部分覆盖=" + cov3);
    }

    @Test
    void singleCharMatchDoesNotCount() {
        // 两句英文只有零散的 2~3 字母巧合重叠,拉丁匹配要求 >=4,不应产生覆盖
        String span = " quantum teleportation protocol ";
        String cand = " the classical channel state ";
        double cov = SpanHitJudge.coverage(List.of(cand), span);
        assertEquals(0.0, cov, 1e-9, "零散 bigram 不应计入");
    }

    @Test
    void documentLevelEmptySpanReturnsZero() {
        assertEquals(0.0, SpanHitJudge.coverage(List.of("任何内容"), ""), 1e-9);
        assertEquals(0.0, SpanHitJudge.coverage(List.of(), "span"), 1e-9);
    }

    @Test
    void overlapsAnyDetectsSpanLevelRelevance() {
        String span = "数据备份每日增量每周全量";
        assertTrue(SpanHitJudge.overlapsAny("系统执行数据备份每日增量", List.of(span)));
        assertFalse(SpanHitJudge.overlapsAny("天气晴朗适合出行", List.of(span)));
    }
}
