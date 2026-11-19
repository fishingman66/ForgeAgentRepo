package com.forgeagent.agent.domain.bo;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 评测执行业务对象
 *
 * @author ForgeAgent
 */
@Data
public class EvalRunStartBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @NotNull(message = "datasetId 不能为空")
    private Long datasetId;

    @NotNull(message = "agentId 不能为空")
    private Long agentId;

    /** 五维 LLM 裁判开关 */
    private Boolean judgeEnabled;

    /** 测试与编排便捷构造 */
    public static EvalRunStartBo of(Long datasetId, Long agentId, boolean judgeEnabled) {
        EvalRunStartBo bo = new EvalRunStartBo();
        bo.setDatasetId(datasetId);
        bo.setAgentId(agentId);
        bo.setJudgeEnabled(judgeEnabled);
        return bo;
    }
}
