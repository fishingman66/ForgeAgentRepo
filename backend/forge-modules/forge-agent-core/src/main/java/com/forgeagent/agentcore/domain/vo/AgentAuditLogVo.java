package com.forgeagent.agentcore.domain.vo;

import io.github.linpeilie.annotations.AutoMapper;
import lombok.Data;
import com.forgeagent.agentcore.domain.AgentAuditLog;

import java.io.Serial;
import java.io.Serializable;
import java.util.Date;

/**
 * 审计日志视图对象
 *
 * @author ForgeAgent
 */
@Data
@AutoMapper(target = AgentAuditLog.class)
public class AgentAuditLogVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 主键
     */
    private Long id;

    /**
     * 所属工作空间ID(平台事件为空)
     */
    private Long workspaceId;

    /**
     * 工作空间名称
     */
    private String workspaceName;

    /**
     * 事件类型
     */
    private String eventType;

    /**
     * 目标对象类型
     */
    private String targetType;

    /**
     * 目标对象ID
     */
    private String targetId;

    /**
     * 来源渠道
     */
    private String sourceChannel;

    /**
     * 客户端IP
     */
    private String clientIp;

    /**
     * User-Agent
     */
    private String userAgent;

    /**
     * 操作结果(0成功 1失败)
     */
    private String result;

    /**
     * 事件级别(INFO/WARN/RISK)
     */
    private String severity;

    /**
     * 链路追踪ID
     */
    private String traceId;

    /**
     * 变更前快照(JSON)
     */
    private String oldValue;

    /**
     * 变更后快照(JSON)
     */
    private String newValue;

    /**
     * 事件详情描述
     */
    private String detail;

    /**
     * 操作人用户ID
     */
    private Long operatorId;

    /**
     * 操作人用户名
     */
    private String operatorName;

    /**
     * 创建时间
     */
    private Date createTime;

}
