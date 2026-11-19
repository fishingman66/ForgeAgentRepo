package com.forgeagent.agent.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableLogic;
import lombok.Data;
import java.io.Serial;
import java.io.Serializable;

/**
 * agent_prompt_template —— 治理域实体(JdbcTemplate 治理迁移)。
 */
@Data
@TableName("agent_prompt_template")
public class AgentPromptTemplate implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 工作空间ID */
    private Long workspaceId;

    /** 模板名称 */
    private String name;

    /** 描述 */
    private String description;

    /** 分类 */
    private String category;

    /** 模板内容 */
    private String content;

    /** 变量契约JSON */
    private String variableSchema;

    /** 稳定版ID */
    private Long stableVersionId;

    /** 状态 */
    private String status;

    /** 创建人 */
    private Long createBy;

    /** 创建时间 */
    private java.util.Date createTime;

    /** 更新时间 */
    private java.util.Date updateTime;

    @TableLogic
    /** 删除标志 */
    private Long delFlag;

}