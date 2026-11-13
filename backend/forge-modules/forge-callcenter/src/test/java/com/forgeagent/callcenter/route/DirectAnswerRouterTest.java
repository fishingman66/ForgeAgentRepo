package com.forgeagent.callcenter.route;

import com.forgeagent.callcenter.domain.CsCall;
import com.forgeagent.callcenter.domain.CsCallSegment;
import com.forgeagent.callcenter.engine.LiveChannelRegistry;
import com.forgeagent.callcenter.engine.MockChannelControl;
import com.forgeagent.callcenter.insight.ChannelVoiceSettings;
import com.forgeagent.callcenter.insight.InsightPipeline;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** C5 第二消费点:无 IVR 直接应答的分流与动作执行口径(有无媒体面分开验证)。 */
@Tag("dev")
class DirectAnswerRouterTest {

    private static final String CALL_UUID = "FS-CALL-1";

    private HybridIntentionClassifier classifier;
    private ChannelVoiceSettings channelSettings;
    private LiveChannelRegistry liveChannels;
    private InsightPipeline insightPipeline;
    private DirectAnswerRouter router;
    /** 固定实例:verify 用 eq() 比较引用,不能每次新建。 */
    private CsCall call;

    @BeforeEach
    void setUp() {
        call = new CsCall();
        call.setId(9L);
        call.setCallId(CALL_UUID);
        call.setWorkspaceId(1L);
        call.setChannelCode("FS_MOCK_CH1");
        classifier = mock(HybridIntentionClassifier.class);
        channelSettings = mock(ChannelVoiceSettings.class);
        liveChannels = new LiveChannelRegistry();
        insightPipeline = mock(InsightPipeline.class);
        router = new DirectAnswerRouter(classifier, channelSettings, liveChannels, insightPipeline);
    }

    @Test
    void channelWithoutDirectAnswerDoesNothing() {
        when(channelSettings.of(anyString()))
            .thenReturn(new ChannelVoiceSettings.Settings(List.of(), null, false));

        router.onSegment(call, segment(101L, "caller", "1", "我要退货"));

        verify(classifier, never()).route(anyLong(), anyString(), anyList());
        verify(insightPipeline, never()).onRouteDecision(any(), anyString(), anyLong(), anyString(), anyString());
    }

    @Test
    void transferFiresOnLiveChannel() {
        when(channelSettings.of(anyString())).thenReturn(directAnswer());
        when(classifier.route(anyLong(), anyString(), anyList())).thenReturn(
            new HybridIntentionClassifier.Decision("refund", "TRANSFER", "after_sales",
                "RULE", "refund", 1.0));
        MockChannelControl channel = new MockChannelControl(CALL_UUID, 0);
        liveChannels.register(CALL_UUID, channel);

        router.onSegment(call, segment(101L, "caller", "1", "我要退货"));

        assertEquals(1, channel.commandsOf("transfer").size());
        assertEquals("after_sales", channel.commandsOf("transfer").get(0).arg());
        verify(insightPipeline).onRouteDecision(eq(call), eq("direct-101"), eq(101L),
            eq("refund"), contains("\"outcome\":\"FIRED\""));
    }

    @Test
    void transferWithoutLiveChannelOnlyRecordsDecision() {
        when(channelSettings.of(anyString())).thenReturn(directAnswer());
        when(classifier.route(anyLong(), anyString(), anyList())).thenReturn(
            new HybridIntentionClassifier.Decision("refund", "TRANSFER", "after_sales",
                "RULE", "refund", 1.0));

        router.onSegment(call, segment(101L, "caller", "1", "我要退货"));

        verify(insightPipeline).onRouteDecision(any(), eq("direct-101"), eq(101L), eq("refund"),
            contains("PENDING_NO_CHANNEL"));
    }

    @Test
    void answerActionWaitsForMediaPlane() {
        when(channelSettings.of(anyString())).thenReturn(directAnswer());
        when(classifier.route(anyLong(), anyString(), anyList())).thenReturn(
            new HybridIntentionClassifier.Decision("query_order", "ANSWER", "order_status",
                "RULE", "order_query", 1.0));

        router.onSegment(call, segment(101L, "caller", "1", "查下订单"));

        verify(insightPipeline).onRouteDecision(any(), eq("direct-101"), eq(101L), eq("query_order"),
            contains("ANSWER_PENDING"));
    }

    @Test
    void botSegmentAndIntermediateSegmentsAreIgnored() {
        when(channelSettings.of(anyString())).thenReturn(directAnswer());

        router.onSegment(call, segment(101L, "bot", "1", "您好"));
        router.onSegment(call, segment(102L, "caller", "0", "我要退货"));

        verify(classifier, never()).route(anyLong(), anyString(), anyList());
    }

    @Test
    void noDecisionRecordsNothing() {
        when(channelSettings.of(anyString())).thenReturn(directAnswer());
        when(classifier.route(anyLong(), anyString(), anyList())).thenReturn(null);

        router.onSegment(call, segment(101L, "caller", "1", "嗯嗯"));

        verify(insightPipeline, never()).onRouteDecision(any(), anyString(), anyLong(), anyString(), anyString());
    }

    @Test
    void classifierFailureIsSwallowed() {
        when(channelSettings.of(anyString())).thenReturn(directAnswer());
        when(classifier.route(anyLong(), anyString(), anyList()))
            .thenThrow(new IllegalStateException("引擎炸了"));

        router.onSegment(call, segment(101L, "caller", "1", "我要退货"));

        verify(insightPipeline, never()).onRouteDecision(any(), anyString(), anyLong(), anyString(), anyString());
    }

    private static ChannelVoiceSettings.Settings directAnswer() {
        return new ChannelVoiceSettings.Settings(List.of(), null, true);
    }

    private static CsCallSegment segment(Long id, String speaker, String finalFlag, String text) {
        CsCallSegment segment = new CsCallSegment();
        segment.setId(id);
        segment.setCallId(9L);
        segment.setSpeaker(speaker);
        segment.setIsFinal(finalFlag);
        segment.setText(text);
        return segment;
    }
}
