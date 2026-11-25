package com.forgeagent.callcenter.service;

import com.forgeagent.callcenter.config.CallcenterProperties;
import com.forgeagent.callcenter.domain.CsCall;
import com.forgeagent.callcenter.domain.CsOverflowConfig;
import com.forgeagent.callcenter.engine.ChannelControl;
import com.forgeagent.callcenter.engine.LiveChannelRegistry;
import com.forgeagent.callcenter.mapper.CsOverflowConfigMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 溢出评估与执行契约测试(P0-3):三类触发条件、动作降级、默认零回退。
 */
@Tag("dev")
class OverflowEvaluatorTest {

    private CsOverflowConfigMapper mapper;
    private CallcenterProperties properties;
    private LiveChannelRegistry registry;
    private OverflowEvaluator evaluator;

    @BeforeEach
    void setUp() {
        mapper = mock(CsOverflowConfigMapper.class);
        properties = new CallcenterProperties();
        registry = new LiveChannelRegistry();
        evaluator = new OverflowEvaluator(mapper, properties, registry);
    }

    private CsOverflowConfig config(String trigger, int threshold, String action, String arg) {
        CsOverflowConfig c = new CsOverflowConfig();
        c.setWorkspaceId(1L);
        c.setTriggerType(trigger);
        c.setThreshold(threshold);
        c.setAction(action);
        c.setActionArg(arg);
        c.setEnabled("0");
        return c;
    }

    // ---------------- 默认关闭 ----------------

    @Test
    void disabledByDefaultNoConfigQueryAndNoTrigger() {
        assertFalse(evaluator.enabled(), "默认关闭");
        assertTrue(evaluator.configs(1L).isEmpty());
        // evaluate 是纯判定(不看 enabled);零回退由 configs() 返回空保证,入口 watcher 先查 configs
        assertTrue(evaluator.evaluate(
            List.of(config("QUEUE_SIZE", 1, "HANGUP", null)),
            new OverflowEvaluator.Metrics(99, 999, 99)).triggered(),
            "纯判定按指标触发;实际链路因 configs() 空而不会走到这里");
        verify(mapper, never()).selectList(any());
    }

    // ---------------- 三类触发条件 ----------------

    @Test
    void queueSizeTriggerAtThreshold() {
        var cfg = List.of(config("QUEUE_SIZE", 5, "MARK", null));
        assertFalse(evaluator.evaluate(cfg, new OverflowEvaluator.Metrics(4, 0, 0)).triggered());
        var d = evaluator.evaluate(cfg, new OverflowEvaluator.Metrics(5, 0, 0));
        assertTrue(d.triggered());
        assertEquals("QUEUE_SIZE", d.triggerType());
        assertEquals(5, d.observed());
        assertEquals("MARK", d.action());
    }

    @Test
    void waitTimeoutTriggerInSeconds() {
        var cfg = List.of(config("QUEUE_TIMEOUT", 60, "HANGUP", "NORMAL_CLEARING"));
        assertFalse(evaluator.evaluate(cfg, new OverflowEvaluator.Metrics(1, 59, 0)).triggered());
        var d = evaluator.evaluate(cfg, new OverflowEvaluator.Metrics(1, 61, 0));
        assertTrue(d.triggered());
        assertEquals(61, d.observed());
        assertEquals("HANGUP", d.action());
    }

    @Test
    void abandonRateTriggerInPercent() {
        var cfg = List.of(config("ABANDON_RATE", 30, "TRANSFER_QUEUE", "overflow_q"));
        assertFalse(evaluator.evaluate(cfg, new OverflowEvaluator.Metrics(0, 0, 29)).triggered());
        var d = evaluator.evaluate(cfg, new OverflowEvaluator.Metrics(0, 0, 30));
        assertTrue(d.triggered());
        assertEquals("TRANSFER_QUEUE", d.action());
        assertEquals("overflow_q", d.actionArg());
    }

    @Test
    void unknownTriggerNeverFires() {
        var cfg = List.of(config("UNKNOWN", 1, "HANGUP", null));
        assertFalse(evaluator.evaluate(cfg, new OverflowEvaluator.Metrics(999, 999, 99)).triggered(),
            "未知触发类型不触发,不伪造指标");
    }

    // ---------------- 动作降级 ----------------

    @Test
    void transferIvrDegradesToMark() {
        assertEquals("TRANSFER_QUEUE", OverflowEvaluator.normalizeAction("transfer_queue"));
        assertEquals("HANGUP", OverflowEvaluator.normalizeAction("HANGUP"));
        assertEquals("MARK", OverflowEvaluator.normalizeAction("TRANSFER_IVR"),
            "无二次下发 IVR 契约,降级 MARK");
        assertEquals("MARK", OverflowEvaluator.normalizeAction("whatever"));
        assertEquals("MARK", OverflowEvaluator.normalizeAction(null));
    }

    @Test
    void emptyTargetOrNoChannelDowngradesWithoutCommand() {
        properties.setOverflowEnabled(true);
        CsCall call = new CsCall();
        call.setId(1L);
        call.setCallId("cc-1");
        call.setWorkspaceId(1L);

        var dEmpty = new OverflowEvaluator.Decision(true, "TRANSFER_QUEUE", "", "QUEUE_SIZE", 5, 5);
        var outcome = evaluator.execute(call, dEmpty);
        assertFalse(outcome.sent());
        assertEquals("EMPTY_TARGET", outcome.downgradeReason());

        var dNoChannel = new OverflowEvaluator.Decision(true, "HANGUP", "NORMAL_CLEARING",
            "QUEUE_TIMEOUT", 60, 60);
        outcome = evaluator.execute(call, dNoChannel);
        assertFalse(outcome.sent());
        assertEquals("NO_LIVE_CHANNEL", outcome.downgradeReason());
    }

    @Test
    void transferQueueExecutesOnLiveChannel() {
        properties.setOverflowEnabled(true);
        CsCall call = new CsCall();
        call.setId(1L);
        call.setCallId("cc-2");
        call.setWorkspaceId(1L);

        ChannelControl channel = mock(ChannelControl.class);
        lenient().when(channel.uuid()).thenReturn("cc-2");
        registry.register("cc-2", channel);

        var d = new OverflowEvaluator.Decision(true, "TRANSFER_QUEUE", "overflow_q",
            "QUEUE_SIZE", 5, 5);
        var outcome = evaluator.execute(call, d);
        assertTrue(outcome.sent());
        verify(channel).transferToQueue("overflow_q");
        verify(channel, never()).hangup(any());
    }
}
