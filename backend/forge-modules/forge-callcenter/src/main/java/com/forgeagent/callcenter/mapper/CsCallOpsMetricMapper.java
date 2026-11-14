package com.forgeagent.callcenter.mapper;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 运营指标聚合查询(C8):跨表统计下推到 SQL,避免把当日全部洞察/质检行捞进应用层。
 *
 * <p>只放「必须跨表聚合」的两条;通话自身的口径(接通/呼损/等待/转人工)在
 * {@code CallMetricService.compute} 里用纯函数算,便于单测锁定口径。</p>
 */
public interface CsCallOpsMetricMapper {

    /** 当日含负面情绪洞察的通话数(按 call_id 去重;一趟通话可能有多轮情绪行)。 */
    @Select("SELECT COUNT(DISTINCT i.call_id) FROM agent_cc_call_insight i"
        + " JOIN agent_cc_call c ON c.id = i.call_id"
        + " WHERE c.workspace_id = #{workspaceId}"
        + " AND c.ring_at >= #{from} AND c.ring_at < #{to}"
        + " AND i.kind = 'SENTIMENT' AND i.label = 'NEGATIVE'")
    Integer countNegativeCalls(@Param("workspaceId") Long workspaceId,
                               @Param("from") java.util.Date from,
                               @Param("to") java.util.Date to);

    /** 当日语音质检均分(0-100);无质检记录返回 null。 */
    @Select("SELECT AVG(q.score) FROM agent_cs_quality_check q"
        + " JOIN agent_cc_call c ON c.ticket_id = q.ticket_id"
        + " WHERE c.workspace_id = #{workspaceId}"
        + " AND c.ring_at >= #{from} AND c.ring_at < #{to}"
        + " AND q.source = 'VOICE_TRANSCRIPT'")
    Double avgVoiceQuality(@Param("workspaceId") Long workspaceId,
                           @Param("from") java.util.Date from,
                           @Param("to") java.util.Date to);
}
