package com.forgeagent.agent.domain.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * 运行轨迹详情视图对象(主记录 + 按序步骤)
 *
 * @author ForgeAgent
 */
@Data
public class RunTraceDetailVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private AgentRunVo run;

    private List<AgentRunStepVo> steps;
}
