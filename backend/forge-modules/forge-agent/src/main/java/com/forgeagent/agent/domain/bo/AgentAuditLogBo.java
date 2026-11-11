package com.forgeagent.agent.domain.bo;

import jakarta.validation.constraints.Size;
import lombok.Data;
import com.forgeagent.common.core.validate.QueryGroup;

import java.io.Serial;
import java.io.Serializable;

/**
 * 审计日志查询对象
 *
 * @author ForgeAgent
 */
@Data
public class AgentAuditLogBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 工作空间ID(超管可按空间过滤)
     */
    private Long workspaceId;

    /**
     * 归属部门ID(超管/部门管理视角按部门过滤,映射到空间集合)
     */
    private Long deptId;

    /**
     * 事件类型
     */
    @Size(max = 40, groups = {QueryGroup.class})
    private String eventType;

    /**
     * 操作人用户名(模糊)
     */
    @Size(max = 30, groups = {QueryGroup.class})
    private String operatorName;

    /**
     * 事件级别(INFO/WARN/RISK)
     */
    @Size(max = 10, groups = {QueryGroup.class})
    private String severity;

    /**
     * 操作结果(0成功 1失败)
     */
    @Size(max = 1, groups = {QueryGroup.class})
    private String result;

    /**
     * 来源渠道(web/api_key/system)
     */
    @Size(max = 20, groups = {QueryGroup.class})
    private String sourceChannel;

    /**
     * 开始时间(yyyy-MM-dd HH:mm:ss)
     */
    private String beginTime;

    /**
     * 结束时间(yyyy-MM-dd HH:mm:ss)
     */
    private String endTime;

}
