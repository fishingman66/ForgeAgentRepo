package com.forgeagent.agent.service.support;

import com.forgeagent.agent.domain.AgentApproval;
import com.forgeagent.agent.service.IAgentApprovalService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.Date;

/**
 * F4一期:扫描"已批准但未完成"的异步工具审批单并重建执行。
 *
 * <p>跨实例/重启后进程内 PendingApprovalRegistry 丢失,但 A 档(http/sandbox)工具的
 * ToolDef 快照与 input 已落审批单,可按 WorkflowNodeExecutors 的原型重建执行。
 * 抢单用 exec_state 条件更新(APPROVED→REPLAYING),多实例仅一个执行;
 * 无快照 / B/C 档工具 / 重建失败则退化 NEED_MANUAL + 回执 + 告警,绝不静默丢弃。</p>
 *
 * <p>触发两点:① 启动时 ApplicationRunner 首扫(覆盖重启残留);
 * ② 周期 @Scheduled(覆盖"重放到一半崩在 REPLAYING、要等下次重启"的悬挂)。
 * 两点共用 recover(),并发由 markExecState 条件更新互斥,不会重复执行。</p>
 */
@Slf4j
@Component
@Order(100)
@RequiredArgsConstructor
public class StaleApprovalRecoveryRunner implements ApplicationRunner {

    /** 周期扫描间隔(ms):与审批超时清扫同频。 */
    static final long SWEEP_INTERVAL_MS = 300_000L;
    /**
     * 在途保护窗口(ms):审批通过后该窗口内 exec_state 仍可能是 APPROVED,
     * 但 in-process 快路径正在执行,周期扫描不得抢走重复执行。窗口取略大于周期。
     */
    static final long IN_FLIGHT_GRACE_MS = 6 * 60_000L;

    private final IAgentApprovalService approvalService;
    private final ApprovalReplayService replayService;
    private final com.forgeagent.agent.service.run.RunEventBuffer eventBuffer;

    @Override
    public void run(ApplicationArguments args) {
        recover();
    }

    /** 周期兜底:启动首扫之后,定期重扫崩在 REPLAYING 的悬挂单。 */
    @org.springframework.scheduling.annotation.Scheduled(fixedDelay = SWEEP_INTERVAL_MS, initialDelay = 60_000L)
    public void scheduledRecover() {
        recover();
    }

    /** 扫描并恢复悬挂审批单;任何异常都不阻断调用方(启动/调度)。 */
    void recover() {
        try {
            java.util.List<AgentApproval> stale = approvalService.listStaleApproved();
            if (stale == null || stale.isEmpty()) {
                return;
            }
            long graceCutoff = System.currentTimeMillis() - IN_FLIGHT_GRACE_MS;
            for (AgentApproval one : stale) {
                if (!pastGraceWindow(one, graceCutoff)) {
                    continue; // 刚批准、in-process 快路径可能仍在执行,跳过避免重复
                }
                // 多实例抢单:仅一个把 APPROVED 推进到 REPLAYING;
                // 若上次重放到一半崩溃卡在 REPLAYING,允许 REPLAYING→REPLAYING 重入抢回
                boolean claimed = approvalService.markExecState(one.getId(), "APPROVED", "REPLAYING")
                    || approvalService.markExecState(one.getId(), "REPLAYING", "REPLAYING");
                if (!claimed) {
                    continue;
                }
                ApprovalReplayService.ReplayResult result = replayService.replay(one);
                if (result.attempted() && result.success()) {
                    approvalService.markExecState(one.getId(), "REPLAYING", "DONE");
                    log.info("[F4] 审批单 {} 重建执行成功(工具 {})", one.getId(), one.getToolName());
                    continue;
                }
                // 未尝试(无快照/不支持)或执行失败:退化人工跟进 + 明确回执
                approvalService.markExecState(one.getId(), "REPLAYING", "NEED_MANUAL");
                log.warn("[F4] 审批单 {} 重建执行未成功({}),标记需人工跟进,runId={}",
                    one.getId(), result.message(), one.getRunId());
                pushManualReceipt(one, result.message());
            }
        } catch (Exception e) {
            // 恢复是增益,失败不阻断应用启动/调度
            log.warn("[F4] 扫描悬挂审批单失败: {}", e.getMessage());
        }
    }

    /**
     * 是否已过在途保护窗口。approveTime 为 null(老数据/异常态)视为已过,纳入扫描以覆盖存量悬挂;
     * 非 null 且晚于 cutoff 则跳过,避免抢走正在 in-process 执行的快路径。
     */
    private static boolean pastGraceWindow(AgentApproval one, long graceCutoff) {
        Date t = one.getApproveTime();
        return t == null || t.getTime() <= graceCutoff;
    }

    private void pushManualReceipt(AgentApproval one, String reason) {
        try {
            if (one.getRunId() == null || one.getRunId().isBlank()) {
                return;
            }
            String payload = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(
                java.util.Map.of("eventType", "TOOL_APPROVAL_RESULT", "status", "need_manual",
                    "toolName", one.getToolName() == null ? "" : one.getToolName(),
                    "message", "操作已审批,但自动重建未成功(" + reason + "),正在人工跟进,请稍候"));
            eventBuffer.publish(one.getRunId(), "cs_approval_result", payload);
        } catch (Exception pe) {
            log.warn("[F4] 审批单 {} 人工跟进回执推送失败: {}", one.getId(), pe.getMessage());
        }
    }
}
