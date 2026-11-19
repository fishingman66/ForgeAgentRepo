package com.forgeagent.agent.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableLogic;
import lombok.Data;
import java.io.Serial;
import java.io.Serializable;

/**
 * agent_eval_dataset —— 评测域实体(JdbcTemplate 治理迁移)。
 */
@Data
@TableName("agent_eval_dataset")
public class AgentEvalDataset implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 所属工作空间ID */
    private Long workspaceId;

    /** 建议绑定的Agent(可空) */
    private Long agentId;

    /** 评测集名称 */
    private String name;

    /** 描述 */
    private String description;

    /** 创建人 */
    private Long createBy;

    /** 创建时间 */
    private java.util.Date createTime;

    @TableLogic
    /** 删除标志 */
    private Long delFlag;

}