package com.forgeagent.kg.service;

import java.util.List;

/**
 * 知识图谱存储抽象(forge-kg)。
 *
 * <p><b>后端可插拔:</b>开发/测试环境用 MySQL 两表存图
 * ({@code MysqlKgStore},零新基建),生产上 Neo4j 等专用图库时,
 * 只需新增一个 {@code backend()} 返回 {@code "neo4j"} 的实现并注册为 Bean,
 * 调用方(记忆、客服)一行都不用改。</p>
 *
 * <p><b>实现约定:</b></p>
 * <ul>
 *   <li>实体归一化统一走 {@link KgText},保证不同后端落到同一套唯一键口径。</li>
 *   <li>{@link #upsert} 必须幂等:同一 (范围, 用户, 主语, 关系, 宾语) 只累加权重,不产生重复边。</li>
 *   <li>读范围含共享级(user_id=0),否则共享关系永远召不回。</li>
 *   <li>所有方法允许抛异常,由 {@link KgService} 兜底降级,不得让图谱故障阻断调用方主流程。</li>
 * </ul>
 *
 * @author ForgeAgent
 */
public interface KgStore {

    /** 后端标识,与 {@code kg.backend} 对应 */
    String backend();

    /** 幂等写入三元组:节点 salience +1,边 weight +1 */
    void upsert(KgScope scope, List<KgTriple> triples);

    /**
     * 1 跳邻接召回:给定一段文本,反查其中出现的实体并展开邻接关系。
     *
     * @param scope 范围
     * @param texts 用于匹配实体名的文本(记忆里是入选记忆内容,客服里可以是访客问题)
     * @param limit 返回边上限
     */
    List<KgEdge> neighbors(KgScope scope, List<String> texts, int limit);

    /** 范围内显著度最高的实体(管理端/调试用) */
    List<KgNode> nodes(KgScope scope, int limit);

    /**
     * 范围内的关系边,按权重降序(维护页/领域知识盘点用)。
     * 读范围与 {@link #neighbors} 一致:本人 + 共享级。
     */
    List<KgEdge> edges(KgScope scope, int limit);

    /**
     * 删除一条关系边(维护入口用)。
     *
     * <p>只允许删除与 scope 完全同范围的边:范围或共享级对不上时返回 false 而不是删除,
     * 防止维护入口误删其他用户/其他范围的关系。两端节点保留,由半衰任务兜底清理。</p>
     *
     * @return 是否真的删除了
     */
    boolean removeEdge(KgScope scope, Long edgeId);

    /** 清空范围内全部节点与边(遗忘/级联删除预留) */
    void deleteScope(KgScope scope);
}
