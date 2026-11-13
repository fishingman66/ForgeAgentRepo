package com.forgeagent.agent.service.impl;

import com.forgeagent.agent.domain.AgentApproval;
import com.forgeagent.agent.domain.AgentWorkflowRun;
import com.forgeagent.agent.domain.bo.WorkflowTrialRunBo;
import com.forgeagent.agent.domain.vo.WorkflowTrialRunVo;
import com.forgeagent.agent.mapper.AgentApprovalMapper;
import com.forgeagent.agent.mapper.AgentWorkflowRunMapper;
import com.forgeagent.agent.service.run.RunEventBuffer;
import com.forgeagent.agent.service.workflow.WorkflowGraphEngine;
import com.forgeagent.agent.service.workflow.WorkflowNodeExecutors;
import com.forgeagent.agent.service.workflow.WorkflowRunLauncher;
import com.forgeagent.agentcore.domain.AgentInfo;
import com.forgeagent.agentcore.mapper.AgentInfoMapper;
import com.forgeagent.agentcore.service.WorkspacePermissionHelper;
import com.forgeagent.common.core.exception.ServiceException;
import com.forgeagent.common.satoken.utils.LoginHelper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 工作流执行体的行为基线(表征测试)。
 *
 * <p>在拆分 {@link AgentWorkflowServiceImpl} 之前锁定图编排的可观察契约:
 * 顺序插值、dryRun 桩输出、条件选路、并行 JOIN、指定起跑节点、步数上限、
 * 审批暂停、静态校验拦截。全部走 {@code trialRun} 公共入口,不依赖任何私有方法,
 * 因此重构前后可直接复用同一套断言。</p>
 *
 * <p>外部节点一律 {@code dryRun=true},不连模型 / 知识库 / 数据库,毫秒级返回。</p>
 */
@Tag("dev")
@ExtendWith(MockitoExtension.class)
class AgentWorkflowGraphCharacterizationTest {

    @Mock
    private AgentInfoMapper agentMapper;
    @Mock
    private AgentWorkflowRunMapper runMapper;
    @Mock
    private AgentApprovalMapper approvalMapper;
    @Mock
    private WorkspacePermissionHelper permissionHelper;
    @Mock
    private RunEventBuffer eventBuffer;

    private AgentWorkflowServiceImpl service;

    /**
     * 显式构造而非 @InjectMocks:节点执行器必须是真实实例(dryRun 下它负责
     * start/end/condition/join/approval 与桩输出),mock 掉会让基线测试失去意义。
     * 外部依赖在 dryRun 路径上不会被触达,故传 null;approvalMapper 是唯一例外。
     */
    @BeforeEach
    void setUp() {
        AgentInfo agent = new AgentInfo();
        agent.setId(1001L);
        agent.setWorkspaceId(900L);
        when(agentMapper.selectById(1001L)).thenReturn(agent);

        WorkflowNodeExecutors nodeExecutors = new WorkflowNodeExecutors(
            null, approvalMapper, null, null, null, null, null, null, null);
        WorkflowGraphEngine graphEngine = new WorkflowGraphEngine(nodeExecutors, eventBuffer);
        WorkflowRunLauncher launcher = new WorkflowRunLauncher(
            agentMapper, approvalMapper, runMapper, permissionHelper, eventBuffer, graphEngine);
        service = new AgentWorkflowServiceImpl(
            agentMapper, null, approvalMapper, runMapper, null, permissionHelper,
            null, null, eventBuffer, graphEngine, launcher);
    }

    // ------------------------------------------------------------ 图夹具

    private static String node(String id, String type, String name, String config) {
        return "{\"id\":\"" + id + "\",\"type\":\"" + type + "\",\"name\":\"" + name
            + "\",\"config\":" + config + "}";
    }

    private static String edge(String id, String source, String target) {
        return edge(id, source, target, null);
    }

