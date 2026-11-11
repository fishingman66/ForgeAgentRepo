package com.forgeagent.kg.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.forgeagent.kg.domain.KgEdgeEntity;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 图谱关系边 Mapper(forge-kg / MySQL 存图实现)。
 *
 * @author ForgeAgent
 */
public interface KgEdgeMapper extends BaseMapper<KgEdgeEntity> {

    @Insert("INSERT INTO kg_edge "
        + "(scope_type, scope_id, user_id, source_key, relation, target_key, weight, "
        + "evidence_ref, last_seen, create_time, update_time) "
        + "VALUES (#{scopeType}, #{scopeId}, #{userId}, #{sourceKey}, #{relation}, "
        + "#{targetKey}, 1, #{evidenceRef}, NOW(), NOW(), NOW()) "
        + "ON DUPLICATE KEY UPDATE weight = weight + 1, "
        + "evidence_ref = IFNULL(#{evidenceRef}, evidence_ref), "
        + "last_seen = NOW(), update_time = NOW()")
    int upsert(@Param("scopeType") String scopeType,
               @Param("scopeId") Long scopeId,
               @Param("userId") Long userId,
               @Param("sourceKey") String sourceKey,
               @Param("relation") String relation,
               @Param("targetKey") String targetKey,
               @Param("evidenceRef") Long evidenceRef);

    /** 取指定实体 1 跳邻接边,按权重降序;范围含共享级(user_id=0) */
    @Select("<script>SELECT id, scope_type, scope_id, user_id, source_key, relation, target_key, "
        + "weight, evidence_ref, last_seen FROM kg_edge "
        + "WHERE scope_type = #{scopeType} AND scope_id = #{scopeId} "
        + "AND user_id IN (#{userId}, 0) AND ("
        + "source_key IN <foreach collection='keys' item='k' open='(' separator=',' close=')'>#{k}</foreach> "
        + "OR target_key IN <foreach collection='keys' item='k' open='(' separator=',' close=')'>#{k}</foreach>) "
        + "ORDER BY weight DESC, id ASC LIMIT #{limit}</script>")
    List<KgEdgeEntity> selectAround(@Param("scopeType") String scopeType,
                                    @Param("scopeId") Long scopeId,
                                    @Param("userId") Long userId,
                                    @Param("keys") List<String> keys,
                                    @Param("limit") int limit);

    /** 范围内全部关系边,按权重降序(维护页/领域知识盘点用) */
    @Select("SELECT id, scope_type, scope_id, user_id, source_key, relation, target_key, "
        + "weight, evidence_ref, last_seen FROM kg_edge "
        + "WHERE scope_type = #{scopeType} AND scope_id = #{scopeId} "
        + "AND user_id IN (#{userId}, 0) "
        + "ORDER BY weight DESC, id ASC LIMIT #{limit}")
    List<KgEdgeEntity> selectByScope(@Param("scopeType") String scopeType,
                                     @Param("scopeId") Long scopeId,
                                     @Param("userId") Long userId,
                                     @Param("limit") int limit);

    @Delete("DELETE FROM kg_edge WHERE scope_type = #{scopeType} AND scope_id = #{scopeId} "
        + "AND user_id = #{userId}")
    int deleteByScope(@Param("scopeType") String scopeType,
                      @Param("scopeId") Long scopeId,
                      @Param("userId") Long userId);
}
