package com.forgeagent.customerservice.domain.vo;

import io.github.linpeilie.annotations.AutoMapper;
import lombok.Data;
import com.forgeagent.customerservice.domain.CsAuditLog;

import java.io.Serial;
import java.io.Serializable;
import java.util.Date;
/**
 * 合规审计视图对象
 *
 * @author ForgeAgent
 */
@Data
@AutoMapper(target = CsAuditLog.class)
public class CsAuditLogVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long id;
    private String channelCode;
    private String eventType;
    private String actorType;
    private String actorId;
    private String detail;
    private Long workspaceId;
    private Date createTime;
}
