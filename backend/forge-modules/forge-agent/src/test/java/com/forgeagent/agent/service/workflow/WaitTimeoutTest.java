package com.forgeagent.agent.service.workflow;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.forgeagent.agent.domain.AgentApproval;
import com.forgeagent.agent.domain.AgentWorkflowRun;
import com.forgeagent.agent.domain.vo.WorkflowTrialRunVo;
import com.forgeagent.agent.mapper.AgentApprovalMapper;
import com.forgeagent.agent.mapper.AgentWorkflowRunMapper;
import com.forgeagent.agent.service.impl.AgentWorkflowServiceImpl;
import com.forgeagent.agent.service.run.RunEventBuffer;
import com.forgeagent.common.core.exception.ServiceException;
import com.forgeagent.agentcore.mapper.AgentInfoMapper;
import com.forgeagent.agentcore.service.WorkspacePermissionHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * C1:wait 节点超时闭环单测。锁四件事:
 * ① executeWaitNode 在 timeoutMinutes>0 时写 deadline 到 vars;
 * ② snapshotRun 把 deadline 提到独立列且从 vars 清除内部键;
 * ③ sweepExpiredWaits 只扫到期 wait 且条件更新置 fail;
 * ④ resumeWaitRun 抢单失败(已被超时清扫)时拒绝唤醒。
 */
@Tag("dev")
@ExtendWith(MockitoExtension.class)
class WaitTimeoutTest {

    @Mock private AgentInfoMapper agentMapper;
    @Mock private AgentWorkflowRunMapper runMapper;
    @Mock private AgentApprovalMapper approvalMapper;
    @Mock private WorkspacePermissionHelper permissionHelper;
    @Mock private RunEventBuffer eventBuffer;

    private AgentWorkflowServiceImpl service;
    private WorkflowRunLauncher launcher;
    private WorkflowGraphEngine graphEngine;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final WorkflowTraceCodec codec = new WorkflowTraceCodec(new ObjectMapper());

    @BeforeAll
    static void warmUp() {
        MapperBuilderAssistant a = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(a, AgentWorkflowRun.class);
        TableInfoHelper.initTableInfo(a, AgentApproval.class);
    }

    @BeforeEach
    void setUp() {
        // 默认纯 mock:超时清扫只需验证 publishEvent 被调用,不能触发真实实现里的
        // JsonUtils 静态初始化(依赖 Spring 容器,纯单测会 ExceptionInInitializerError)。
        graphEngine = mock(WorkflowGraphEngine.class);
        launcher = new WorkflowRunLauncher(
            agentMapper, approvalMapper, runMapper, permissionHelper, eventBuffer, graphEngine);
        service = new AgentWorkflowServiceImpl(
            agentMapper, null, approvalMapper, runMapper, null, permissionHelper,
            null, null, eventBuffer, graphEngine, launcher);
    }

    /** 需要真实执行图的测试(deadline 落地)专用:真实引擎 + 真实 launcher/service。 */
    private void useRealEngineForExecution() {
        WorkflowNodeExecutors executors = new WorkflowNodeExecutors(
            null, approvalMapper, null, null, null, null, null, null, null);
        graphEngine = new WorkflowGraphEngine(executors, eventBuffer);
        launcher = new WorkflowRunLauncher(
            agentMapper, approvalMapper, runMapper, permissionHelper, eventBuffer, graphEngine);
        service = new AgentWorkflowServiceImpl(
            agentMapper, null, approvalMapper, runMapper, null, permissionHelper,
            null, null, eventBuffer, graphEngine, launcher);
    }

