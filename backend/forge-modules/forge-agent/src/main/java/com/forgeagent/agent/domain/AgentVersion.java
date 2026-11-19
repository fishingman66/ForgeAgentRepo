package com.forgeagent.agent.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.io.Serial;
import java.io.Serializable;

/**
 * agent_version —— 治理域实体(JdbcTemplate 治理迁移)。
 */
@Data
@TableName("agent_version")
public class AgentVersion implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** AgentID */
    private Long agentId;

    /** 工作空间ID */
    private Long workspaceId;

    /** 版本号 */
    private Integer versionNo;

    /** 配置快照JSON */
    private String snapshotJson;

    /** 快照SHA-256 */
    private String configHash;

    /** 版本说明 */
    private String remark;

    /** publish/rollback/manual */
    private String source;

    /** 创建人 */
    private Long createBy;

    /** 创建时间 */
    private java.util.Date createTime;

}