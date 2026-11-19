package com.forgeagent.agent.domain.bo;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 评测数据集创建业务对象
 *
 * @author ForgeAgent
 */
@Data
public class EvalDatasetCreateBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @NotBlank(message = "评测集名称不能为空")
    private String name;

    private String description;

    @NotNull(message = "工作空间不能为空")
    private Long workspaceId;

    /** 可空=通用评测集 */
    private Long agentId;
}
