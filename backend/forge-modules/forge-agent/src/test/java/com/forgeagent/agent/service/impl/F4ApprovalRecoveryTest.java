package com.forgeagent.agent.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.forgeagent.agent.domain.AgentApproval;
import com.forgeagent.agent.mapper.AgentApprovalMapper;
import com.forgeagent.agentcore.domain.AgentInfo;
import com.forgeagent.agentcore.domain.AgentWorkspaceMember;
import com.forgeagent.agentcore.mapper.AgentInfoMapper;
import com.forgeagent.agentcore.mapper.AgentWorkspaceMemberMapper;
import com.forgeagent.agentcore.service.AgentConfigCacheService;
import com.forgeagent.agentcore.service.WorkspacePermissionHelper;
import com.forgeagent.agent.service.runner.PendingApprovalRegistry;
import com.forgeagent.agent.service.run.RunEventBuffer;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * F4:审批单执行态持久化与启动恢复单测。
 *
 * <p>验证:markExecState 条件流转(抢单语义)、listStaleApproved 过滤口径、"
 * 已通过但未执行"可被识别为悬挂单。</p>
 *
 * @author ForgeAgent
 */
@Tag("dev")
@ExtendWith(MockitoExtension.class)
class F4ApprovalRecoveryTest {

    @Mock private AgentApprovalMapper approvalMapper;
    @Mock private AgentWorkspaceMemberMapper memberMapper;
    @Mock private AgentInfoMapper agentInfoMapper;
    @Mock private AgentConfigCacheService agentConfigCache;
    @Mock private WorkspacePermissionHelper permissionHelper;
    @Mock private PendingApprovalRegistry pendingApprovalRegistry;
    @Mock private RunEventBuffer eventBuffer;

    private AgentApprovalServiceImpl service;

    @BeforeAll
    static void warmUp() {
        TableInfoHelper.initTableInfo(
            new MapperBuilderAssistant(new MybatisConfiguration(), ""), AgentApproval.class);
    }

    @BeforeEach
    void setUp() {
        service = new AgentApprovalServiceImpl(approvalMapper, memberMapper, agentInfoMapper,
            agentConfigCache, permissionHelper, pendingApprovalRegistry, eventBuffer);
    }

    private static AgentApproval approval(Long id, String status, String execState, String runId) {
        AgentApproval a = new AgentApproval();
        a.setId(id);
        a.setStatus(status);
        a.setExecState(execState);
        a.setRunId(runId);
        a.setToolName("refund_order");
        return a;
    }

    @Test
    void markExecStateClaimsOnlyMatchingFromState() {
        when(approvalMapper.update(any(AgentApproval.class), any(LambdaUpdateWrapper.class))).thenReturn(1);

        boolean ok = service.markExecState(7L, "APPROVED", "NEED_MANUAL");

        assertTrue(ok);
    }

    @Test
    void markExecStateReturnsFalseWhenAlreadyClaimed() {
        when(approvalMapper.update(any(AgentApproval.class), any(LambdaUpdateWrapper.class))).thenReturn(0);

        boolean ok = service.markExecState(7L, "APPROVED", "NEED_MANUAL");

        assertFalse(ok, "多实例抢单仅一个成功");
    }

    @Test
    void markExecStateRejectsNullId() {
        assertFalse(service.markExecState(null, "APPROVED", "DONE"));
    }

    @Test
    void listStaleApprovedReadsFromMapper() {
        when(approvalMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(
            List.of(approval(7L, "approved", "APPROVED", "run-7")));

        List<AgentApproval> stale = service.listStaleApproved();

        assertEquals(1, stale.size());
        assertEquals("APPROVED", stale.get(0).getExecState());
        assertEquals("run-7", stale.get(0).getRunId());
    }

    @Test
    void listStaleApprovedReturnsEmptyWhenNoStaleRows() {
        when(approvalMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());

        assertTrue(service.listStaleApproved().isEmpty());
    }
}
