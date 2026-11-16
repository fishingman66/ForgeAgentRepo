package com.forgeagent.agent.service.runner;

import io.agentscope.core.tool.AgentTool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * sandbox 类型工具 Provider(08方案 / A3 自 ToolAssembler 抽出,行为不变)。
 *
 * @author ForgeAgent
 */
@Slf4j
@Component
public class SandboxToolProvider implements ToolProvider {

    @Override
    public String type() {
        return "sandbox";
    }

    @Override
    public List<AgentTool> provide(ToolAssembler.ToolDef def, ToolAssembler.BuildContext ctx) {
        return List.of(new DockerSandboxTool(new DockerSandboxTool.Config(
            def.name(), def.description(), def.sandboxImage(),
            def.sandboxTimeout(), def.sandboxMemory(), def.sandboxCpus())));
    }
}
