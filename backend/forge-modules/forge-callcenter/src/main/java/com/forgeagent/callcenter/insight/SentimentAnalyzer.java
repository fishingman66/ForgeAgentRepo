package com.forgeagent.callcenter.insight;

/**
 * 情绪分析 SPI(C4):输入一段脱敏后的 caller 文本,输出标签与分值。
 *
 * <p>实现两态:{@link LexiconSentimentAnalyzer}(词典法,零外部依赖,C4 默认)与
 * 后续 LLM 实现(与 C5 双引擎共用模型客户端)。分值与标签的语义固定:
 * score ∈ [-1,1],负值偏负面;标签取值见 {@link Labels}。</p>
 *
 * <p>调用方(InsightPipeline)只用 score 做阈值判定,标签随结果落库做展示维度。</p>
 */
public interface SentimentAnalyzer {

    /** 引擎标识,落 payload.engine 便于口径追溯(如 lexicon/dashscope)。 */
    String code();

    /** 分析一段文本;空文本由调用方短路,实现可假定 text 非空。 */
    Result analyze(String text);

    /** 分析结果:标签 + 分值。 */
    record Result(String label, double score) {
    }

    /** 标签常量(不用枚举:后续 LLM 侧可扩 ANXIOUS/ANGRY 等细分而不改落库口径)。 */
    final class Labels {
        public static final String NEGATIVE = "NEGATIVE";
        public static final String NEUTRAL = "NEUTRAL";
        public static final String POSITIVE = "POSITIVE";

        private Labels() {
        }
    }
}
