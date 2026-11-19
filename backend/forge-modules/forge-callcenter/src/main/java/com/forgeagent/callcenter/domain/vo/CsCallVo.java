package com.forgeagent.callcenter.domain.vo;

import io.github.linpeilie.annotations.AutoMapper;
import lombok.Data;
import com.forgeagent.callcenter.domain.CsCall;

import java.io.Serial;
import java.io.Serializable;
import java.util.Date;

/**
 * 通话主记录视图对象
 *
 * @author ForgeAgent
 */
@Data
@AutoMapper(target = CsCall.class)
public class CsCallVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 主键
     */
    private Long id;

    /**
     * callId
     */
    private String callId;

    /**
     * mediaProvider
     */
    private String mediaProvider;

    /**
     * channelCode
     */
    private String channelCode;

    /**
     * workspaceId
     */
    private Long workspaceId;

    /**
     * agentId
     */
    private Long agentId;

    /**
     * conversationId
     */
    private Long conversationId;

    /**
     * ticketId
     */
    private Long ticketId;

    /**
     * direction
     */
    private String direction;

    /**
     * callerNumber
     */
    private String callerNumber;

    /**
     * calleeNumber
     */
    private String calleeNumber;

    /**
     * ani
     */
    private String ani;

    /**
     * dnis
     */
    private String dnis;

    /**
     * status
     */
    private String status;

    /**
     * botHandled
     */
    private String botHandled;

    /**
     * workNo
     */
    private String workNo;

    /**
     * ringAt
     */
    private Date ringAt;

    /**
     * answerAt
     */
    private Date answerAt;

    /**
     * hangupAt
     */
    private Date hangupAt;

    /**
     * durationSec
     */
    private Integer durationSec;

    /**
     * billSec
     */
    private Integer billSec;

    /**
     * hangupCause
     */
    private String hangupCause;

    /**
     * hangupBy
     */
    private String hangupBy;

    /**
     * sipCode
     */
    private Integer sipCode;

    /**
     * recordingOssKey
     */
    private String recordingOssKey;

    /**
     * recordingId
     */
    private String recordingId;

    /**
     * recordingReadyAt
     */
    private Date recordingReadyAt;

    /**
     * recordingRetainUntil
     */
    private Date recordingRetainUntil;

    /**
     * ivrPath
     */
    private String ivrPath;

    /**
     * dtmfDigits
     */
    private String dtmfDigits;

    /**
     * transferFrom
     */
    private String transferFrom;

    /**
     * transferTo
     */
    private String transferTo;

    /**
     * 创建时间
     */
    private Date createTime;

    /**
     * 更新时间
     */
    private Date updateTime;
}
