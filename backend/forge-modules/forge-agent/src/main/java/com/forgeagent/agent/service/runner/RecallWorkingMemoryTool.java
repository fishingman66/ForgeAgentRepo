package com.forgeagent.agent.service.runner;

import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * L1 工作记忆取回工具(P3 / M1)。
 *
 * <p>工具结果被 {@link WorkingMemoryGuard} 压缩后,模型凭占位文本里的指纹按需取回原文,
 * 支持 offset / limit 分段读取,避免一次把大结果又塞回上下文。只读,不经审批门。</p>
 *
 * @author ForgeAgent
 */
public record RecallWorkingMemoryTool(RunWorkingMemory workingMemory, String runId, int defaultLimit)
    implements AgentTool {

    @Override
    public String getName() {
        return "recall_working_memory";
    }

    @Override
    public String getDescription() {
        return "取回本轮对话中被压缩保存的工具原始输出。仅当工具结果里出现 [工作记忆] 与指纹时调用,"
            + "把指纹原样传入 fingerprint;内容较长时用 offset / limit 分段读取,不要一次取全文。";
    }

    @Override
    public Map<String, Object> getParameters() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("fingerprint", Map.of("type", "string",
            "description", "工具结果中给出的工作记忆指纹,形如 wm-1-1a2b3c4d"));
        props.put("offset", Map.of("type", "integer",
            "description", "从原文第几个字符开始读取,默认 0"));
        props.put("limit", Map.of("type", "integer",
            "description", "本次读取的最大字符数,默认 4000,由平台限制上限"));
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", props);
        schema.put("required", List.of("fingerprint"));
        return schema;
    }

    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        return Mono.fromCallable(() -> {
            Map<String, Object> input = param == null || param.getInput() == null
                ? Map.of() : param.getInput();
            Object fp = input.get("fingerprint");
            int offset = asInt(input.get("offset"), 0);
            int limit = asInt(input.get("limit"), defaultLimit);
            return ToolResultBlock.text(
                workingMemory.recall(runId, fp == null ? null : String.valueOf(fp), offset, limit));
        });
    }

    private static int asInt(Object value, int fallback) {
        if (value instanceof Number n) {
            return n.intValue();
        }
        if (value instanceof String s && !s.isBlank()) {
            try {
                return Integer.parseInt(s.trim());
            } catch (NumberFormatException ignore) {
                return fallback;
            }
        }
        return fallback;
    }
}
