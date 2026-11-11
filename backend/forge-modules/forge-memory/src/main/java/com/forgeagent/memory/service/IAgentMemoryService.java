package com.forgeagent.memory.service;

import com.forgeagent.memory.domain.bo.AgentMemoryBo;
import com.forgeagent.memory.domain.vo.AgentMemoryVo;
import com.forgeagent.common.core.domain.PageResult;
import com.forgeagent.common.mybatis.core.page.PageQuery;

import java.util.List;

/**
 * Agent 长期记忆 Service
 *
 * @author ForgeAgent
 */
public interface IAgentMemoryService {

    /**
     * 分页查询记忆
     *
     * @param agentId AgentID(必填)
     * @param keyword 内容关键字(可空)
     */
    PageResult<AgentMemoryVo> queryPageList(Long agentId, String keyword, PageQuery pageQuery);

    /**
     * 手动新增记忆
     */
    Boolean insertByBo(AgentMemoryBo bo);

    /**
     * 批量删除记忆
     */
    Boolean deleteWithValidByIds(List<Long> ids);

    /**
     * 清空 Agent 全部记忆
     */
    Boolean clearByAgent(Long agentId);

    /**
     * 构建注入提示词的记忆块(兼容无查询文本的调用方,走纯评分召回)
     */
    default String buildMemoryBlock(Long agentId, Long userId) {
        return buildMemoryBlock(agentId, userId, null);
    }

    /**
     * 构建注入提示词的记忆块(空记忆返回空串)
     *
     * <p>注入格式: "[长期记忆]\n1. xxx\n2. yyy"。开启 {@code memory.vector.enabled}
     * 且 query 非空时,先做语义召回再与评分召回融合;向量链路任何失败都自动回退评分召回。</p>
     *
     * @param query 本轮用户问题,用于语义召回;为空则只走评分
     */
    String buildMemoryBlock(Long agentId, Long userId, String query);

    /**
     * 预览注入给模型的记忆块(调试/运维用)。
     *
     * <p>返回内容与对话时真实注入的一致,含图谱「关联背景」,用于排查
     * 「图谱是否召回到」「预算是否把记忆裁掉了」这类问题,避免只能靠猜。</p>
     */
    String previewMemoryBlock(Long agentId, Long userId, String query);

    /**
     * 对话后异步提取长期记忆(静默失败,不阻断主流程)
     *
     * @param agentId     AgentID
     * @param workspaceId 工作空间ID
     * @param userId      用户ID
     * @param sessionId   会话ID
     */
    void extractAsync(Long agentId, Long workspaceId, Long userId, Long sessionId,
                      String userContent, String assistantContent);

    /**
     * 异步补建知识图谱:把已有记忆分批过一遍三元组抽取并落图。
     *
     * <p>会真实消耗模型调用(每批一次),只做手动触发。图谱未启用时直接报错,
     * 不做「静默什么也没发生」的假成功。</p>
     *
     * @param agentId AgentID
     * @param userId  用户ID,为空表示只补建平台级记忆
     * @return 是否已投递到异步线程
     */
    Boolean rebuildGraphAsync(Long agentId, Long userId);

    /**
     * 语义检索(记忆管理页用):向量可用走语义召回,不可用回退评分排序。
     *
     * @param limit 返回条数,1~20
     */
    List<AgentMemoryVo> searchMemories(Long agentId, Long userId, String query, Integer limit);

    /** 用户级遗忘的图谱侧量:范围内实体 TOP(记忆管理页图谱 tab 用) */
    List<com.forgeagent.kg.service.KgNode> graphNodes(Long agentId, Long userId, Integer limit);

    /** 范围内关系边(按权重降序,记忆管理页图谱 tab 用) */
    List<com.forgeagent.kg.service.KgEdge> graphEdges(Long agentId, Long userId, Integer limit);

    /**
     * 手动触发一次记忆治理(消息归档 + TTL + 配额),返回各步处理量。
     * 治理未启用(memory.governance.enabled=false)时直接报错。
     */
    java.util.Map<String, Long> runGovernance();

    /**
     * 用户级遗忘(L5):级联删除该用户在指定 Agent 下的
     * 记忆(含向量) → 图谱节点边 → 两张归档表的归档行。
     *
     * <p>confirm 必须等于 {@code FORGET},防误触;平台级记忆不在遗忘范围。</p>
     *
     * @return 实际删除的记忆条数
     */
    long forgetUser(Long agentId, Long userId, String confirm);
}
