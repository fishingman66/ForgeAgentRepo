package com.forgeagent.agent.service;

import com.forgeagent.agent.domain.vo.AgentApprovalVo;
import com.forgeagent.common.core.domain.PageResult;
import com.forgeagent.common.mybatis.core.page.PageQuery;

/**
 * 工具审批单 Service(HITL 人工审批)
 *
 * @author ForgeAgent
 */
public interface IAgentApprovalService {

    /**
     * 创建待审批单,返回审批单ID
     */
    Long create(Long sessionId, Long agentId, String roundId, String toolName,
                String toolSource, String serverName, String callArgs);

    /**
     * 查询审批单当前状态(不存在返回 null)
     */
    String getStatus(Long id);

    /**
     * 分页查询审批单
     *
     * @param status  状态过滤(可空)
     * @param keyword 工具名关键字(可空)
     */
    PageResult<AgentApprovalVo> queryPageList(String status, String keyword, String toolSource, PageQuery pageQuery);

    /**
     * 审批处理(仅 pending 可操作)
     *
     * @param id      审批单ID
     * @param approve true通过 false拒绝
     * @param note    审批说明
     */
    Boolean handle(Long id, boolean approve, String note);

    /**
     * 取消会话全部待审批单(轮级中断时调用)
     *
     * @return 取消数量
     */
    int cancelPendingBySession(Long sessionId);

    /**
     * 标记审批单超时
     */
    void markExpired(Long id);

    /** F4:ask_async 创建审批单后落执行上下文(runId/actionLogId)。 */
    void markPendingExecution(Long id, String runId, Long actionLogId);

    /** F4一期:落工具重放快照(toolId + ToolDef JSON),供重启后按快照重建执行。 */
    void markReplaySnapshot(Long id, Long toolId, String toolDefJson);

    /** F4:执行态条件流转(仅当前为 fromState 时才置 toState),返回是否抢到。 */
    boolean markExecState(Long id, String fromState, String toState);

    /** F4:启动扫描已批准但未完成的审批单。 */
    java.util.List<com.forgeagent.agent.domain.AgentApproval> listStaleApproved();
}
