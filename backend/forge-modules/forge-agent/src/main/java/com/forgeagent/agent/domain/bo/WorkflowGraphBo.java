package com.forgeagent.agent.domain.bo;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 工作流图保存业务对象
 *
 * @author ForgeAgent
 */
@Data
public class WorkflowGraphBo implements Serializable {

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
     * 版本备注(保存时生成版本快照,为空则自动生成)
     */
    private String remark;
}
