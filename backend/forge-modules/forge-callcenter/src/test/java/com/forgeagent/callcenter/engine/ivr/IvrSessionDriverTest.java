package com.forgeagent.callcenter.engine.ivr;

import com.forgeagent.callcenter.config.CallcenterProperties;
import com.forgeagent.callcenter.engine.LiveChannelRegistry;
import com.forgeagent.callcenter.engine.MockAsrEngine;
import com.forgeagent.callcenter.engine.MockChannelControl;
import com.forgeagent.callcenter.engine.MockMediaStreamProvider;
import com.forgeagent.callcenter.engine.MockTtsEngine;
import com.forgeagent.callcenter.engine.NoInterruptService;
import com.forgeagent.callcenter.engine.VoiceEngineRegistry;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * IvrSessionDriver 单测(P3):callerChannel 内部装配后,DTMF 经 offerDtmf 投递、
 * 会话结束活跃表归零。真实 Mock 引擎跑「say → received(#) → hangup」迷你流程。
 */
@Tag("dev")
class IvrSessionDriverTest {

    /** 迷你流程:say(欢迎) → received(endKey=#) → hangup。 */
    private static final String FLOW = """
        [
          {"id":"s","type":"start","nextId":"say1"},
          {"id":"say1","type":"say","text":"您好","nextId":"recv"},
          {"id":"recv","type":"received","endKey":"#","maxDigits":3,"timeoutMs":3000,"nextId":"done"},
          {"id":"done","type":"hangup","cause":"NORMAL_CLEARING"}
        ]
        """;

    private IvrSessionDriver driver(java.util.Map<String, IvrNode> nodes) {
        IvrFlowService flowService = mock(IvrFlowService.class);
        Mockito.when(flowService.load(Mockito.any(), Mockito.anyString())).thenReturn(nodes);
        MockAsrEngine asr = new MockAsrEngine();
        MockTtsEngine tts = new MockTtsEngine().setAutoPump(true);
        VoiceEngineRegistry registry = new VoiceEngineRegistry(List.of(asr), List.of(tts));
        CascadeEngineFactory factory = new CascadeEngineFactory(
            registry, new NoInterruptService(new CallcenterProperties(), null));
        // mini 流程只需这四个无状态执行器(Intention/Agent 需要分类器依赖,不在本测试范围)
        List<NodeExecutor> executors = List.of(new IvrExecutors.StartExecutor(),
            new IvrExecutors.SayExecutor(), new IvrExecutors.ReceivedExecutor(),
            new IvrExecutors.HangupExecutor());
        return new IvrSessionDriver(flowService, new NodeExecutorRegistry(executors), factory,
            new MockMediaStreamProvider(), new CallcenterProperties(), new LiveChannelRegistry());
    }

    @Test
    void dtmfDeliveredToActiveSessionAndClearedAfterEnd() throws Exception {
        java.util.Map<String, IvrNode> nodes = new IvrFlowService(null).deserialize(FLOW);
        IvrSessionDriver driver = driver(nodes);
        AtomicBoolean hangup = new AtomicBoolean(false);
        MockChannelControl channel = new MockChannelControl("CALL-DTMF", 0);
        MockMediaStreamProvider streamProvider = new MockMediaStreamProvider();
        Thread t = new Thread(() -> driver.drive(1L, "demo", "CALL-DTMF", "13800000000", "1001",
            streamProvider.open("CALL-DTMF"), channel, hangup));
        t.start();

        // 会话起来后投按键:# 结束收号 → hangup 节点 → 循环终止,活跃表清空
        await().atMost(java.time.Duration.ofSeconds(3)).until(() -> driver.activeCount() == 1);
        assertTrue(driver.offerDtmf("CALL-DTMF", "1"));
        assertTrue(driver.offerDtmf("CALL-DTMF", "#"));

        await().atMost(java.time.Duration.ofSeconds(3)).until(() -> driver.activeCount() == 0);
        assertFalse(driver.offerDtmf("CALL-DTMF", "9"), "会话结束后投递应落空");
        assertTrue(channel.commands().stream().anyMatch(c -> "hangup".equals(c.op())),
            "流程应走到 hangup 节点下发挂机命令");
        t.join(2000);
    }

    @Test
    void offerDtmfToUnknownCallReturnsFalse() {
        IvrSessionDriver driver = driver(java.util.Map.of());
        assertFalse(driver.offerDtmf("NOT-EXIST", "1"));
        assertEquals(0, driver.activeCount());
    }
}
