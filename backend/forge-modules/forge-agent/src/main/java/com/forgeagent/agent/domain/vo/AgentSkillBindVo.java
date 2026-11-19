package com.forgeagent.agent.domain.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.Date;

/**
 * 应用已安装技能视图对象(应用视角)。
 *
 * <p>与 {@link AgentSkillVo} 的区别:这里只描述「绑定关系 + 装载摘要」,
 * 不含操作手册大字段,且**包含已停用技能**——停用后仍需让运营看到并手动卸载。</p>
 *
 * @author ForgeAgent
 */
@Data
public class AgentSkillBindVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 技能ID */
    private Long skillId;

    /** 技能名称 */
    private String name;

    /** 一行摘要 */
    private String description;

    /** 技能版本(钉住时即钉住的版本,否则为当前版本) */
    private Integer version;

    /** 钉住的版本(NULL=跟随最新) */
    private Integer pinnedVersion;

    /** 状态(0正常 1停用) */
    private String status;

    /** 归属工作空间(NULL=平台共享) */
    private Long workspaceId;

    /** 安装时间 */
    private Date installTime;
}
