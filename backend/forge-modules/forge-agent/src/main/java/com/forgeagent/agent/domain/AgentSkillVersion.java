package com.forgeagent.agent.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.io.Serial;
import java.io.Serializable;

/**
 * agent_skill_version —— 治理域实体(JdbcTemplate 治理迁移)。
 */
@Data
@TableName("agent_skill_version")
public class AgentSkillVersion implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 技能ID */
    private Long skillId;

    /** 版本号 */
    private Integer versionNo;

    /** 版本名称 */
    private String name;

    /** 摘要 */
    private String description;

    /** 操作手册 */
    private String instructions;

    /** 工具ID列表 */
    private String toolIds;

    /** MCP列表 */
    private String mcpIds;

    /** 知识库列表 */
    private String knowledgeIds;

    /** 提示词片段 */
    private String promptFragment;

    /** 内容SHA-256 */
    private String contentHash;

    /** 归档人 */
    private Long createBy;

    /** 归档时间 */
    private java.util.Date createTime;

}