package com.forgeagent.agent.domain.bo;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * Agent 调试草稿(UX-01):配置页未保存的表单值,覆盖到调试会话执行
 *
 * <p>仅覆盖与对话行为相关的字段;空间归属/预算/输出 schema 等仍取 DB 中已存在的 agent_info,
 * 调试会话 token 消耗按正常路径计入用户配额(评审决策 #3)
 *
 * @author ForgeAgent
 */
@Data
public class AgentDraftBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 关联的正式 AgentID(必填):用于归属校验、配额统计、会话归属
     */
    @NotNull(message = "agentId 不能为空")
    private Long agentId;

    /**
     * 系统提示词(覆盖)
     */
    private String systemPrompt;

    /**
     * 模型ID(覆盖)
     */
    private Long modelId;

    /**
     * 工具ID集合(逗号分隔,覆盖)
     */
    private String toolIdsStr;

    /**
     * 知识库ID集合(逗号分隔,覆盖)
     */
    private String knowledgeIdsStr;

    /**
     * MCP ID集合(逗号分隔,覆盖)
     */
    private String mcpIdsStr;
}
