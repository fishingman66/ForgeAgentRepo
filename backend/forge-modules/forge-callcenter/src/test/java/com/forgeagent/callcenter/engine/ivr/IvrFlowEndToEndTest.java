package com.forgeagent.callcenter.engine.ivr;

import com.forgeagent.callcenter.engine.CascadeTurnEngine;
import com.forgeagent.callcenter.engine.MockAsrEngine;
import com.forgeagent.callcenter.engine.MockChannelControl;
import com.forgeagent.callcenter.engine.MockMediaStreamProvider;
import com.forgeagent.callcenter.engine.MockTtsEngine;
import com.forgeagent.callcenter.engine.NoInterruptMatcher;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * C3 端到端流程回放测试(方案验证口径「流程 JSON 回放」):
 * Mock 全家桶 + 真实 runner + 真实执行器,完整走通
 * say(SpEL 渲染) → answer 收音 → intention 分流 → agent 问答 → transfer 的整条支线,
 * 以及静默超时 → otherNextId 支线。
 */
@Tag("dev")
class IvrFlowEndToEndTest {

    private static final String DEMO_FLOW = """
        [
          {"id":"s","type":"start","nextId":"n1"},
          {"id":"n1","type":"say","text":"您好,${caller},请问有什么可以帮您","nextId":"n2"},
          {"id":"n2","type":"answer","prompt":"","timeoutMs":8000,"nextId":"n3","silenceNextId":"n1"},
          {"id":"n3","type":"intention","branches":{"refund":"n4"},"otherNextId":"n6"},
          {"id":"n4","type":"agent","noAnswerNextId":"n6","nextId":"n6b"},
          {"id":"n6","type":"say","text":"暂未找到答案,转接人工","nextId":"n5"},
          {"id":"n6b","type":"say","text":"好的,关于退货问题,为您说明如下","nextId":"n5"},
          {"id":"n5","type":"transfer","queue":"after_sales"}
        ]""";

    /** 一条会话的统一组件束:turnEngine 全链路唯一实例。 */
    private record Rig(MockMediaStreamProvider.MockMediaStream stream,
                       MockChannelControl channel,
                       CascadeTurnEngine turnEngine,
                       CascadeEngineFactory.CallerChannel callerChannel,
                       MockAsrEngine asr,
                       MockTtsEngine tts,
                       NodeExecutorRegistry registry) {
    }

    private Rig rig(MockAsrEngine asr) {
        MockMediaStreamProvider provider = new MockMediaStreamProvider();
        MockMediaStreamProvider.MockMediaStream stream = provider.open("FS-CALL-E2E");
        MockChannelControl channel = new MockChannelControl("FS-CALL-E2E", 0);
        MockTtsEngine tts = new MockTtsEngine("mock");
        tts.setAutoPump(true);
        CascadeTurnEngine turnEngine = new CascadeTurnEngine(stream, channel, asr, tts,
            () -> new NoInterruptMatcher(List.of("嗯", "好的"), List.of()));
        CascadeEngineFactory.CallerChannel callerChannel = new CascadeEngineFactory.CallerChannel(turnEngine);
        NodeExecutorRegistry registry = new NodeExecutorRegistry(List.of(
            new IvrExecutors.StartExecutor(),
            new IvrExecutors.SayExecutor(),
            new IvrExecutors.AnswerExecutor(),
            new IvrExecutors.ReceivedExecutor(),
            new IvrExecutors.IntentionExecutor(() -> (workspaceId, text, recentTexts) -> {
                // 无 DB 桩分类器:按关键词路由,与 CsIntentService 解耦
                if (text != null && text.contains("退货")) {
                    return new IntentionClassifier.Result("refund", 1.0);
                }
                return null;
            }),
            new IvrExecutors.AgentExecutor(() -> (workspaceId, question) ->
                question != null && question.contains("退货") ? "支持七天无理由退货" : null),
            new IvrExecutors.ConditionExecutor(),
            new IvrExecutors.TransferExecutor(),
            new IvrExecutors.HangupExecutor()));
        return new Rig(stream, channel, turnEngine, callerChannel, asr, tts, registry);
    }

