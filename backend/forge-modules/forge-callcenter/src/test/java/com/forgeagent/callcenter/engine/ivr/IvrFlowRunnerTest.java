package com.forgeagent.callcenter.engine.ivr;

import com.forgeagent.common.core.exception.ServiceException;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 流程驱动循环单测(C3):同步 while 语义、condition 分支、步数防护、挂机退出。
 */
@Tag("dev")
class IvrFlowRunnerTest {

    private final IvrFlowRunner runner = new IvrFlowRunner();

    /** 极简执行器桩:计数并固定 nextId。 */
    private static class StubExecutor implements NodeExecutor {
        private final String type;
        private final String nextId;
        int executed;

        StubExecutor(String type, String nextId) {
            this.type = type;
            this.nextId = nextId;
        }

        @Override
        public String type() {
            return type;
        }

        @Override
        public NodeOutcome execute(NodeContext ctx, IvrNode node) {
            executed++;
            return NodeOutcome.next(nextId);
        }
    }

    private Map<String, IvrNode> index(IvrNode... nodes) {
        Map<String, IvrNode> map = new HashMap<>();
        for (IvrNode node : nodes) {
            map.put(node.getId(), node);
        }
        return map;
    }

    @Test
    void runsUntilTerminalNode() {
        IvrNode.Start s = new IvrNode.Start();
        s.setId("s");
        s.setNextId("h");
        IvrNode.Hangup h = new IvrNode.Hangup();
        h.setId("h");
        StubExecutor stub = new StubExecutor("hangup", null);
        NodeExecutorRegistry registry = new NodeExecutorRegistry(
            List.of(new StubExecutor("start", "h") {
                @Override
                public NodeOutcome execute(NodeContext ctx, IvrNode node) {
                    return NodeOutcome.next("h");
                }
            }, stub));

        Map<String, IvrNode> nodes = index(s, h);
        NodeContext ctx = new NodeContext(Map.of());
        ctx.setVar("executorRegistry", registry);
        String reason = runner.run(nodes, "s", ctx, new AtomicBoolean(false));
        assertEquals("completed", reason);
        assertEquals(1, stub.executed);
    }

    @Test
    void conditionBranchesBySpel() {
        NodeContext ctx = new NodeContext(Map.of("dtmfDigits", "1234", "intent", "other"));
        // 表达式直接引用变量名(MapAccessor 已注册,Map property 访问)
        assertTrue(ctx.evalBool("dtmfDigits.length() > 3"));
        assertFalse(ctx.evalBool("dtmfDigits.length() > 10"));
        // 不存在的变量用索引语法取 null,避免 MapAccessor 抛 property not found
        assertFalse(ctx.evalBool("['notExist'] == true"));
        assertTrue(ctx.evalBool("intent == 'other'"));
    }

    @Test
    void hangupFlagExitsAtNodeBoundary() {
        AtomicBoolean hangup = new AtomicBoolean(true);
        IvrNode.Start s = new IvrNode.Start();
        s.setId("s");
        s.setNextId("h");
        NodeExecutorRegistry registry = new NodeExecutorRegistry(
            List.of(new NodeExecutor() {
                @Override
                public String type() {
                    return "start";
                }

                @Override
                public NodeOutcome execute(NodeContext ctx, IvrNode node) {
                    return NodeOutcome.next("h");
                }
            }, new StubExecutor("hangup", null)));
        Map<String, IvrNode> nodes = index(s, new IvrNode.Hangup());
        NodeContext ctx = new NodeContext(Map.of());
        ctx.setVar("executorRegistry", registry);
        assertEquals("interrupted", runner.run(nodes, "s", ctx, hangup));
    }

    @Test
    void missingNodeAndDeadLoopRejected() {
        NodeExecutorRegistry registry = new NodeExecutorRegistry(
            List.of(new NodeExecutor() {
                @Override
                public String type() {
                    return "start";
                }

                @Override
                public NodeOutcome execute(NodeContext ctx, IvrNode node) {
                    return NodeOutcome.next("ghost");
                }
            }));
        NodeContext ctx = new NodeContext(Map.of());
        ctx.setVar("executorRegistry", registry);
        IvrNode.Start s = new IvrNode.Start();
        s.setId("s");
        ServiceException ex = assertThrows(ServiceException.class,
            () -> runner.run(index(s), "s", ctx, new AtomicBoolean(false)));
        assertTrue(ex.getMessage().contains("节点不存在"));

        // 死循环:start → start 自环,超 200 步熔断
        NodeExecutorRegistry loop = new NodeExecutorRegistry(
            List.of(new NodeExecutor() {
                @Override
                public String type() {
                    return "start";
                }

                @Override
                public NodeOutcome execute(NodeContext ctx, IvrNode node) {
                    return NodeOutcome.next("s");
                }
            }));
        NodeContext ctx2 = new NodeContext(Map.of());
        ctx2.setVar("executorRegistry", loop);
        assertThrows(ServiceException.class, () -> runner.run(index(s), "s", ctx2, new AtomicBoolean(false)));
    }

}