    private static String edge(String id, String source, String target, String branchName) {
        return "{\"id\":\"" + id + "\",\"source\":\"" + source + "\",\"target\":\"" + target
            + "\"" + (branchName == null ? "" : ",\"branchName\":\"" + branchName + "\"") + "}";
    }

    private static String graph(List<String> nodes, List<String> edges) {
        return "{\"nodes\":[" + String.join(",", nodes) + "],\"edges\":["
            + String.join(",", edges) + "]}";
    }

    private static final String LLM_CONFIG = "{\"modelId\":1,\"prompt\":\"hi\"}";

    private WorkflowTrialRunVo run(String graphJson, String inputs, boolean dryRun,
                                    String startNodeKey, Integer maxSteps) {
        WorkflowTrialRunBo bo = new WorkflowTrialRunBo();
        bo.setAgentId(1001L);
        bo.setWorkflowJson(graphJson);
        bo.setInputs(inputs);
        bo.setDryRun(dryRun);
        bo.setStartNodeKey(startNodeKey);
        bo.setMaxSteps(maxSteps);
        try (MockedStatic<LoginHelper> login = Mockito.mockStatic(LoginHelper.class)) {
            login.when(LoginHelper::getUserId).thenReturn(42L);
            return service.trialRun(bo);
        }
    }

    private static WorkflowTrialRunVo.NodeTrace traceOf(WorkflowTrialRunVo vo, String nodeId) {
        return vo.getTrace().stream().filter(t -> nodeId.equals(t.getNodeId()))
            .findFirst().orElseThrow(() -> new AssertionError("轨迹缺少节点 " + nodeId));
    }

    // ------------------------------------------------------------ 契约

    @Test
    void sequentialGraphRunsStartAndEndAndInterpolatesVariables() {
        String g = graph(
            List.of(node("s1", "start", "开始", "{}"),
                node("e1", "end", "结束", "{\"output\":\"结果={{start.topic}}\"}")),
            List.of(edge("ed1", "s1", "e1")));

        WorkflowTrialRunVo vo = run(g, "{\"topic\":\"hello\"}", false, null, null);

        assertEquals("success", vo.getStatus());
        assertEquals(2, vo.getTrace().size());
        assertEquals("结果=hello", traceOf(vo, "e1").getOutput());
    }

    @Test
    void dryRunReturnsStubForExternalNodesWithoutCallingModel() {
        String g = graph(
            List.of(node("s1", "start", "开始", "{}"),
                node("m1", "llm", "大模型", LLM_CONFIG),
                node("e1", "end", "结束", "{\"output\":\"done\"}")),
            List.of(edge("ed1", "s1", "m1"), edge("ed2", "m1", "e1")));

        WorkflowTrialRunVo vo = run(g, "{}", true, null, null);

        assertEquals("success", vo.getStatus());
        assertTrue(traceOf(vo, "m1").getOutput().contains("dryRun 桩输出:llm"));
        assertEquals("success", traceOf(vo, "m1").getStatus());
    }

    @Test
    void conditionSelectsNamedBranchAndSkipsOtherPath() {
        String branches = "[{\"name\":\"yes\",\"condition\":\"{{start.flag}} == yes\"},"
            + "{\"name\":\"no\",\"condition\":\"{{start.flag}} == no\"}]";
        String g = graph(
            List.of(node("s1", "start", "开始", "{}"),
                node("c1", "condition", "判断", "{\"branches\":" + branches + "}"),
                node("a1", "llm", "分支甲", LLM_CONFIG),
                node("a2", "llm", "分支乙", LLM_CONFIG),
                node("e1", "end", "结束", "{\"output\":\"done\"}")),
            List.of(edge("ed1", "s1", "c1"),
                edge("ed2", "c1", "a1", "yes"), edge("ed3", "c1", "a2", "no"),
                edge("ed4", "a1", "e1"), edge("ed5", "a2", "e1")));

        WorkflowTrialRunVo vo = run(g, "{\"flag\":\"yes\"}", true, null, null);

        assertEquals("success", vo.getStatus());
        assertEquals("success", traceOf(vo, "a1").getStatus());
        assertEquals("skipped", traceOf(vo, "a2").getStatus());
        assertEquals("done", traceOf(vo, "e1").getOutput());
    }

