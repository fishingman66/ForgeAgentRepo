package com.forgeagent.agentcore.domain;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import com.forgeagent.common.mybatis.core.domain.BaseEntity;

import java.io.Serial;
import java.math.BigDecimal;

/**
 * Agent 定义对象 agent_info
 *
 * @author ForgeAgent
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("agent_info")
public class AgentInfo extends BaseEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 主键
     */
    @TableId(value = "id")
    private Long id;

    /**
     * 所属工作空间ID
     */
    private Long workspaceId;

    /**
     * Agent名称(空间内唯一)
     */
    private String name;

    /**
     * Agent描述
     */
    private String description;

    /**
     * 系统提示词
     */
    private String systemPrompt;

    /**
     * 欢迎语
     */
    private String welcomeMsg;

    /**
     * 推荐问题(JSON 数组,会话空态展示为可点击快捷提问)
     */
    private String suggestedQuestions;

    /**
     * 绑定模型配置ID
     */
    private Long modelId;

    /**
     * 采样温度(0-2)
     */
    private BigDecimal temperature;

    /**
     * 最大输出tokens
     */
    private Integer maxTokens;

    /**
     * Token累计预算(总tokens上限,空为不限,超限后中断执行)
     */
    private Long tokenBudget;

    /**
     * 结构化输出JSON Schema(空为关闭,配置后回复为受约束 JSON)
     */
    private String outputSchema;

    /**
     * 团队协作模式(none 不启用 / sequential 顺序接力)
     */
    private String teamMode;

    /**
     * 协作成员AgentID列表(逗号分隔)
     */
    @TableField("team_ids")
    private String teamIdsStr;

    /**
     * 长期记忆开关(0关1开)
     */
    @TableField("memory_enabled")
    private String memoryEnabled;

    /**
     * 绑定工具ID列表(逗号分隔)
     */
    @TableField("tool_ids")
    private String toolIdsStr;

    /**
     * 绑定知识库ID列表(逗号分隔)
     */
    @TableField("knowledge_ids")
    private String knowledgeIdsStr;

    /**
     * 绑定MCP服务器ID列表(逗号分隔)
     */
    @TableField("mcp_ids")
    private String mcpIdsStr;

    /**
     * 工作流编排JSON(画布,预留)
     */
    private String workflowJson;

    /**
     * 工作流触发模式(P2 / W10):0 关闭(对话走 ReAct) / 1 每轮对话自动执行工作流 / 2 按意图路由
     */
    private String workflowTriggerMode;

    /**
     * 状态(0草稿 1已发布 2已停用)
     */
    private String status;

    /**
     * 配置版本号(每次编辑递增)
     */
    private Integer version;

    /**
     * 当前生效的发布版本号
     */
    private Integer publishedVersion;

    /**
     * 发布配置 SHA-256
     */
    private String configHash;

    /**
     * 绑定 Prompt 模板ID(P1 / B2)
     */
    private Long promptTemplateId;

    /**
     * Prompt 绑定模式 MANUAL / LOCKED / FOLLOW_STABLE
     */
    private String promptBindingMode;

    /**
     * LOCKED 模式锁定的模板版本ID
     */
    private Long promptVersionId;

    /**
     * 模板变量值 JSON
     */
    private String promptVariables;

    /**
     * 删除标志(0存在 2删除)
     */
    @TableLogic
    private Long delFlag;

}
