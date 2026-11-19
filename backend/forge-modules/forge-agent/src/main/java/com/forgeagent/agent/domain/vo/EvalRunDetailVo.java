package com.forgeagent.agent.domain.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * 评测任务明细视图对象(任务 + 样本结果)
 *
 * @author ForgeAgent
 */
@Data
public class EvalRunDetailVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private AgentEvalRunVo run;

    private List<AgentEvalCaseResultVo> results;
}
