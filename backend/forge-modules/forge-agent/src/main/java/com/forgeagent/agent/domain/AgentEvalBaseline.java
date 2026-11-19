package com.forgeagent.agent.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.io.Serial;
import java.io.Serializable;

/**
 * agent_eval_baseline —— 评测域实体(JdbcTemplate 治理迁移)。
 */
@Data
@TableName("agent_eval_baseline")
public class AgentEvalBaseline implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** AgentID */
    private Long agentId;

    /** 评测集ID */
    private Long datasetId;

    /** 运行ID */
    private Long runId;

    /** 指标JSON */
    private String metricsJson;

    /** 是否生效(0/1) */
    private String active;

    /** 创建人 */
    private Long createBy;

    /** 创建时间 */
    private java.util.Date createTime;

}