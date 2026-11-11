package com.forgeagent.agent.service.runner;

import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import reactor.core.publisher.Mono;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 当前时间工具(P0 / A4)。
 *
 * <p>模型没有时钟,涉及「今天 / 现在 / 明天」的问题必须经工具调用取实时北京时间。
 * 动态时间不写入 system prompt,保持供应商 KV Cache 前缀稳定;该工具只读、无外部请求,
 * 因此不经审批门。</p>
 *
 * @author ForgeAgent
 */
public record CurrentTimeTool() implements AgentTool {

    private static final ZoneId BEIJING = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter FORMATTER =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss E", Locale.CHINA);

    @Override
    public String getName() {
        return "get_current_time";
    }

    @Override
    public String getDescription() {
        return "获取当前准确的北京时间。回答涉及今天、现在、明天、日期换算、星期、"
            + "时间先后判断时,必须先调用本工具,不要凭推测给出日期或时刻。";
    }

    @Override
    public Map<String, Object> getParameters() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("timezone", Map.of("type", "string", "enum", List.of("Asia/Shanghai"),
            "description", "时区,固定为北京时间"));
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", props);
        schema.put("required", List.of());
        return schema;
    }

    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        return Mono.fromCallable(() -> ToolResultBlock.text(
            "当前北京时间:" + ZonedDateTime.now(BEIJING).format(FORMATTER)
                + "(UTC+8,Asia/Shanghai)"));
    }
}
