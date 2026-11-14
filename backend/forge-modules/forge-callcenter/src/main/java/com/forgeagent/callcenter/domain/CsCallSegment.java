package com.forgeagent.callcenter.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.util.Date;

/**
 * 通话转写分片(V1):弹屏、质检、摘要与知识缺口的数据源,文本入库前统一 PII 脱敏。
 */
@Data
@TableName("agent_cc_call_segment")
public class CsCallSegment {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private Long callId;
    private String speaker;
    private String text;
    private Long startMs;
    private Long endMs;
    private BigDecimal asrConfidence;
    private String isFinal;
    /** C8 段类型:TRANSCRIPT(转写)/TRANSFER_RING(转人工发起)/TRANSFER_CONNECT(坐席接通)。 */
    private String segmentKind;
    private Date createTime;
}
