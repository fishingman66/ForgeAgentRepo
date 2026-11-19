package com.forgeagent.agent.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.io.Serial;
import java.io.Serializable;

/**
 * agent_prompt_version —— 治理域实体(JdbcTemplate 治理迁移)。
 */
@Data
@TableName("agent_prompt_version")
public class AgentPromptVersion implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 模板ID */
    private Long templateId;

    /** 版本号 */
    private Integer versionNo;

    /** 内容 */
    private String content;

    /** 变量契约 */
    private String variableSchema;

    /** SHA-256 */
    private String contentHash;

    /** 说明 */
    private String remark;

    /** 创建人 */
    private Long createBy;

    /** 创建时间 */
    private java.util.Date createTime;

}