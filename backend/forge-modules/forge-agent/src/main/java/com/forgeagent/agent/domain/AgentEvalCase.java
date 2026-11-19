package com.forgeagent.agent.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.io.Serial;
import java.io.Serializable;

/**
 * agent_eval_case —— 评测域实体(JdbcTemplate 治理迁移)。
 */
@Data
@TableName("agent_eval_case")
public class AgentEvalCase implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 评测集ID */
    private Long datasetId;

    /** 评测问题 */
    private String question;

    /** 规则评分关键词(逗号分隔) */
    private String expectedKeywords;

    /** 参考答案(Judge用) */
    private String referenceAnswer;

    /** 创建时间 */
    private java.util.Date createTime;

    /** 期望拒答(0/1) */
    private Integer expectedRefusal;

    /** 样本来源(cs_badcase/cs_sampled) */
    private String source;

    /** 来源单据ID */
    private Long sourceRefId;

    /** 审核态(0待确认 1通过 2驳回) */
    private String reviewStatus;

}