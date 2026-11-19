package com.forgeagent.agent.domain.bo;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 发布门禁豁免申请业务对象
 *
 * @author ForgeAgent
 */
@Data
public class GateWaiverRequestBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @NotNull(message = "agentId 不能为空")
    private Long agentId;

    /** 豁免理由(审批留痕) */
    private String reason;
}
