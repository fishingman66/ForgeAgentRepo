package com.forgeagent.agent.service.runner;

import com.forgeagent.agent.config.AgentChatProperties;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * L1 工具输出压缩装饰器契约单测(P3 / M1)。
 *
 * <p>重点锁定 AgentScope 2.0.4 的一个易踩细节:ToolResultBlock.text() 的默认状态是
 * RUNNING 而非 SUCCESS;若按 SUCCESS 判定,正常工具结果永远不会被压缩。</p>
 */
@Tag("dev")
class WorkingMemoryGuardTest {

    /** 不连 Redis 的桩:固定返回可识别指纹 */
    private static final class StubMemory extends RunWorkingMemory {
        StubMemory() {
            super(new AgentChatProperties());
        }

        @Override
        public String store(String runId, String toolName, String text) {
            return "wm-1-1a2b3c4d";
        }

        @Override
        public String recall(String runId, String fingerprint, int offset, int limit) {
            return "stub";
        }
    }

    private record StubTool(String result, boolean error) implements AgentTool {
        @Override
        public String getName() {
            return "query_orders";
        }

        @Override
        public String getDescription() {
            return "测试工具";
        }

        @Override
        public Map<String, Object> getParameters() {
            return Map.of("type", "object", "properties", Map.of());
        }

        @Override
        public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
            return Mono.just(error ? ToolResultBlock.error(result) : ToolResultBlock.text(result));
        }
    }

    private static String textOf(ToolResultBlock block) {
        return ((TextBlock) block.getOutput().getFirst()).getText();
    }

    @Test
    void runningStateTextResultIsCompressed() {
        WorkingMemoryGuard guard = new WorkingMemoryGuard(
            new StubTool("内容".repeat(2000), false), new StubMemory(), "run-1", 800, 200);
        ToolResultBlock block = guard.callAsync(null).block();
        assertEquals(ToolResultState.RUNNING, block.getState(), "保留内核默认状态,不改写为 SUCCESS");
        assertTrue(textOf(block).contains("[工作记忆]"));
        assertTrue(textOf(block).contains("wm-1-1a2b3c4d"));
        assertTrue(textOf(block).contains("recall_working_memory"));
    }

    @Test
    void shortResultPassesThrough() {
        String shortText = "当前北京时间:2026-11-07";
        WorkingMemoryGuard guard = new WorkingMemoryGuard(
            new StubTool(shortText, false), new StubMemory(), "run-1", 800, 200);
        assertEquals(shortText, textOf(guard.callAsync(null).block()));
    }

    @Test
    void errorResultPassesThrough() {
        WorkingMemoryGuard guard = new WorkingMemoryGuard(
            new StubTool("错误".repeat(2000), true), new StubMemory(), "run-1", 800, 200);
        ToolResultBlock block = guard.callAsync(null).block();
        assertEquals(ToolResultState.ERROR, block.getState());
        assertTrue(textOf(block).contains("错误"), "错误结果原样透传");
        assertTrue(!textOf(block).contains("[工作记忆]"), "错误结果不得被压缩");
    }
}
