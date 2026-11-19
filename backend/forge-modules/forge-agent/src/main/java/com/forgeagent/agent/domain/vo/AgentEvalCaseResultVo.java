package com.forgeagent.agent.domain.vo;

import io.github.linpeilie.annotations.AutoMapper;
import lombok.Data;
import com.forgeagent.agent.domain.AgentEvalCaseResult;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 评测单样本结果视图对象
 *
 * @author ForgeAgent
 */
@Data
@AutoMapper(target = AgentEvalCaseResult.class)
public class AgentEvalCaseResultVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long id;

    private Long caseId;

    private String question;

    private String answer;

    private BigDecimal score;

    private BigDecimal judgeScore;

    private String judgeReason;

    private Long latencyMs;

    private String errorMessage;
}
