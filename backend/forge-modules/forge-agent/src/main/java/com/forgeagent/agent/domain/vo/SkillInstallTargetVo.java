package com.forgeagent.agent.domain.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.Date;

/**
 * 技能安装目标视图对象(技能视角)。
 *
 * <p>一次返回全部已发布应用 + 该技能的安装态,前端一个表格完成安装/卸载,
 * 避免「先查已安装、再查候选」两次请求。</p>
 *
 * @author ForgeAgent
 */
@Data
public class SkillInstallTargetVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 应用ID */
    private Long agentId;

    /** 应用名称 */
    private String agentName;

    /** 应用状态(1已发布) */
    private String agentStatus;

    /** 应用归属空间(NULL=平台共享) */
    private Long workspaceId;

    /** 是否已安装本技能 */
    private Boolean installed;

    /** 安装时间(未安装为 null) */
    private Date installTime;
}
