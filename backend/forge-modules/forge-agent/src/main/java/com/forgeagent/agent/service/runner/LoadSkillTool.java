package com.forgeagent.agent.service.runner;

import com.forgeagent.agent.domain.AgentSkill;
import com.forgeagent.agent.service.IAgentSkillService;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 技能手册按需加载工具(08方案 / A2,对标 DSH Skills 懒加载)。
 *
 * <p>技能在上下文里只占一行摘要目录(由装配层追加进 system prompt),全文手册
 * 由模型经本工具按需取用——N 个技能固定 ≈ N×40 token,替代全量工具描述膨胀。
 * 白名单 = 本 Agent 已安装且正常状态的技能;执行时实时查库,技能停用立即失效。
 * 只读平台数据、无外部请求,因此免审批门(ApprovalExempt)。</p>
 *
 * @author ForgeAgent
 */
@Slf4j
public record LoadSkillTool(IAgentSkillService skillService, Long agentId,
                            List<ToolAssembler.SkillDef> allowed) implements AgentTool, ApprovalExempt {

    @Override
    public String getName() {
        return "load_skill";
    }

    @Override
    public String getDescription() {
        return "按名称加载技能的操作手册全文。当 [可用技能] 目录中某技能与本轮任务相关时,"
            + "先调用本工具获取完整操作步骤与工具用法,再按手册执行;无关技能不要加载。";
    }

    @Override
    public Map<String, Object> getParameters() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("name", Map.of("type", "string",
            "description", "技能名称,必须取自 [可用技能] 目录中的名称原文"));
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", props);
        schema.put("required", List.of("name"));
        return schema;
    }

    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        return Mono.fromCallable(() -> load(param));
    }

    private ToolResultBlock load(ToolCallParam param) {
        String name = param.getInput() == null ? null
            : String.valueOf(param.getInput().getOrDefault("name", "")).trim();
        if (name == null || name.isEmpty()) {
            return ToolResultBlock.text("[技能] 缺少 name 参数,请从 [可用技能] 目录中选取名称原文。");
        }
        boolean inWhitelist = allowed != null && allowed.stream()
            .anyMatch(s -> name.equals(s.name()));
        if (!inWhitelist) {
            return ToolResultBlock.text("[技能] 未安装或名称无效:" + name
                + "。只能加载 [可用技能] 目录中列出的技能。");
        }
        AgentSkill skill = skillService.getInstalledByName(agentId, name);
        if (skill == null || !"0".equals(skill.getStatus())) {
            // 白名单内但已停用/被卸载:实时状态优先于装配时快照
            return ToolResultBlock.text("[技能] 该技能当前不可用(已停用或被卸载):" + name);
        }
        StringBuilder sb = new StringBuilder();
        sb.append("[技能手册 ").append(skill.getName()).append(" v")
            .append(skill.getVersion() == null ? 1 : skill.getVersion()).append("]\n");
        String manual = skill.getInstructions() == null || skill.getInstructions().isBlank()
            ? "(该技能暂无操作手册,请按摘要描述执行)"
            : skill.getInstructions().strip();
        sb.append(manual).append('\n');
        if (skill.getPromptFragment() != null && !skill.getPromptFragment().isBlank()) {
            sb.append('\n').append("[技能约束]\n").append(skill.getPromptFragment().strip()).append('\n');
        }
        return ToolResultBlock.text(sb.toString());
    }
}
