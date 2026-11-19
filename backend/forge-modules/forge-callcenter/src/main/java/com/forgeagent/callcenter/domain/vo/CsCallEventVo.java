package com.forgeagent.callcenter.domain.vo;

import io.github.linpeilie.annotations.AutoMapper;
import lombok.Data;
import com.forgeagent.callcenter.domain.CsCallEvent;

import java.io.Serial;
import java.io.Serializable;
import java.util.Date;

/**
 * 通话事件轨迹视图对象
 *
 * @author ForgeAgent
 */
@Data
@AutoMapper(target = CsCallEvent.class)
public class CsCallEventVo implements Serializable {

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
     * eventSeq
     */
    private Long eventSeq;

    /**
     * eventType
     */
    private String eventType;

    /**
     * fromStatus
     */
    private String fromStatus;

    /**
     * toStatus
     */
    private String toStatus;

    /**
     * actorType
     */
    private String actorType;

    /**
     * payload
     */
    private String payload;

    /**
     * 创建时间
     */
    private Date createTime;
}