    @Test
    void parallelBranchesJoinDeterministically() {
        String g = graph(
            List.of(node("s1", "start", "开始", "{}"),
                node("p1", "parallel", "并行", "{}"),
                node("b1", "llm", "分支一", LLM_CONFIG),
                node("b2", "llm", "分支二", LLM_CONFIG),
                node("j1", "join", "汇聚", "{}"),
                node("e1", "end", "结束", "{\"output\":\"joined\"}")),
            List.of(edge("ed1", "s1", "p1"),
                edge("ed2", "p1", "b1"), edge("ed3", "p1", "b2"),
                edge("ed4", "b1", "j1"), edge("ed5", "b2", "j1"),
                edge("ed6", "j1", "e1")));

        WorkflowTrialRunVo vo = run(g, "{}", true, null, null);

        assertEquals("success", vo.getStatus());
        assertEquals("并行分支已汇合", traceOf(vo, "j1").getOutput());
        assertTrue(traceOf(vo, "p1").getOutput().contains("2 条分支并行完成"));
        assertEquals("joined", traceOf(vo, "e1").getOutput());
    }

    @Test
    void startNodeKeySkipsUpstreamNodes() {
        String g = graph(
            List.of(node("s1", "start", "开始", "{}"),
                node("m1", "llm", "大模型", LLM_CONFIG),
                node("e1", "end", "结束", "{\"output\":\"done\"}")),
            List.of(edge("ed1", "s1", "m1"), edge("ed2", "m1", "e1")));

        WorkflowTrialRunVo vo = run(g, "{\"topic\":\"hello\"}", true, "m1", null);

        assertEquals("success", vo.getStatus());
        assertTrue(vo.getTrace().stream().noneMatch(t -> "s1".equals(t.getNodeId())));
        assertEquals(2, vo.getTrace().size());
    }

    @Test
    void maxStepsGuardFailsRun() {
        String g = graph(
            List.of(node("s1", "start", "开始", "{}"),
                node("e1", "end", "结束", "{\"output\":\"done\"}")),
            List.of(edge("ed1", "s1", "e1")));

        WorkflowTrialRunVo vo = run(g, "{}", false, null, 1);

        assertEquals("fail", vo.getStatus());
        assertTrue(vo.getMessage().contains("步数超过上限"));
    }

    @Test
    void approvalNodePausesRunWithApprovalId() {
        when(approvalMapper.insert(any(AgentApproval.class))).thenAnswer(inv -> {
            ((AgentApproval) inv.getArgument(0)).setId(7001L);
            return 1;
        });
        when(runMapper.insert(any(AgentWorkflowRun.class))).thenReturn(1);
        String g = graph(
            List.of(node("s1", "start", "开始", "{}"),
                node("a1", "approval", "人工审批", "{\"message\":\"请确认\"}"),
                node("e1", "end", "结束", "{\"output\":\"done\"}")),
            List.of(edge("ed1", "s1", "a1"), edge("ed2", "a1", "e1")));

        WorkflowTrialRunVo vo = run(g, "{}", true, null, null);

        assertEquals("pending_approval", vo.getStatus());
        assertEquals(7001L, vo.getApprovalId());
        assertEquals("pending", traceOf(vo, "a1").getStatus());
    }

    @Test
    void invalidGraphIsRejectedBeforeAnyRun() {
        ServiceException ex = assertThrows(ServiceException.class,
            () -> run("{\"nodes\":[],\"edges\":[]}", "{}", true, null, null));

        assertTrue(ex.getMessage().contains("工作流校验未通过"));
    }
}
