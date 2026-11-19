package com.forgeagent.callcenter.domain.vo;

import io.github.linpeilie.annotations.AutoMapper;
import lombok.Data;
import com.forgeagent.callcenter.domain.CsCallProfile;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Date;

/**
 * 通话画像视图对象(C8)
 *
 * @author ForgeAgent
 */
@Data
@AutoMapper(target = CsCallProfile.class)
public class CsCallProfileVo implements Serializable {

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
     * intentCode
     */
    private String intentCode;

    /**
     * sentimentLabel
     */
    private String sentimentLabel;

    /**
     * sentimentScore
     */
    private BigDecimal sentimentScore;

    /**
     * qualityScore
     */
    private Integer qualityScore;

    /**
     * durationSec
     */
    private Integer durationSec;

    /**
     * turnCount
     */
    private Integer turnCount;

    /**
     * transferFlag
     */
    private String transferFlag;

    /**
     * resolvedFlag
     */
    private String resolvedFlag;

    /**
     * tags
     */
    private String tags;

    /**
     * summary
     */
    private String summary;

    /**
     * payload
     */
    private String payload;

    /**
     * 创建时间
     */
    private Date createTime;

    /**
     * 更新时间
     */
    private Date updateTime;
}
