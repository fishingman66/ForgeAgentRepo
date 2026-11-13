package com.forgeagent.callcenter.insight;

import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 词典法情绪分析(C4 默认实现):不依赖模型与网络,可离线可复现。
 *
 * <p>算法:统计负面词与正面词命中次数,{@code score = (pos - neg) / max(1, pos + neg)},
 * 取值收敛到 [-1,1]。词表覆盖中文口语与英文常见表达——
 * 电话场景的负面信号以「慢/没人管/投诉/退钱」为主,正面信号多是致谢。</p>
 *
 * <p>局限与去处:词典法分不清反讽与否定(「不是不好」会被判负面),
 * 语音情绪还应结合声学特征;这两点都留给 LLM/声学实现(C4-real 与 C5 双引擎),
 * 本实现作为无外部依赖的基线,保证管道在无模型时也产出可用信号。</p>
 */
@Component
public class LexiconSentimentAnalyzer implements SentimentAnalyzer {

    /** 引擎标识。 */
    public static final String CODE = "lexicon";

    /** 负面词表(长词在前,避免「不满意」被「满意」抢先命中——统计用 contains,需去重顺序)。 */
    private static final List<String> NEGATIVE = List.of(
        "不满意", "没解决", "解决不了", "没人管", "等太久", "太慢", "很慢", "真慢",
        "投诉", "退钱", "赔偿", "生气", "气死", "恶心", "垃圾", "骗子", "欺骗",
        "失望", "无语", "离谱", "差劲", "太差", "很烂", "坏了", "故障", "催",
        "烦", "讨厌", "坑人", "不行", "没用", "退款", "退货", "拒绝",
        "angry", "terrible", "awful", "useless", "scam", "slow", "worst", "complaint", "refund");

    /** 正面词表。 */
    private static final List<String> POSITIVE = List.of(
        "谢谢", "感谢", "多谢", "辛苦了", "满意", "不错", "很好", "挺好", "棒",
        "可以了", "解决了", "有帮助", "耐心", "专业",
        "thanks", "thank you", "great", "good", "perfect", "awesome", "helpful");

    @Override
    public String code() {
        return CODE;
    }

    @Override
    public Result analyze(String text) {
        String normalized = text.toLowerCase();
        int negative = countHits(normalized, NEGATIVE);
        int positive = countHits(normalized, POSITIVE);
        double score = (double) (positive - negative) / Math.max(1, positive + negative);
        return new Result(labelOf(score), score);
    }

    /**
     * 命中计数:每个词只计一次(同一句里重复出现不叠加权重,
     * 避免「太慢了太慢了」把分数压到极端值)。
     */
    private static int countHits(String text, List<String> words) {
        int hits = 0;
        for (String word : words) {
            if (text.contains(word)) {
                hits++;
            }
        }
        return hits;
    }

    /**
     * 分档:阈值固定为 ±0.2 的展示标签,
     * 自动转接用的是配置阈值 {@code agent.callcenter.negative-score-threshold},
     * 两者用途不同,不要互相替代。
     */
    static String labelOf(double score) {
        if (score <= -0.2) {
            return Labels.NEGATIVE;
        }
        return score >= 0.2 ? Labels.POSITIVE : Labels.NEUTRAL;
    }
}
