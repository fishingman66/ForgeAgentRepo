package com.forgeagent.customerservice.domain.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.Date;

/**
 * 评测回流用例行视图对象(跨域:实现在 EvalFeedbackWriterImpl,底层 agent_eval_case)。
 * 字段对齐 @Select 直出列(蛇形 SQL 列→驼峰 Vo)。
 *
 * @author ForgeAgent
 */
@Data
public class CsEvalCaseRowVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long id;

    private Long datasetId;

    private String question;

    private String referenceAnswer;

    /** cs_badcase / cs_sampled */
    private String source;

    private Long sourceRefId;

    /** 0 待确认 / 1 已通过 / 2 已驳回 */
    private String reviewStatus;

    private Date createTime;
}
