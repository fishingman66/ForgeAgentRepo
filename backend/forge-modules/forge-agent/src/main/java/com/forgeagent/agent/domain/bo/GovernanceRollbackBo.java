package com.forgeagent.agent.domain.bo;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * Agent 版本回滚业务对象
 *
 * @author ForgeAgent
 */
@Data
public class GovernanceRollbackBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @NotNull(message = "agentId 不能为空")
    private Long agentId;

    @NotNull(message = "versionNo 不能为空")
    private Integer versionNo;
}
