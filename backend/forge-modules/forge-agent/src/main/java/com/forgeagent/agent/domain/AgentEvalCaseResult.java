package com.forgeagent.agent.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.io.Serial;
import java.io.Serializable;

/**
 * agent_eval_case_result —— 评测域实体(JdbcTemplate 治理迁移)。
 */
@Data
@TableName("agent_eval_case_result")
public class AgentEvalCaseResult implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 评测运行ID */
    private Long runId;

    /** 用例ID */
    private Long caseId;

    /** 问题 */
    private String question;

    /** 回答 */
    private String answer;

    /** 规则分 */
    private java.math.BigDecimal score;

    /** Judge分 */
    private java.math.BigDecimal judgeScore;

    /** Judge理由 */
    private String judgeReason;

    /** 延迟ms */
    private Long latencyMs;

    /** 总token */
    private Integer totalTokens;

    /** 错误信息 */
    private String errorMessage;

    /** 创建时间 */
    private java.util.Date createTime;

}