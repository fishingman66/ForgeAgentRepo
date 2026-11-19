package com.forgeagent.agent.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.io.Serial;
import java.io.Serializable;

/**
 * release_gate_execution —— 治理域实体(JdbcTemplate 治理迁移)。
 */
@Data
@TableName("release_gate_execution")
public class ReleaseGateExecution implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** AgentID */
    private Long agentId;

    /** 目标版本 */
    private Integer targetVersion;

    /** 状态 */
    private String status;

    /** 检查明细JSON */
    private String checksJson;

    /** 原因 */
    private String reason;

    /** 是否豁免 */
    private String waived;

    /** 请求人 */
    private Long requestedBy;

    /** 创建时间 */
    private java.util.Date createTime;

}