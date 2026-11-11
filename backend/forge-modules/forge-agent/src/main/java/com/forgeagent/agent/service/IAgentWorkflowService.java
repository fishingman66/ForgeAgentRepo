package com.forgeagent.agent.service;

import com.forgeagent.agent.domain.bo.WorkflowGraphBo;
import com.forgeagent.agent.domain.bo.WorkflowTrialRunBo;
import com.forgeagent.agent.domain.vo.AgentWorkflowRunVo;
import com.forgeagent.agent.domain.vo.AgentWorkflowVersionVo;
import com.forgeagent.agent.domain.vo.WorkflowRunDetailVo;
import com.forgeagent.agent.domain.vo.WorkflowTrialRunVo;
import com.forgeagent.agent.domain.vo.WorkflowValidateVo;
import com.forgeagent.common.core.domain.PageResult;
import com.forgeagent.common.mybatis.core.page.PageQuery;

import java.util.List;

/**
 * 工作流编排 Service
 *
 * @author ForgeAgent
 */
public interface IAgentWorkflowService {

    /**
     * 保存工作流图(同时生成递增版本快照)
     *
     * @return 新版本号
     */
    Long saveWorkflow(WorkflowGraphBo bo);

    /**
     * 校验工作流图
     */
    WorkflowValidateVo validateWorkflow(String workflowJson);

    /**
     * 试运行工作流(执行过程全量落库,支持事后回放)
     */
    WorkflowTrialRunVo trialRun(WorkflowTrialRunBo bo);

    /**
     * W9:异步试运行:先落运行实例并立即返回 runId,后台线程执行;
     * 进度经 GET /run/{runId}/events 的 SSE 逐节点推送
     *
     * @return 运行实例 ID
     */
    Long trialRunAsync(WorkflowTrialRunBo bo);

    /**
     * W9:订阅试运行执行事件(先重放 after 之后的事件,再跟随至终态自动收口)
     *
     * @param runId 运行实例 ID
     * @param after 已消费的最大事件序号(0 从头开始)
     */
    org.springframework.web.servlet.mvc.method.annotation.SseEmitter runEvents(Long runId, Long after);

    /**
     * W10:聊天链路异步执行工作流(无权限校验——聊天侧已完成会话归属校验;
     * 后台线程无登录上下文,knowledge 节点走内部检索入口,与触发器同口径)
     *
     * @param agentId      目标 AgentID
     * @param workflowJson 工作流图 JSON(Agent 当前草稿)
     * @param inputs       输入变量 JSON(约定 {"query": 用户消息})
     * @param userId       会话归属人
     * @return 运行实例 ID(事件经 RunEventBuffer 键 "wf-{runId}" 推送)
     */
    Long startRunForChat(Long agentId, String workflowJson, String inputs, Long userId);

    /**
     * W10:内部查询运行结果(无权限校验,仅供聊天链路在终态后取回复;
     * 返回 trace / status / message,不含图快照大字段)
     */
    WorkflowTrialRunVo runResultInternal(Long runId);

    /**
     * 触发器执行工作流(无登录上下文场景):以指定用户身份执行目标 Agent 的当前工作流,
     * 不做空间权限校验,行为与试运行一致。
     *
     * @param agentId        目标 AgentID
     * @param inputs         输入变量 JSON
     * @param operatorUserId 操作人用户ID(触发器归属人)
     */
    WorkflowTrialRunVo triggerRun(Long agentId, String inputs, Long operatorUserId);

    /**
     * G3:带幂等键的触发器执行。key 命中终态 run 直接复用其结果;
     * 命中 running run 返回去重提示;未命中正常执行(唯一索引兜底并发)。
     *
     * @param idempotencyKey 幂等键(cron=triggerId+调度分钟;webhook=请求头 Idempotency-Key;null 不去重)
     */
    WorkflowTrialRunVo triggerRun(Long agentId, String inputs, Long operatorUserId, String idempotencyKey);

    /**
     * 审批决策后恢复工作流运行(审批中心处理联动入口,不修改审批单状态)
     *
     * @param runId    运行实例ID
     * @param approved true 通过(从审批节点继续执行)/false 拒绝(运行标记失败)
     */
    WorkflowTrialRunVo resumeRun(Long runId, boolean approved);

    /**
     * 直接审批决策:更新关联审批单状态后恢复运行(工作流页快捷操作入口)
     */
    WorkflowTrialRunVo respondRun(Long runId, boolean approved);

    /**
     * 查询 Agent 的版本历史(倒序,不含图快照大字段)
     */
    List<AgentWorkflowVersionVo> listVersion(Long agentId);

    /**
     * 查询版本详情(含工作流图快照)
     */
    AgentWorkflowVersionVo getVersion(Long versionId);

    /**
     * 回滚到指定版本:图快照写回草稿并生成新版本
     *
     * @return 新版本号
     */
    Long rollbackVersion(Long versionId);

    /**
     * 分页查询运行历史(倒序)
     */
    PageResult<AgentWorkflowRunVo> listRun(Long agentId, PageQuery pageQuery);

    /**
     * 查询运行回放详情(图快照/输入/变量/节点轨迹)
     */
    WorkflowRunDetailVo getRunDetail(Long runId);
}