    @Test
    void executeWaitNodeWritesDeadlineIntoVars() throws Exception {
        useRealEngineForExecution();
        // 用最小图:wait 节点超时 60 分钟,通过 launcher 执行到暂停
        String graph = "{\"nodes\":["
            + "{\"id\":\"s1\",\"type\":\"start\",\"name\":\"开始\",\"config\":{}},"
            + "{\"id\":\"w1\",\"type\":\"wait\",\"name\":\"等待\",\"config\":{\"eventKey\":\"paid\",\"timeoutMinutes\":60}},"
            + "{\"id\":\"e1\",\"type\":\"end\",\"name\":\"结束\",\"config\":{\"output\":\"done\"}}"
            + "],\"edges\":["
            + "{\"id\":\"ed1\",\"source\":\"s1\",\"target\":\"w1\"},"
            + "{\"id\":\"ed2\",\"source\":\"w1\",\"target\":\"e1\"}]}";
        com.forgeagent.agentcore.domain.AgentInfo agent = new com.forgeagent.agentcore.domain.AgentInfo();
        agent.setId(1L); agent.setWorkspaceId(9L);
        lenient().when(agentMapper.selectById(1L)).thenReturn(agent);
        when(runMapper.insert(any(AgentWorkflowRun.class))).thenReturn(1);

        com.forgeagent.agent.domain.bo.WorkflowTrialRunBo bo =
            new com.forgeagent.agent.domain.bo.WorkflowTrialRunBo();
        bo.setAgentId(1L); bo.setWorkflowJson(graph); bo.setInputs("{}"); bo.setDryRun(false);
        try (org.mockito.MockedStatic<com.forgeagent.common.satoken.utils.LoginHelper> login =
                 Mockito.mockStatic(com.forgeagent.common.satoken.utils.LoginHelper.class)) {
            login.when(com.forgeagent.common.satoken.utils.LoginHelper::getUserId).thenReturn(42L);
            service.trialRun(bo);
        }

        // 抓快照 run:deadline 提到独立列,内部键从 vars 移除(约 60 分钟后)
        ArgumentCaptor<AgentWorkflowRun> cap = ArgumentCaptor.forClass(AgentWorkflowRun.class);
        verify(runMapper, atLeastOnce()).updateById(cap.capture());
        AgentWorkflowRun snap = cap.getValue();
        assertNotNull(snap.getPendingDeadline(), "wait 超时必须落 pending_deadline");
        assertTrue(snap.getPendingDeadline().isAfter(LocalDateTime.now().plusMinutes(59)));
        assertTrue(snap.getPendingDeadline().isBefore(LocalDateTime.now().plusMinutes(61)));
        assertFalse(snap.getVarsJson().contains(WorkflowNodeExecutors.WAIT_DEADLINE_VAR),
            "内部键不得污染 varsJson");
    }

    @Test
    void sweepExpiredWaitsClaimsAndFailsOnlyDueWaitRuns() {
        LocalDateTime overdue = LocalDateTime.now().minusMinutes(1);
        AgentWorkflowRun due = run(7001L, overdue);
        when(runMapper.selectList(any(com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper.class)))
            .thenReturn(List.of(due));
        when(runMapper.update(any(), any(com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper.class)))
            .thenReturn(1);
        when(runMapper.updateById(any(AgentWorkflowRun.class))).thenReturn(1);

        service.sweepExpiredWaits();

        // 条件更新置 fail;终态事件推送
        verify(runMapper).update(any(), any(com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper.class));
        verify(graphEngine).publishEvent(eq(7001L), eq("node_error"), any(Map.class));
        verify(graphEngine).publishEvent(eq(7001L), eq("done"), any(Map.class));
        verify(eventBuffer).terminal("wf-7001", "error");
    }

    @Test
    void sweepExpiredWaitsSkipsWhenClaimLost() {
        AgentWorkflowRun due = run(7002L, LocalDateTime.now().minusMinutes(1));
        when(runMapper.selectList(any(com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper.class)))
            .thenReturn(List.of(due));
        // 条件更新失败:已被 resumeWaitRun 抢先唤醒
        when(runMapper.update(any(), any(com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper.class)))
            .thenReturn(0);

        service.sweepExpiredWaits();

        verify(runMapper, never()).updateById(any(AgentWorkflowRun.class));
        verify(graphEngine, never()).publishEvent(eq(7002L), eq("done"), any(Map.class));
    }