    private NodeContext ctx(Rig rig, Map<String, IvrNode> nodes) {
        Map<String, Object> vars = new HashMap<>();
        vars.put("callId", "FS-CALL-E2E");
        vars.put("caller", "138****5678");
        vars.put("workspaceId", 1L);
        vars.put("turnEngine", rig.turnEngine());
        vars.put("callerChannel", rig.callerChannel());
        vars.put("channel", rig.channel());
        vars.put("executorRegistry", rig.registry());
        return new NodeContext(vars);
    }

    @Test
    void refundBranchEndToEnd() throws Exception {
        MockAsrEngine asr = new MockAsrEngine("mock").addScript(1600, "我要退货", 0.95);
        Rig rig = rig(asr);
        Map<String, IvrNode> nodes = new IvrFlowService(null).deserialize(DEMO_FLOW);

        AtomicBoolean hangup = new AtomicBoolean(false);
        AtomicReference<Throwable> driverError = new AtomicReference<>();
        Thread driver = new Thread(() -> {
            try {
                new IvrFlowRunner().run(nodes, "s", ctx(rig, nodes), hangup);
            } catch (Throwable t) {
                driverError.set(t);
            }
        });
        driver.setUncaughtExceptionHandler((t, e) -> driverError.set(e));
        driver.start();
        Thread.sleep(120);

        // 1) say 播报(SpEL 渲染 caller)进入播放队列
        assertTrue(rig.stream().isBotPlaying(), "say 节点话术已进入播放队列");
        // 2) answer 收音注入终稿(1600 字节达脚本阈值)
        rig.stream().injectCallerAudio(new byte[1700]);
        // 3) intention → refund → agent 命中 FAQ 播报 → transfer
        driver.join(3000);
        if (driverError.get() != null) {
            throw new AssertionError("driver 线程异常", driverError.get());
        }

        assertTrue(rig.tts().synthesizedTexts().stream().anyMatch(t -> t.contains("138****5678")),
            "say 节点 SpEL 渲染了 caller 变量: " + rig.tts().synthesizedTexts());
        assertTrue(rig.tts().synthesizedTexts().stream().anyMatch(t -> t.contains("七天无理由退货")),
            "agent 节点命中 FAQ 并播报答案: " + rig.tts().synthesizedTexts());
        assertTrue(rig.channel().commandsOf("transfer").stream()
                .anyMatch(c -> "after_sales".equals(c.arg())),
            "transfer 节点发出转人工命令 | state=" + driver.getState()
                + " cmds=" + rig.channel().commands()
                + " tts=" + rig.tts().synthesizedTexts());
        assertTrue(driver.getState() == Thread.State.TERMINATED, "transfer 后循环终止");
    }

    @Test
    void silenceBranchRoutesToOtherThenTransfer() throws Exception {
        String flow = """
            [
              {"id":"s","type":"start","nextId":"n1"},
              {"id":"n1","type":"answer","prompt":"请讲","timeoutMs":400,"nextId":"n2","silenceNextId":"n2"},
              {"id":"n2","type":"intention","branches":{},"otherNextId":"n3"},
              {"id":"n3","type":"say","text":"未识别,转人工","nextId":"n4"},
              {"id":"n4","type":"transfer","queue":"after_sales"}
            ]""";
        Rig rig = rig(new MockAsrEngine("mock"));  // 无脚本 → 超时静默
        Map<String, IvrNode> nodes = new IvrFlowService(null).deserialize(flow);

        AtomicBoolean hangup = new AtomicBoolean(false);
        Thread driver = new Thread(() -> new IvrFlowRunner().run(nodes, "s", ctx(rig, nodes), hangup));
        driver.start();
        driver.join(5000);

        assertTrue(rig.tts().synthesizedTexts().stream().anyMatch(t -> t.contains("请讲")),
            "answer 的 prompt 先播报");
        assertTrue(rig.tts().synthesizedTexts().stream().anyMatch(t -> t.contains("未识别")),
            "静默分支走 otherNextId");
        assertTrue(rig.channel().commandsOf("transfer").size() >= 1, "最终转人工");
        assertTrue(driver.getState() == Thread.State.TERMINATED, "transfer 后循环终止");
    }
}
