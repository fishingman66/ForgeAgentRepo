package com.forgeagent.callcenter.insight;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** C4:词典情绪分析的分值与分档(离线可复现,不依赖模型)。 */
@Tag("dev")
class LexiconSentimentAnalyzerTest {

    private final LexiconSentimentAnalyzer analyzer = new LexiconSentimentAnalyzer();

    @Test
    void negativeComplaintScoresNegative() {
        SentimentAnalyzer.Result result = analyzer.analyze("太慢了,我要投诉,一直没人管");
        assertEquals(SentimentAnalyzer.Labels.NEGATIVE, result.label());
        assertTrue(result.score() < -0.5, "多处负面词应给出较强负分,实际=" + result.score());
    }

    @Test
    void thankYouScoresPositive() {
        SentimentAnalyzer.Result result = analyzer.analyze("谢谢,解决得很好,辛苦了");
        assertEquals(SentimentAnalyzer.Labels.POSITIVE, result.label());
        assertTrue(result.score() > 0.5);
    }

    @Test
    void neutralTextScoresZero() {
        SentimentAnalyzer.Result result = analyzer.analyze("我想查一下订单状态");
        assertEquals(SentimentAnalyzer.Labels.NEUTRAL, result.label());
        assertEquals(0.0, result.score(), 1e-9);
    }

    @Test
    void mixedSentimentCancelsOut() {
        SentimentAnalyzer.Result result = analyzer.analyze("虽然很慢,但是谢谢你们帮忙");
        assertEquals(SentimentAnalyzer.Labels.NEUTRAL, result.label());
        assertEquals(0.0, result.score(), 1e-9);
    }

    @Test
    void scoreAlwaysWithinRange() {
        for (String text : new String[]{"投诉投诉投诉", "谢谢谢谢", "慢", ""}) {
            double score = analyzer.analyze(text).score();
            assertTrue(score >= -1.0 && score <= 1.0, "分值必须落在 [-1,1]: " + score);
        }
    }

    @Test
    void labelBandsAreStable() {
        assertEquals(SentimentAnalyzer.Labels.NEGATIVE, LexiconSentimentAnalyzer.labelOf(-0.21));
        assertEquals(SentimentAnalyzer.Labels.NEUTRAL, LexiconSentimentAnalyzer.labelOf(-0.19));
        assertEquals(SentimentAnalyzer.Labels.POSITIVE, LexiconSentimentAnalyzer.labelOf(0.2));
        assertEquals(LexiconSentimentAnalyzer.CODE, analyzer.code());
    }
}
