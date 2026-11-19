package com.forgeagent.callcenter.domain.vo;

import io.github.linpeilie.annotations.AutoMapper;
import lombok.Data;
import com.forgeagent.callcenter.domain.CsCallbackTask;

import java.io.Serial;
import java.io.Serializable;
import java.util.Date;

/**
 * 呼损回拨单视图对象(C8)
 *
 * @author ForgeAgent
 */
@Data
@AutoMapper(target = CsCallbackTask.class)
public class CsCallbackTaskVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 主键
     */
    private Long id;

    /**
     * callId
     */
    private Long callId;

    /**
     * workspaceId
     */
    private Long workspaceId;

    /**
     * channelCode
     */
    private String channelCode;

    /**
     * callerNumber
     */
    private String callerNumber;

    /**
     * reason
     */
    private String reason;

    /**
     * status
     */
    private String status;

    /**
     * attemptCount
     */
    private Integer attemptCount;

    /**
     * lastAttemptAt
     */
    private Date lastAttemptAt;

    /**
     * operatorId
     */
    private Long operatorId;

    /**
     * remark
     */
    private String remark;

    /**
     * 创建时间
     */
    private Date createTime;

    /**
     * 更新时间
     */
    private Date updateTime;
}
