package com.forgeagent.rag.service.eval;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R3-3 五指标单测:手工构造 3 个用例验算(K=5)
 *
 * <pre>
 * caseA: 命中 rank=1,2 证据全中(2/2),相关条数 2,rel=[1,1,0,0,0]
 * caseB: 命中 rank=3,1 证据(1/1),相关条数 1,rel=[0,0,1,0,0]
 * caseC: 未命中,1 证据 0 中,相关条数 0,rel=[0,0,0,0,0]
 * </pre>
 *
 * <p>手算:HitRate=2/3;Recall=(1+1+0)/3=2/3;Precision=(2+1+0)/15=0.2;
 * MRR=(1+1/3+0)/3=4/9;NDCG:caseA 前 2 名全相关且 ideal=2 → 1.0,
 * caseB rel=[0,0,1,...] dcg=1/log2(4)=0.5、ideal=1 → 0.5,caseC=0 → 均值=0.5。</p>
 *
 * @author ForgeAgent
 */
@Tag("dev")
class EvalMetricsCalculatorTest {

    private static final int K = 5;

    private EvalMetricsCalculator.Metrics compute() {
        List<EvalMetricsCalculator.CaseOutcome> outcomes = List.of(
            new EvalMetricsCalculator.CaseOutcome("A", "span", true, 1, 2, 2, 2,
                EvalMetricsCalculator.ndcgOfCase(List.of(1, 1, 0, 0, 0), 2, K)),
            new EvalMetricsCalculator.CaseOutcome("B", "span", true, 3, 1, 1, 1,
                EvalMetricsCalculator.ndcgOfCase(List.of(0, 0, 1, 0, 0), 1, K)),
            new EvalMetricsCalculator.CaseOutcome("C", "document", false, -1, 1, 0, 0,
                EvalMetricsCalculator.ndcgOfCase(List.of(0, 0, 0, 0, 0), 1, K)));
        return EvalMetricsCalculator.compute(outcomes, K);
    }

    @Test
    void overallMetricsMatchHandCalculation() {
        EvalMetricsCalculator.Metrics m = compute();
        assertEquals(2.0 / 3, m.overall().hitRate(), 1e-9);
        assertEquals(2.0 / 3, m.overall().recall(), 1e-9);
        assertEquals(0.2, m.overall().precision(), 1e-9);
        assertEquals(4.0 / 9, m.overall().mrr(), 1e-9);
        double expectedNdcg = (1.0 + 0.5 + 0.0) / 3;
        assertEquals(expectedNdcg, m.overall().ndcg(), 1e-9);
    }

    @Test
    void groupsSplitAndEmptyOmitted() {
        EvalMetricsCalculator.Metrics m = compute();
        assertEquals(3, m.overall().cases());
        // span 组 2 例,document 组 1 例,legacy 组没有用例 → 省略
        assertEquals(2, m.groups().size());
        assertTrue(m.groups().stream().noneMatch(g -> "legacy".equals(g.group())));
        assertEquals(2, m.groups().stream().filter(g -> "span".equals(g.group())).findFirst().orElseThrow().cases());
    }

    @Test
    void wilsonIntervalCoversPointAndStaysInUnit() {
        double[] ci = EvalMetricsCalculator.wilson(2, 3);
        assertTrue(ci[0] <= 2.0 / 3 && 2.0 / 3 <= ci[1], "点估计应在区间内");
        assertTrue(ci[0] >= 0 && ci[1] <= 1);
        // n=0 不炸
        assertEquals(0, EvalMetricsCalculator.wilson(0, 0)[0]);
    }

    @Test
    void ndcgObservedCapPreventsOverOne() {
        // 观测相关 5 > 标注 2:ideal=max(2,5) 取 min(...,K)=5 → 满排 DCG=IDCG,NDCG=1 而不是 >1
        double ndcg = EvalMetricsCalculator.ndcgOfCase(List.of(1, 1, 1, 1, 1), 2, K);
        assertEquals(1.0, ndcg, 1e-9);
    }
}