    @Test
    void resumeWaitRunRejectsWhenClaimLostToTimeoutSweep() {
        AgentWorkflowRun stored = run(7003L, LocalDateTime.now().minusMinutes(1));
        when(runMapper.selectById(7003L)).thenReturn(stored);
        // 抢单失败(超时扫描已把 status 改 fail)
        when(runMapper.update(any(), any(com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper.class)))
            .thenReturn(0);

        ServiceException ex = assertThrows(ServiceException.class,
            () -> launcher.resumeWaitRun(7003L, "{\"x\":1}"));
        assertTrue(ex.getMessage().contains("已被处理"), ex.getMessage());
        verify(runMapper, never()).updateById(any(AgentWorkflowRun.class));
    }

    @Test
    void sweepResumesWithTimeoutPayloadInsteadOfFail() {
        // C1:wait 节点配置了 timeoutPayload 时,超时不再置 fail,而是注入 payload 续跑
        WorkflowRunLauncher mockLauncher = mock(WorkflowRunLauncher.class);
        AgentWorkflowServiceImpl svc = new AgentWorkflowServiceImpl(
            agentMapper, null, approvalMapper, runMapper, null, permissionHelper,
            null, null, eventBuffer, mock(WorkflowGraphEngine.class), mockLauncher);
        AgentWorkflowRun due = run(7004L, LocalDateTime.now().minusMinutes(1));
        due.setWorkflowJson("{\"nodes\":[{\"id\":\"w1\",\"type\":\"wait\",\"name\":\"等待\","
            + "\"config\":{\"eventKey\":\"paid\",\"timeoutMinutes\":60,"
            + "\"timeoutPayload\":\"{\\\"timedOut\\\":true}\"}}],\"edges\":[]}");
        when(runMapper.selectList(any(com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper.class)))
            .thenReturn(List.of(due));

        svc.sweepExpiredWaits();

        verify(mockLauncher).resumeWaitRun(7004L, "{\"timedOut\":true}");
        // payload 分支不落 fail(claim 发生在 resumeWaitRun 内部),也不推终态
        verify(runMapper, never()).update(any(), any(com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper.class));
        verify(eventBuffer, never()).terminal(anyString(), anyString());
    }

    @Test
    void sweepStillFailsRunWithoutTimeoutPayload() {
        // 未配置 timeoutPayload 的 wait 保持原超时终止口径
        WorkflowRunLauncher mockLauncher = mock(WorkflowRunLauncher.class);
        AgentWorkflowServiceImpl svc = new AgentWorkflowServiceImpl(
            agentMapper, null, approvalMapper, runMapper, null, permissionHelper,
            null, null, eventBuffer, mock(WorkflowGraphEngine.class), mockLauncher);
        AgentWorkflowRun due = run(7005L, LocalDateTime.now().minusMinutes(1));
        due.setWorkflowJson("{\"nodes\":[{\"id\":\"w1\",\"type\":\"wait\",\"name\":\"等待\","
            + "\"config\":{\"eventKey\":\"paid\",\"timeoutMinutes\":60}}],\"edges\":[]}");
        when(runMapper.selectList(any(com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper.class)))
            .thenReturn(List.of(due));
        when(runMapper.update(any(), any(com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper.class)))
            .thenReturn(1);
        when(runMapper.updateById(any(AgentWorkflowRun.class))).thenReturn(1);

        svc.sweepExpiredWaits();

        verify(mockLauncher, never()).resumeWaitRun(any(), any());
        verify(runMapper).update(any(), any(com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper.class));
    }

    private static AgentWorkflowRun run(Long id, LocalDateTime deadline) {
        AgentWorkflowRun r = new AgentWorkflowRun();
        r.setId(id);
        r.setAgentId(1L);
        r.setWorkspaceId(9L);
        r.setStatus("pending_approval");
        r.setPendingNode("w1");
        r.setApprovalId(null);
        r.setPendingDeadline(deadline);
        r.setInputs("{}");
        r.setTraceJson("[]");
        r.setVarsJson("{}");
        r.setCompletedJson("[]");
        r.setSkippedJson("[]");
        return r;
    }
}
