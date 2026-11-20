package com.forgeagent.agent.service.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.forgeagent.agent.domain.AgentApproval;
import com.forgeagent.agent.domain.AgentWorkflowRun;
import com.forgeagent.agent.domain.bo.WorkflowTrialRunBo;
import com.forgeagent.agent.domain.vo.WorkflowTrialRunVo;
import com.forgeagent.agent.domain.vo.WorkflowValidateVo;
import com.forgeagent.agent.mapper.AgentApprovalMapper;
import com.forgeagent.agent.mapper.AgentWorkflowRunMapper;
import com.forgeagent.agent.service.impl.AgentWorkflowServiceImpl;
import com.forgeagent.agent.service.run.RunEventBuffer;
import com.forgeagent.agentcore.domain.AgentInfo;
import com.forgeagent.agentcore.mapper.AgentInfoMapper;
import com.forgeagent.agentcore.service.WorkspacePermissionHelper;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * C1 暂停类节点(form / wait / human_transfer)契约测试。
 *
 * <p>锁三件事:表单与等待节点暂停时<b>不生成审批单</b>(approvalId 为 null)、
 * 转人工节点缺少会话时 <b>fail-closed</b> 不产出假工单、等待节点可经 payload 唤醒续跑。</p>
 */
@Tag("dev")
@ExtendWith(MockitoExtension.class)
class WorkflowPauseNodesTest {

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
    private WorkflowRunLauncher launcher;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final WorkflowGraphValidator validator = new WorkflowGraphValidator(new ObjectMapper());
    private final WorkflowTraceCodec codec = new WorkflowTraceCodec(new ObjectMapper());

    @BeforeEach
    void setUp() {
        AgentInfo agent = new AgentInfo();
        agent.setId(1001L);
        agent.setWorkspaceId(900L);
        // 校验器用例不触达 mapper,统一宽松打桩避免 UnnecessaryStubbing
        org.mockito.Mockito.lenient().when(agentMapper.selectById(1001L)).thenReturn(agent);

        WorkflowNodeExecutors nodeExecutors = new WorkflowNodeExecutors(
            null, approvalMapper, null, null, null, null, null, null, null);
        WorkflowGraphEngine graphEngine = new WorkflowGraphEngine(nodeExecutors, eventBuffer);
        launcher = new WorkflowRunLauncher(
            agentMapper, approvalMapper, runMapper, permissionHelper, eventBuffer, graphEngine);
        service = new AgentWorkflowServiceImpl(
            agentMapper, null, approvalMapper, runMapper, null, permissionHelper,
            null, null, eventBuffer, graphEngine, launcher);
    }

    // ------------------------------------------------------------ 夹具

    private static String node(String id, String type, String name, String config) {
        return "{\"id\":\"" + id + "\",\"type\":\"" + type + "\",\"name\":\"" + name
            + "\",\"config\":" + config + "}";
    }

    private static String edge(String id, String source, String target) {
        return "{\"id\":\"" + id + "\",\"source\":\"" + source + "\",\"target\":\"" + target + "\"}";
    }

    private static String graph(List<String> nodes, List<String> edges) {
        return "{\"nodes\":[" + String.join(",", nodes) + "],\"edges\":["
            + String.join(",", edges) + "]}";
    }

    private WorkflowTrialRunVo run(String graphJson, String inputs, boolean dryRun) {
        org.mockito.Mockito.lenient().when(runMapper.insert(any(AgentWorkflowRun.class))).thenReturn(1);
        WorkflowTrialRunBo bo = new WorkflowTrialRunBo();
        bo.setAgentId(1001L);
        bo.setWorkflowJson(graphJson);
        bo.setInputs(inputs);
        bo.setDryRun(dryRun);
        try (MockedStatic<LoginHelper> login = Mockito.mockStatic(LoginHelper.class)) {
            login.when(LoginHelper::getUserId).thenReturn(42L);
            return service.trialRun(bo);
        }
    }

    private static WorkflowTrialRunVo.NodeTrace traceOf(WorkflowTrialRunVo vo, String nodeId) {
        return vo.getTrace().stream().filter(t -> nodeId.equals(t.getNodeId())).findFirst().orElseThrow();
    }

    private String threeNodeGraph(String midType, String midName, String midConfig) {
        return graph(
            List.of(node("s1", "start", "开始", "{}"),
                node("m1", midType, midName, midConfig),
                node("e1", "end", "结束", "{\"output\":\"done\"}")),
            List.of(edge("ed1", "s1", "m1"), edge("ed2", "m1", "e1")));
    }

    // ------------------------------------------------------------ 用例

    @Test
    void formNodePausesWithoutApproval() {
        String g = threeNodeGraph("form", "表单收集_1",
            "{\"formTitle\":\"补充信息\",\"fields\":[{\"name\":\"orderNo\",\"required\":true}]}");

        WorkflowTrialRunVo vo = run(g, "{}", true);

        assertEquals("pending_approval", vo.getStatus());
        assertNull(vo.getApprovalId(), "表单节点不应生成审批单");
        assertEquals("pending", traceOf(vo, "m1").getStatus());
        verify(approvalMapper, never()).insert(any(AgentApproval.class));
    }

