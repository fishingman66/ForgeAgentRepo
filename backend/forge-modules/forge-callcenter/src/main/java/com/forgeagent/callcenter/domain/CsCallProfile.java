package com.forgeagent.callcenter.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.util.Date;

/**
 * 通话画像快照(C8,方案 docs/07 §三 P2):一通电话一行,幂等。
 *
 * <p>数据来自已落库的洞察(意图/情绪/提取)与质检结果,不含任何新的明文来源;
 * 摘要由确定性规则拼装,不做生成式润色——运营看的是口径,不是文采。</p>
 */
@Data
@TableName("agent_cc_call_profile")
public class CsCallProfile {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** agent_cc_call.id。 */
    private Long callId;

    private Long workspaceId;
    private String channelCode;
    /** 已 PII 脱敏。 */
    private String callerNumber;

    /** 挂机全文意图码。 */
    private String intentCode;
    /** 通话内最负面的一次情绪标签。 */
    private String sentimentLabel;
    private BigDecimal sentimentScore;
    private Integer qualityScore;
    private Integer durationSec;
    /** 主叫轮次。 */
    private Integer turnCount;
    /** 是否转过人工。 */
    private String transferFlag;
    /** 机器人自助解决(无工单)。 */
    private String resolvedFlag;
    /** 逗号分隔标签。 */
    private String tags;
    private String summary;
    /** JSON:意图/情绪分布/提取结果。 */
    private String payload;

    private Date createTime;
    private Date updateTime;
}
