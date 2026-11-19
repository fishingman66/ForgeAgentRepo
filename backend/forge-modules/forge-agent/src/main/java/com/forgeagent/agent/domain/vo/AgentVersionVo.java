package com.forgeagent.agent.domain.vo;

import io.github.linpeilie.annotations.AutoMapper;
import lombok.Data;
import com.forgeagent.agent.domain.AgentVersion;

import java.io.Serial;
import java.io.Serializable;
import java.util.Date;
import java.util.Map;

/**
 * Agent 版本快照视图对象
 *
 * @author ForgeAgent
 */
@Data
@AutoMapper(target = AgentVersion.class)
public class AgentVersionVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long id;

    private Long agentId;

    private Long workspaceId;

    /** 顺序版本号(发布时与 agent_info.version 对齐) */
    private Integer versionNo;

    /** 配置 SHA-256 摘要 */
    private String configHash;

    private String remark;

    private String source;

    private Long createBy;

    private Date createTime;

    /** 解析后的配置快照(仅明细接口返回) */
    private Map<String, Object> snapshot;
}
