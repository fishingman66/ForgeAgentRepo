package com.forgeagent.kg.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.forgeagent.kg.domain.KgNodeEntity;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 图谱实体节点 Mapper(forge-kg / MySQL 存图实现)。
 *
 * <p>upsert 走唯一键 {@code uk_kg_node(scope_type,scope_id,user_id,name_key)}:
 * 一条 SQL 完成「有则 salience+1、无则插入」。更新值一律用参数而不是
 * {@code VALUES(col)}(后者 MySQL 8.0.20+ 已废弃),5.7/8.x/9.x 都能跑。</p>
 *
 * @author ForgeAgent
 */
public interface KgNodeMapper extends BaseMapper<KgNodeEntity> {

    @Insert("INSERT INTO kg_node "
        + "(scope_type, scope_id, user_id, name_key, name, node_type, salience, "
        + "last_seen, create_time, update_time) "
        + "VALUES (#{scopeType}, #{scopeId}, #{userId}, #{nameKey}, #{name}, #{nodeType}, "
        + "1, NOW(), NOW(), NOW()) "
        + "ON DUPLICATE KEY UPDATE salience = salience + 1, name = #{name}, "
        + "last_seen = NOW(), update_time = NOW()")
    int upsert(@Param("scopeType") String scopeType,
               @Param("scopeId") Long scopeId,
               @Param("userId") Long userId,
               @Param("nameKey") String nameKey,
               @Param("name") String name,
               @Param("nodeType") String nodeType);

    /** 按范围取显著度最高的节点,用于「文本 → 实体」反查 */
    @Select("SELECT id, scope_type, scope_id, user_id, name_key, name, node_type, salience, last_seen "
        + "FROM kg_node WHERE scope_type = #{scopeType} AND scope_id = #{scopeId} "
        + "AND user_id IN (#{userId}, 0) "
        + "ORDER BY salience DESC, id ASC LIMIT #{limit}")
    List<KgNodeEntity> selectByScope(@Param("scopeType") String scopeType,
                                     @Param("scopeId") Long scopeId,
                                     @Param("userId") Long userId,
                                     @Param("limit") int limit);

    /** 按 key 取实体展示名(边渲染时补两端名字) */
    @Select("<script>SELECT name_key, name FROM kg_node "
        + "WHERE scope_type = #{scopeType} AND scope_id = #{scopeId} "
        + "AND user_id IN (#{userId}, 0) AND name_key IN "
        + "<foreach collection='keys' item='k' open='(' separator=',' close=')'>#{k}</foreach></script>")
    List<KgNodeEntity> selectByScopeAndKeys(@Param("scopeType") String scopeType,
                                            @Param("scopeId") Long scopeId,
                                            @Param("userId") Long userId,
                                            @Param("keys") List<String> keys);

    @Delete("DELETE FROM kg_node WHERE scope_type = #{scopeType} AND scope_id = #{scopeId} "
        + "AND user_id = #{userId}")
    int deleteByScope(@Param("scopeType") String scopeType,
                      @Param("scopeId") Long scopeId,
                      @Param("userId") Long userId);
}
