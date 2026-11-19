package com.forgeagent.agent.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.io.Serial;
import java.io.Serializable;

/**
 * release_gate_waiver —— 治理域实体(JdbcTemplate 治理迁移)。
 */
@Data
@TableName("release_gate_waiver")
public class ReleaseGateWaiver implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** AgentID */
    private Long agentId;

    /** 豁免理由 */
    private String reason;

    /** 状态 */
    private String status;

    /** 申请人 */
    private Long requestedBy;

    /** 审批人 */
    private Long approvedBy;

    /** 过期时间 */
    private java.util.Date expiresAt;

    /** 创建时间 */
    private java.util.Date createTime;

}