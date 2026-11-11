package com.forgeagent.agent.domain.bo;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 工作流试运行业务对象
 *
 * @author ForgeAgent
 */
@Data
public class WorkflowTrialRunBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * Agent ID
     */
    @NotNull(message = "Agent ID不能为空")
    private Long agentId;

    /**
     * 工作流图 JSON
     */
    @NotBlank(message = "工作流图不能为空")
    private String workflowJson;

    /**
     * 试运行输入变量 JSON
     */
    private String inputs;

    /**
     * W8:空跑模式(LLM/工具/知识库等外部调用节点返回桩输出,不烧 Token)
     */
    private Boolean dryRun;

    /**
     * W8:从指定节点起跑(其上游节点视为已完成),空为从开始节点全图执行
     */
    private String startNodeKey;

    /**
     * W8:图级步数上限(默认 100,上限 500,防条件环失控)
     */
    private Integer maxSteps;
}
