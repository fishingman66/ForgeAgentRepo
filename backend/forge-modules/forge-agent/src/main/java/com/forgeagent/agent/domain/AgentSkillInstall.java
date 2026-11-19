package com.forgeagent.agent.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.forgeagent.common.mybatis.core.domain.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serial;

/**
 * 技能-应用安装绑定对象 agent_skill_install(08方案 / A1)
 *
 * @author ForgeAgent
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("agent_skill_install")
public class AgentSkillInstall extends BaseEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 主键
     */
    @TableId(value = "id")
    private Long id;

    /**
     * 技能ID
     */
    private Long skillId;

    /**
     * AgentID
     */
    private Long agentId;

    /**
     * 安装时技能归属空间(审计)
     */
    private Long workspaceId;

    /**
     * 钉住的技能版本(NULL=跟随最新)。发布时固定为当前版本,回滚时恢复快照里的值
     */
    private Integer pinnedVersion;

    /**
     * 删除标志
     */
    @TableLogic
    private Long delFlag;
}
