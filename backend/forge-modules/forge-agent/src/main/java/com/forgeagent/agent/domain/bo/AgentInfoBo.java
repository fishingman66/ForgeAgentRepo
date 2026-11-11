package com.forgeagent.agent.domain.bo;

import io.github.linpeilie.annotations.AutoMapper;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;
import com.forgeagent.agentcore.domain.AgentInfo;
import com.forgeagent.common.core.validate.AddGroup;
import com.forgeagent.common.core.validate.EditGroup;
import com.forgeagent.common.core.validate.QueryGroup;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.List;

/**
 * Agent 定义业务对象
 *
 * @author ForgeAgent
 */
@Data
@AutoMapper(target = AgentInfo.class, reverseConvertGenerate = false)
public class AgentInfoBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 主键(编辑必填)
     */
    @NotNull(message = "AgentID不能为空", groups = {EditGroup.class})
    private Long id;

    /**
     * 所属工作空间ID
     */
    @NotNull(message = "工作空间不能为空", groups = {AddGroup.class})
    private Long workspaceId;

    /**
     * Agent名称
     */
    @NotBlank(message = "Agent名称不能为空", groups = {AddGroup.class, EditGroup.class})
    @Size(min = 2, max = 50, message = "Agent名称长度须在 2-50 字符之间", groups = {AddGroup.class, EditGroup.class})
    private String name;

    /**
     * Agent描述
     */
    @Size(max = 200, message = "Agent描述不能超过 200 字符", groups = {AddGroup.class, EditGroup.class})
    private String description;

    /**
     * 系统提示词
     */
    @Size(max = 5000, message = "系统提示词不能超过 5000 字符", groups = {AddGroup.class, EditGroup.class})
    private String systemPrompt;

    /**
     * 欢迎语
     */
    @Size(max = 500, message = "欢迎语不能超过 500 字符", groups = {AddGroup.class, EditGroup.class})
    private String welcomeMsg;

    /**
     * 推荐问题(JSON 数组,会话空态展示为可点击快捷提问)
     */
    @Size(max = 2000, message = "推荐问题不能超过 2000 字符", groups = {AddGroup.class, EditGroup.class})
    private String suggestedQuestions;

    /**
     * 绑定模型配置ID
     */
    private Long modelId;

    /**
     * 采样温度(0-2)
     */
    @DecimalMin(value = "0", message = "温度不能小于 0", groups = {AddGroup.class, EditGroup.class})
    @DecimalMax(value = "2", message = "温度不能大于 2", groups = {AddGroup.class, EditGroup.class})
    private BigDecimal temperature;

    /**
     * 最大输出tokens
     */
    @Min(value = 1, message = "最大tokens须大于 0", groups = {AddGroup.class, EditGroup.class})
    @Max(value = 100000, message = "最大tokens不能超过 100000", groups = {AddGroup.class, EditGroup.class})
    private Integer maxTokens;

    /**
     * Token累计预算(总tokens上限,空为不限)
     */
    @Min(value = 1, message = "Token预算须大于 0", groups = {AddGroup.class, EditGroup.class})
    private Long tokenBudget;

    /**
     * 结构化输出JSON Schema(空为关闭;须为合法 JSON 对象)
     */
    @Size(max = 10000, message = "结构化输出Schema不能超过 10000 字符", groups = {AddGroup.class, EditGroup.class})
    private String outputSchema;

    /**
     * 团队协作模式(none 不启用 / sequential 顺序接力)
     */
    private String teamMode;

    /**
     * 协作成员AgentID列表
     */
    private List<Long> teamIds;

    /**
     * 长期记忆开关(true 开启)
     */
    private Boolean memoryEnabled;

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
     * 模板变量值 JSON 字符串
     */
    @Size(max = 10000, message = "模板变量不能超过 10000 字符", groups = {AddGroup.class, EditGroup.class})
    private String promptVariables;

    /**
     * 状态(0草稿 1已发布 2已停用,仅查询/发布用)
     */
    private String status;

    /**
     * 查询关键字(仅查询用)
     */
    @Size(max = 50, groups = {QueryGroup.class})
    private String keyword;

}
