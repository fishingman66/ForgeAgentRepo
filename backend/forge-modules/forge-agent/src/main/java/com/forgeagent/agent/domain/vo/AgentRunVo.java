package com.forgeagent.agent.domain.vo;

import io.github.linpeilie.annotations.AutoMapper;
import lombok.Data;
import com.forgeagent.agent.domain.AgentRun;

import java.io.Serial;
import java.io.Serializable;
import java.util.Date;

/**
 * 运行轨迹主记录视图对象(第 3 批 Bo/Vo 规范化)
 *
 * @author ForgeAgent
 */
@Data
@AutoMapper(target = com.forgeagent.agent.domain.AgentRun.class)
public class AgentRunVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String runId;

    private Long workspaceId;

    private Long agentId;

    private Long sessionId;

    private Long userId;

    private String modelName;

    private String runType;

    /** running/success/failed/cancelled */
    private String status;

    private Integer promptTokens;

    private Integer completionTokens;

    private Integer totalTokens;

    private Long latencyMs;

    private Long firstTokenLatencyMs;

    private String errorMessage;

    private String channelCode;

    private Long visitorId;

    private Long csTicketId;

    private Date createTime;

    private Date finishTime;
}
