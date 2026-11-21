package com.forgeagent.agent.service.support;

import com.forgeagent.agent.domain.AgentApproval;
import com.forgeagent.agent.service.IAgentApprovalService;
import com.forgeagent.agent.service.run.RunEventBuffer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.scheduling.annotation.Scheduled;

import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * F4:悬挂审批单周期扫描单测。锁两件事:
 * ① scheduledRecover 带 @Scheduled(覆盖"崩在 REPLAYING 需等重启");
 * ② 刚批准的审批单在保护窗口内不被抢走(防重复执行在途工具),超窗的才被恢复。
 */
@Tag("dev")
@ExtendWith(MockitoExtension.class)
class StaleApprovalRecoverySweepTest {

    @Mock private IAgentApprovalService approvalService;
    @Mock private ApprovalReplayService replayService;
    @Mock private RunEventBuffer eventBuffer;

    private StaleApprovalRecoveryRunner runner;

    @BeforeEach
    void setUp() {
        runner = new StaleApprovalRecoveryRunner(approvalService, replayService, eventBuffer);
    }

    @Test
    void scheduledRecoverCarriesScheduledAnnotation() throws Exception {
        var m = StaleApprovalRecoveryRunner.class.getMethod("scheduledRecover");
        assertNotNull(m.getAnnotation(Scheduled.class),
            "周期兜底必须带 @Scheduled,否则崩在 REPLAYING 仍要等重启");
    }

    @Test
    void freshApprovedSkippedWithinGraceWindow() {
        AgentApproval fresh = approval(1L, new Date()); // 刚批准
        when(approvalService.listStaleApproved()).thenReturn(List.of(fresh));

        runner.recover();

        verify(approvalService, never()).markExecState(any(), any(), any());
        verify(replayService, never()).replay(any());
    }

    @Test
    void oldApprovedBeyondGraceWindowIsReplayed() {
        AgentApproval old = approval(2L,
            new Date(System.currentTimeMillis() - StaleApprovalRecoveryRunner.IN_FLIGHT_GRACE_MS - 10_000L));
        when(approvalService.listStaleApproved()).thenReturn(List.of(old));
        when(approvalService.markExecState(2L, "APPROVED", "REPLAYING")).thenReturn(true);
        when(replayService.replay(old)).thenReturn(
            new ApprovalReplayService.ReplayResult(true, true, "done"));

        runner.recover();

        verify(replayService).replay(old);
        verify(approvalService).markExecState(2L, "REPLAYING", "DONE");
    }

    @Test
    void nullApproveTimeTreatedAsStaleForLegacyRows() {
        AgentApproval legacy = approval(3L, null); // 老数据无 approveTime
        when(approvalService.listStaleApproved()).thenReturn(List.of(legacy));
        when(approvalService.markExecState(3L, "APPROVED", "REPLAYING")).thenReturn(true);
        when(replayService.replay(legacy)).thenReturn(
            new ApprovalReplayService.ReplayResult(false, false, "无快照"));

        runner.recover();

        verify(replayService).replay(legacy);
        verify(approvalService).markExecState(3L, "REPLAYING", "NEED_MANUAL");
    }

    private static AgentApproval approval(Long id, Date approveTime) {
        AgentApproval a = new AgentApproval();
        a.setId(id);
        a.setStatus("approved");
        a.setExecState("APPROVED");
        a.setRunId("run-" + id);
        a.setToolName("refund");
        a.setApproveTime(approveTime);
        return a;
    }
}
