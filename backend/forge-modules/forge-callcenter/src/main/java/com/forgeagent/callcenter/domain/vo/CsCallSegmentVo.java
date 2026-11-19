package com.forgeagent.callcenter.domain.vo;

import io.github.linpeilie.annotations.AutoMapper;
import lombok.Data;
import com.forgeagent.callcenter.domain.CsCallSegment;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Date;

/**
 * 通话转写分片视图对象
 *
 * @author ForgeAgent
 */
@Data
@AutoMapper(target = CsCallSegment.class)
public class CsCallSegmentVo implements Serializable {

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
     * speaker
     */
    private String speaker;

    /**
     * text
     */
    private String text;

    /**
     * startMs
     */
    private Long startMs;

    /**
     * endMs
     */
    private Long endMs;

    /**
     * asrConfidence
     */
    private BigDecimal asrConfidence;

    /**
     * isFinal
     */
    private String isFinal;

    /**
     * segmentKind
     */
    private String segmentKind;

    /**
     * 创建时间
     */
    private Date createTime;
}
