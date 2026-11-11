package com.forgeagent.kg.service;

/**
 * 图谱关系边(forge-kg),两端带展示名,调用方可直接渲染。
 *
 * @param id         存储行 ID(Neo4j 等后端返回其元素 ID 的数值映射),维护页删除用;召回路径可能为空
 * @param sourceKey  主语实体归一化键
 * @param sourceName 主语展示名
 * @param relation   关系
 * @param targetKey  宾语实体归一化键
 * @param targetName 宾语展示名
 * @param weight     权重
 * @author ForgeAgent
 */
public record KgEdge(Long id, String sourceKey, String sourceName, String relation,
                     String targetKey, String targetName, double weight) {

    /** 召回/渲染场景的便捷构造:不带行 ID */
    public static KgEdge of(String sourceKey, String sourceName, String relation,
                            String targetKey, String targetName, double weight) {
        return new KgEdge(null, sourceKey, sourceName, relation, targetKey, targetName, weight);
    }
}
