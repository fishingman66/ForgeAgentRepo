package com.forgeagent.agent.service.runner;

import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.Map;

/**
 * Docker 沙箱代码执行工具
 *
 * <p>把 agent_tool 注册表中的 sandbox 类型工具包装为内核 AgentTool:
 * 真实执行委托给 {@link SandboxCodeExecutor}(一次性隔离容器,无网络、
 * 内存/CPU/进程数限额、执行超时、输出截断)。</p>
 *
 * @author ForgeAgent
 */
record DockerSandboxTool(Config def) implements AgentTool {

    /**
     * 沙箱运行配置(装配快照)
     */
    record Config(String name, String description, String image,
                  Integer timeoutSeconds, String memory, String cpus) {
    }

    /**
     * 公共沙箱执行器(无状态,工具为 record,以静态实例持有)
     */
    private static final SandboxCodeExecutor EXECUTOR = new SandboxCodeExecutor();

    @Override
    public String getName() {
        return def.name();
    }

    @Override
    public String getDescription() {
        return def.description() == null ? "在隔离 Docker 沙箱中执行 Python 代码" : def.description();
    }

    @Override
    public Map<String, Object> getParameters() {
        Map<String, Object> code = Map.of(
            "type", "string",
            "description", "要执行的 Python 3 源代码(将写入 main.py 后运行)");
        Map<String, Object> schema = new java.util.LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", Map.of("code", code));
        schema.put("required", java.util.List.of("code"));
        return schema;
    }

    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        Map<String, Object> input = param.getInput() == null ? Map.of() : param.getInput();
        return Mono.fromCallable(() -> execute(input)).subscribeOn(Schedulers.boundedElastic());
    }

    private ToolResultBlock execute(Map<String, Object> input) {
        Object codeObj = input.get("code");
        String code = codeObj == null ? null : String.valueOf(codeObj);
        String output = EXECUTOR.execute(new SandboxCodeExecutor.SandboxConfig(
            code, def.image(), def.timeoutSeconds(), def.memory(), def.cpus()));
        return ToolResultBlock.text(output);
    }
}
