package com.forgeagent.agent.mapper;

import org.apache.ibatis.annotations.Select;

/**
 * information_schema 自检 Mapper(W4):元数据查询无业务表归属,专责独立接口。
 * 仅做启动期表/列存在性检查,无写路径。
 *
 * @author ForgeAgent
 */
public interface SchemaIntrospectMapper {

    @Select("SELECT COUNT(*) FROM information_schema.tables "
        + "WHERE table_schema = DATABASE() AND table_name = #{table}")
    long countTable(String table);

    @Select("SELECT COUNT(*) FROM information_schema.columns "
        + "WHERE table_schema = DATABASE() AND table_name = #{table} "
        + "AND column_name = #{column} AND is_nullable = 'NO'")
    long countNotNullColumn(String table, String column);
}