    @Test
    void waitNodePausesWithoutApproval() {
        String g = threeNodeGraph("wait", "等待事件_1", "{\"eventKey\":\"order_paid\"}");

        WorkflowTrialRunVo vo = run(g, "{}", true);

        assertEquals("pending_approval", vo.getStatus());
        assertNull(vo.getApprovalId());
        assertEquals("pending", traceOf(vo, "m1").getStatus());
        verify(approvalMapper, never()).insert(any(AgentApproval.class));
    }

    @Test
    void humanTransferFailsClosedWithoutConversationId() {
        String g = threeNodeGraph("human_transfer", "转人工_1", "{\"reason\":\"客户要求\"}");

        WorkflowTrialRunVo vo = run(g, "{}", false);

        assertEquals("fail", vo.getStatus());
        assertTrue(vo.getMessage().contains("conversationId"),
            "缺少会话时必须 fail-closed,实际=" + vo.getMessage());
    }

    @Test
    void resumeWaitRunWritesPayloadIntoNodeOutput() throws Exception {
        String g = threeNodeGraph("form", "表单收集_1",
            "{\"fields\":[{\"name\":\"orderNo\"}]}");
        WorkflowTrialRunVo.NodeTrace pending = new WorkflowTrialRunVo.NodeTrace();
        pending.setNodeId("m1");
        pending.setNodeName("表单收集_1");
        pending.setNodeType("form");
        pending.setStatus("pending");

        AgentWorkflowRun stored = new AgentWorkflowRun();
        // id 留空:发布事件在 runId 为 null 时直接短路,避免 JsonUtils 静态初始化依赖 Spring 上下文
        stored.setAgentId(1001L);
        stored.setWorkspaceId(900L);
        stored.setWorkflowJson(g);
        stored.setInputs("{}");
        stored.setStatus("pending_approval");
        stored.setPendingNode("m1");
        stored.setTraceJson(codec.compactTraceJson(List.of(pending)));
        stored.setVarsJson("{}");
        stored.setCompletedJson("[]");
        stored.setSkippedJson("[]");
        when(runMapper.selectById(9001L)).thenReturn(stored);
        when(runMapper.updateById(any(AgentWorkflowRun.class))).thenReturn(1);

        WorkflowTrialRunVo vo = launcher.resumeWaitRun(9001L, "{\"orderNo\":\"A100\"}");

        assertEquals("success", vo.getStatus(), "唤醒后应续跑至结束");
        assertTrue(stored.getVarsJson().contains("表单收集_1.output"),
            "payload 必须写入等待节点输出,实际=" + stored.getVarsJson());
        assertTrue(stored.getVarsJson().contains("A100"));
    }

    @Test
    void resumeWaitRunRejectsRunNotPending() {
        AgentWorkflowRun stored = new AgentWorkflowRun();
        stored.setId(9002L);
        stored.setStatus("success");
        when(runMapper.selectById(9002L)).thenReturn(stored);

        assertTrue(org.junit.jupiter.api.Assertions.assertThrows(
            com.forgeagent.common.core.exception.ServiceException.class,
            () -> launcher.resumeWaitRun(9002L, "{}")).getMessage().contains("不在等待状态"));
    }

    // ------------------------------------------------------------ 校验器

    @Test
    void validatorAcceptsValidPauseNodes() {
        WorkflowValidateVo formVo = validator.validateWorkflow(threeNodeGraph("form", "表单收集_1",
            "{\"fields\":[{\"name\":\"orderNo\"}]}"));
        WorkflowValidateVo waitVo = validator.validateWorkflow(threeNodeGraph("wait", "等待事件_1",
            "{\"eventKey\":\"order_paid\"}"));
        WorkflowValidateVo transferVo = validator.validateWorkflow(threeNodeGraph("human_transfer", "转人工_1",
            "{\"reason\":\"客户要求\"}"));

        assertTrue(formVo.getBlockers().isEmpty(), "合法表单节点不应有 blocker:" + formVo.getBlockers());
        assertTrue(waitVo.getBlockers().isEmpty(), "合法等待节点不应有 blocker:" + waitVo.getBlockers());
        assertTrue(transferVo.getBlockers().isEmpty(), "合法转人工节点不应有 blocker:" + transferVo.getBlockers());
    }

    @Test
    void validatorRejectsFormWithoutFields() {
        WorkflowValidateVo vo = validator.validateWorkflow(threeNodeGraph("form", "表单收集_1", "{}"));
        assertTrue(vo.getBlockers().stream().anyMatch(b -> b.getMessage().contains("至少一个字段")));
    }

    @Test
    void validatorRejectsWaitWithoutEventKey() {
        WorkflowValidateVo vo = validator.validateWorkflow(threeNodeGraph("wait", "等待事件_1", "{}"));
        assertTrue(vo.getBlockers().stream().anyMatch(b -> b.getMessage().contains("eventKey")));
    }
}
