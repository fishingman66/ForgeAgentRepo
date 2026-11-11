package com.forgeagent.customerservice.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 图谱检索扩展效果日聚合 Mapper(P3 / M4 后续)。
 *
 * <p>观测口径:expanded 检索与非 expanded 检索各自的零命中率对比。
 * 这是同期的观察性对比(开启扩展后,命中的往往是没扩展就搜不到的问题),
 * 不是 A/B 实验——解读时注意幸存者偏差:扩展本身就是给「原词搜不到」的场景兜底。</p>
 *
 * @author ForgeAgent
 */
public interface CsKgExpandMetricMapper {

    /** 日聚合 upsert:唯一键 (workspace_id, stat_date),计数累加 */
    @Insert("INSERT INTO agent_cs_kg_expand_metric "
        + "(workspace_id, stat_date, total_searches, expanded_searches, zero_hit_total, "
        + "zero_hit_expanded, update_time) "
        + "VALUES (#{workspaceId}, #{statDate}, 1, #{expanded}, #{zeroHit}, #{zeroExpanded}, NOW()) "
        + "ON DUPLICATE KEY UPDATE total_searches = total_searches + 1, "
        + "expanded_searches = expanded_searches + #{expanded}, "
        + "zero_hit_total = zero_hit_total + #{zeroHit}, "
        + "zero_hit_expanded = zero_hit_expanded + #{zeroExpanded}, update_time = NOW()")
    int upsert(@Param("workspaceId") Long workspaceId,
               @Param("statDate") LocalDate statDate,
               @Param("expanded") int expanded,
               @Param("zeroHit") int zeroHit,
               @Param("zeroExpanded") int zeroExpanded);

    /** 最近 N 天的日聚合行(供管理端对比零命中率) */
    @Select("SELECT stat_date AS statDate, total_searches AS totalSearches, "
        + "expanded_searches AS expandedSearches, zero_hit_total AS zeroHitTotal, "
        + "zero_hit_expanded AS zeroHitExpanded "
        + "FROM agent_cs_kg_expand_metric WHERE workspace_id = #{workspaceId} "
        + "AND stat_date >= DATE_SUB(CURDATE(), INTERVAL #{days} DAY) ORDER BY stat_date")
    List<Map<String, Object>> listRecent(@Param("workspaceId") Long workspaceId,
                                         @Param("days") int days);
}
