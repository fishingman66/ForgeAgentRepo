package com.forgeagent.agent.domain.vo;

import io.github.linpeilie.annotations.AutoMapper;
import lombok.Data;
import com.forgeagent.agent.domain.ReleaseGateWaiver;

import java.io.Serial;
import java.io.Serializable;
import java.util.Date;

/**
 * 发布门禁豁免视图对象
 *
 * @author ForgeAgent
 */
@Data
@AutoMapper(target = ReleaseGateWaiver.class)
public class ReleaseGateWaiverVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long id;

    private Long agentId;

    private String reason;

    /** pending / approved / rejected */
    private String status;

    private Long requestedBy;

    private Long approvedBy;

    private Date expiresAt;

    private Date createTime;
}
