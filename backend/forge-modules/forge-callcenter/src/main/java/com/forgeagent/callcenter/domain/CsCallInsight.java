package com.forgeagent.callcenter.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.util.Date;

/**
 * 通话洞察(C4,方案 docs/07 §三 P0-2):情绪/意图/提取/质检报告统一表。
 *
 * <p>唯一键 {@code (call_id, kind, item_key)} 是幂等重放的落点——
 * 同一通话同一类洞察同一实例只保一行,重放走更新而非追加;
 * {@code segment_until_seq} 是增量游标(已消费的最大 segment.id),
 * 写入时只增不减,保证乱序回放不会把游标推回去重复消费。</p>
 *
 * <p>payload 只存脱敏后内容:转写文本入库前已经过 CsPiiMasker,
 * 洞察侧不再引入新的明文来源。</p>
 */
@Data
@TableName("agent_cc_call_insight")
public class CsCallInsight {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** agent_cc_call.id。 */
    private Long callId;

    private Long workspaceId;

    /** {@link com.forgeagent.callcenter.insight.InsightKind} 名称。 */
    private String kind;

    /** kind 内多实例键:情绪轮次 turn-&lt;seq&gt; / 意图 final / 提取字段名 / 报告类型。 */
    private String itemKey;

    /** 增量游标:已消费的最大 segment.id,单调不减。 */
    private Long segmentUntilSeq;

    /** 情绪标签 / 意图码 / 提取字段名 / ERROR。 */
    private String label;

    private BigDecimal score;

    /** JSON,只存脱敏内容。 */
    private String payload;

    private Date createTime;

    private Date updateTime;
}
