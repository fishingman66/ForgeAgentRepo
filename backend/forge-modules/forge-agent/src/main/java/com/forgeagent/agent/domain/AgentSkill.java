package com.forgeagent.agent.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.forgeagent.common.mybatis.core.domain.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serial;

/**
 * 声明式技能包对象 agent_skill(08方案 / A1)
 *
 * <p>Skill = Markdown 操作手册 + 工具/MCP/知识库引用 + 提示词片段,平台解释执行,
 * 不含任何可执行代码。懒加载:上下文里只出现 name + description 一行摘要,
 * 全文由模型经 load_skill 工具按需取用。</p>
 *
 * @author ForgeAgent
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("agent_skill")
public class AgentSkill extends BaseEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 主键
     */
    @TableId(value = "id")
    private Long id;

    /**
     * 技能名称(同一空间内唯一)
     */
    private String name;

    /**
     * 一行摘要(懒加载目录展示用)
     */
    private String description;

    /**
     * 操作手册(Markdown,模型按需加载)
     */
    private String instructions;

    /**
     * 引用平台工具ID列表(逗号分隔)
     */
    private String toolIds;

    /**
     * 引用MCP服务器ID列表(逗号分隔)
     */
    private String mcpIds;

    /**
     * 引用知识库ID列表(逗号分隔)
     */
    private String knowledgeIds;

    /**
     * 加载后追加的提示词片段
     */
    private String promptFragment;

    /**
     * 技能版本(内容变更自增)
     */
    private Integer version;

    /**
     * 状态(0正常 1停用)
     */
    private String status;

    /**
     * 归属工作空间(NULL=平台共享)
     */
    private Long workspaceId;

    /**
     * 备注
     */
    private String remark;

    /**
     * 删除标志
     */
    @TableLogic
    private Long delFlag;
}
