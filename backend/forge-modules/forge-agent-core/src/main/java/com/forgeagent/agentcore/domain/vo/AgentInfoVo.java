package com.forgeagent.agentcore.domain.vo;

import io.github.linpeilie.annotations.AutoMapper;
import lombok.Data;
import com.forgeagent.agentcore.domain.AgentInfo;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Date;
import java.util.List;

/**
 * Agent 定义视图对象
 *
 * @author ForgeAgent
 */
@Data
@AutoMapper(target = AgentInfo.class)
public class AgentInfoVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 主键
     */
    private Long id;

    /**
     * 所属工作空间ID
     */
    private Long workspaceId;

    /**
     * 工作空间名称
     */
    private String workspaceName;

    /**
     * Agent名称
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
     * 绑定模型名称
     */
    private String modelName;

    /**
     * 绑定模型是否支持图片输入(1 支持 / 0 不支持,无绑定模型时为空)
     */
    private String supportVision;

    /**
     * 采样温度
     */
    private BigDecimal temperature;

    /**
     * 最大输出tokens
     */
    private Integer maxTokens;

    /**
     * Token累计预算(总tokens上限,空为不限)
     */
    private Long tokenBudget;

    /**
     * 结构化输出JSON Schema(空为关闭)
     */
    private String outputSchema;

    /**
     * 团队协作模式(none/sequential)
     */
    private String teamMode;

    /**
     * 协作成员AgentID列表
     */
    private List<Long> teamIds;

    /**
     * 长期记忆开关(0关1开)
     */
    private String memoryEnabled;

    /**
     * 绑定工具ID列表
     */
    private List<Long> toolIds;

    /**
     * 绑定知识库ID列表
     */
    private List<Long> knowledgeIds;

    /**
     * 绑定MCP服务器ID列表
     */
    private List<Long> mcpIds;

    /**
     * 工作流编排JSON
     */
    private String workflowJson;

    /**
     * 工作流触发模式(0关 1每轮自动 2按意图路由)
     */
    private String workflowTriggerMode;

    /**
     * 状态(0草稿 1已发布 2已停用)
     */
    private String status;

    /** 配置版本号 */
    private Integer version;

    /** 当前生效的发布版本号 */
    private Integer publishedVersion;

    /** 发布配置 SHA-256 */
    private String configHash;

    /** 绑定 Prompt 模板ID */
    private Long promptTemplateId;

    /** Prompt 绑定模式 MANUAL / LOCKED / FOLLOW_STABLE */
    private String promptBindingMode;

    /** LOCKED 模式锁定的模板版本ID */
    private Long promptVersionId;

    /** 模板变量值 JSON */
    private String promptVariables;

    /**
     * 创建时间
     */
    private Date createTime;

    /**
     * 更新时间
     */
    private Date updateTime;

}
