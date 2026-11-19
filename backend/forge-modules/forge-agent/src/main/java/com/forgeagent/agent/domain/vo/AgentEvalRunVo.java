package com.forgeagent.agent.domain.vo;

import io.github.linpeilie.annotations.AutoMapper;
import lombok.Data;
import com.forgeagent.agent.domain.AgentEvalRun;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Date;

/**
 * 评测任务视图对象(judgeEnabled 统一为布尔,替代实体 "1"/"0" 串)
 *
 * @author ForgeAgent
 */
@Data
@AutoMapper(target = AgentEvalRun.class)
public class AgentEvalRunVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long id;

    private Long datasetId;

    private Long agentId;

    private Long workspaceId;

    /** running / success / failed */
    private String status;

    private Integer totalCases;

    private Integer finishedCases;

    private BigDecimal avgScore;

    private BigDecimal toolSuccessRate;

    private BigDecimal refusalRate;

    private Long p95LatencyMs;

    private Integer totalTokens;

    /** 实体存 "1"/"0",Vo 统一布尔(Jackson 宽松转换) */
    private Boolean judgeEnabled;

    /** 评测时刻配置指纹,发布门禁据此判断评测覆盖 */
    private String configHash;

    private String errorMessage;

    private Long createBy;

    private Date createTime;

    private Date finishTime;
}
