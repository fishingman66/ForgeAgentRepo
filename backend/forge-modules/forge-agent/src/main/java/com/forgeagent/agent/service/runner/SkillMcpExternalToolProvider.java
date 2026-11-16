package com.forgeagent.agent.service.runner;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.forgeagent.agent.domain.AgentSkill;
import com.forgeagent.agent.mapper.AgentSkillMapper;
import com.forgeagent.common.core.utils.StringUtils;
import com.forgeagent.rag.mcp.McpExternalToolProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

/**
 * 08方案 / A5:技能对外 MCP 工具(skill_list / skill_get)。
 *
 * <p>让外部 Agent(DSH、Claude Code 等)经平台 /mcp 端点读取声明式技能与
 * 操作手册——知识检索之外的第二个对外能力面。只读、仅正常状态技能;
 * instructions 为大字段,list 时不查(get 时按名称单条查),避免列表响应撑爆。</p>
 *
 * @author ForgeAgent
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SkillMcpExternalToolProvider implements McpExternalToolProvider {

    private static final String TOOL_LIST = "skill_list";
    private static final String TOOL_GET = "skill_get";

    private final AgentSkillMapper skillMapper;

    @Override
    public Set<String> supportedTools() {
        return Set.of(TOOL_LIST, TOOL_GET);
    }

    /**
     * 返回两个工具的定义(一个 Provider 覆盖 skill_list + skill_get)
     */
    @Override
    public List<JsonNode> definitions(ObjectMapper om) {
        ObjectNode listTool = om.createObjectNode();
        listTool.put("name", TOOL_LIST);
        listTool.put("description", "列出平台可用的声明式技能(名称/摘要/版本),"
            + "用于决定是否需要用 skill_get 获取某技能的完整操作手册");
        ObjectNode listSchema = listTool.putObject("inputSchema");
        listSchema.put("type", "object");

        ObjectNode getTool = om.createObjectNode();
        getTool.put("name", TOOL_GET);
        getTool.put("description", "按名称获取技能的完整操作手册(Markdown),"
            + "手册含操作步骤与该技能可使用的工具说明");
        ObjectNode getSchema = getTool.putObject("inputSchema");
        ObjectNode getProps = getSchema.putObject("properties");
        getProps.putObject("name").put("type", "string").put("description", "技能名称,取自 skill_list");
        getSchema.putArray("required").add("name");

        return List.of(listTool, getTool);
    }

    @Override
    public ObjectNode invoke(ObjectMapper om, String toolName, JsonNode args) {
        return switch (toolName) {
            case TOOL_LIST -> listTool(om);
            case TOOL_GET -> getTool(om, args);
            default -> throw new IllegalArgumentException("未知技能工具:" + toolName);
        };
    }

    private ObjectNode listTool(ObjectMapper om) {
        List<AgentSkill> skills = skillMapper.selectList(
            new LambdaQueryWrapper<AgentSkill>()
                .select(AgentSkill::getId, AgentSkill::getName, AgentSkill::getDescription,
                    AgentSkill::getVersion, AgentSkill::getStatus, AgentSkill::getWorkspaceId)
                .eq(AgentSkill::getStatus, "0")
                .orderByDesc(AgentSkill::getId));
        ObjectNode result = om.createObjectNode();
        ArrayNode rows = result.putArray("rows");
        for (AgentSkill skill : skills) {
            ObjectNode row = rows.addObject();
            row.put("name", skill.getName());
            row.put("description", StringUtils.defaultString(skill.getDescription()));
            row.put("version", skill.getVersion() == null ? 1 : skill.getVersion());
            row.put("scope", skill.getWorkspaceId() == null ? "shared" : "workspace");
        }
        result.put("hint", "用 skill_get(name) 获取技能的完整操作手册");
        return result;
    }

    private ObjectNode getTool(ObjectMapper om, JsonNode args) {
        String name = args.path("name").asText("");
        if (StringUtils.isBlank(name)) {
            throw new IllegalArgumentException("name 不能为空");
        }
        AgentSkill skill = skillMapper.selectOne(new LambdaQueryWrapper<AgentSkill>()
            .eq(AgentSkill::getName, name)
            .eq(AgentSkill::getStatus, "0")
            .last("limit 1"));
        if (skill == null) {
            throw new IllegalArgumentException("技能不存在或已停用:" + name);
        }
        ObjectNode result = om.createObjectNode();
        ArrayNode rows = result.putArray("rows");
        ObjectNode row = rows.addObject();
        row.put("name", skill.getName());
        row.put("version", skill.getVersion() == null ? 1 : skill.getVersion());
        row.put("instructions", StringUtils.defaultString(skill.getInstructions()));
        if (StringUtils.isNotBlank(skill.getPromptFragment())) {
            row.put("promptFragment", skill.getPromptFragment());
        }
        if (StringUtils.isNotBlank(skill.getToolIds())) {
            row.put("toolIds", skill.getToolIds());
        }
        if (StringUtils.isNotBlank(skill.getKnowledgeIds())) {
            row.put("knowledgeIds", skill.getKnowledgeIds());
        }
        return result;
    }
}
