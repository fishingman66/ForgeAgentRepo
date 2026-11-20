package com.forgeagent.agent.service.support;

import com.forgeagent.agent.domain.AgentApproval;
import com.forgeagent.agent.service.IAgentApprovalService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * F4一期:启动时扫描"已批准但未完成"的异步工具审批单并重建执行。
 *
 * <p>跨实例/重启后进程内 PendingApprovalRegistry 丢失,但 A 档(http/sandbox)工具的
 * ToolDef 快照与 input 已落审批单,可按 WorkflowNodeExecutors 的原型重建执行。
 * 抢单用 exec_state 条件更新(APPROVED→REPLAYING),多实例仅一个执行;
 * 无快照 / B/C 档工具 / 重建失败则退化 NEED_MANUAL + 回执 + 告警,绝不静默丢弃。</p>
 *
 * @author ForgeAgent
 */
@Slf4j
@Component
@Order(100)
@RequiredArgsConstructor
public class StaleApprovalRecoveryRunner implements ApplicationRunner {

    private final IAgentApprovalService approvalService;
    private final ApprovalReplayService replayService;
    private final com.forgeagent.agent.service.run.RunEventBuffer eventBuffer;

    @Override
    public void run(ApplicationArguments args) {
        try {
            java.util.List<AgentApproval> stale = approvalService.listStaleApproved();
            if (stale == null || stale.isEmpty()) {
                return;
            }
            for (AgentApproval one : stale) {
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
            // 启动恢复是增益,失败不阻断应用启动
            log.warn("[F4] 启动扫描悬挂审批单失败: {}", e.getMessage());
        }
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
            log.warn("[F4] 审批单 {} 回执推送失败: {}", one.getId(), pe.getMessage());
        }
    }
}
