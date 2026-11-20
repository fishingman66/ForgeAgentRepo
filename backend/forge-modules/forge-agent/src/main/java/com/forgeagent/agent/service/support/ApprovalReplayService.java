package com.forgeagent.agent.service.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.forgeagent.agent.domain.AgentApproval;
import com.forgeagent.agent.service.IAgentApprovalService;
import com.forgeagent.agent.service.runner.ToolAssembler;
import com.forgeagent.agent.service.run.RunEventBuffer;
import com.forgeagent.agentcore.domain.AgentInfo;
import com.forgeagent.agentcore.service.AgentConfigCacheService;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * F4一期(A档):跨实例重建并执行已审批的异步工具调用。
 *
 * <p>只支持 http / sandbox 两类平台工具——它们的执行状态全在 ToolDef 配置里,
 * 无需进程内连接或轮级 collector。重建路径照搬 WorkflowNodeExecutors.executeToolNode:
 * ToolDef 快照 → buildForWorkflow(approvalMode 强制 auto,不会再触发审批) →
 * 按名取工具 → 只喂 input 执行。执行绕过 CsToolGuard 装饰器
 * (buildForWorkflow 不传 csGuardDecorator),与现有 callbackAsyncTool 口径一致,
 * 避免 ask_async 时已写 DONE 的动作日志被幂等键判重拒跑。</p>
 *
 * @author ForgeAgent
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ApprovalReplayService {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ToolAssembler toolAssembler;
    private final IAgentApprovalService approvalService;
    private final AgentConfigCacheService agentConfigCache;
    private final RunEventBuffer eventBuffer;

    /** 重放结果 */
    public record ReplayResult(boolean attempted, boolean success, String message) {
    }

    /**
     * 重放一条审批单。无工具快照 / 非 A 档工具 / 上下文缺失时返回未尝试(调用方保留 NEED_MANUAL 语义)。
     */
    public ReplayResult replay(AgentApproval approval) {
        if (approval == null || approval.getToolDefSnapshot() == null
            || approval.getToolDefSnapshot().isBlank() || approval.getToolId() == null) {
            return new ReplayResult(false, false, "无工具定义快照,无法重建");
        }
        AgentInfo agent = agentConfigCache.getAgentById(approval.getAgentId());
        if (agent == null || agent.getWorkspaceId() == null) {
            return new ReplayResult(false, false, "Agent/工作空间不存在,无法校验工具可见性");
        }
        ToolAssembler.ToolDef def;
        Map<String, Object> input;
        try {
            def = MAPPER.readValue(approval.getToolDefSnapshot(), ToolAssembler.ToolDef.class);
            Object parsed = MAPPER.readValue(
                approval.getCallArgs() == null ? "{}" : approval.getCallArgs(), Map.class);
            input = parsed instanceof Map<?, ?> m ? castMap(m) : Map.of();
        } catch (Exception e) {
            return new ReplayResult(false, false, "工具快照/参数解析失败:" + e.getMessage());
        }
        // A 档白名单:只重放 http / sandbox,防止 sql 丢 collector / MCP 需建连被误重放
        if (!"http".equals(def.toolType()) && !"sandbox".equals(def.toolType())) {
            return new ReplayResult(false, false, "工具类型 " + def.toolType() + " 不在 A 档重放范围");
        }
        try (ToolAssembler.Assembly assembly = toolAssembler.buildForWorkflow(List.of(def))) {
            AgentTool tool = assembly.toolkit().getTool(def.name());
            if (tool == null) {
                return new ReplayResult(false, false, "工具装配失败:" + def.name());
            }
            ToolResultBlock block = tool.callAsync(ToolCallParam.builder().input(input).build())
                .block(Duration.ofSeconds(120));
            String text = extractText(block);
            publish(approval, "approved", "操作已审批并通过重建执行完成:" + text);
            return new ReplayResult(true, true, text);
        } catch (Exception e) {
            log.warn("[F4] 审批单 {} 重放执行失败: {}", approval.getId(), e.getMessage());
            publish(approval, "failed", "审批已通过,但重建执行失败:" + e.getMessage());
            return new ReplayResult(true, false, e.getMessage());
        }
    }

    private static Map<String, Object> castMap(Map<?, ?> m) {
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        m.forEach((k, v) -> out.put(String.valueOf(k), v));
        return out;
    }

    private static String extractText(ToolResultBlock block) {
        if (block == null || block.getOutput() == null || block.getOutput().isEmpty()) {
            return "(工具无返回)";
        }
        StringBuilder sb = new StringBuilder();
        for (Object item : block.getOutput()) {
            if (item instanceof TextBlock tb) {
                sb.append(tb.getText());
            }
        }
        return sb.length() == 0 ? "(工具无文本返回)" : sb.toString();
    }

    /** 回执经 run 级 SSE 推送(与 C4 callbackAsyncTool 同口径) */
    private void publish(AgentApproval approval, String status, String message) {
        if (approval.getRunId() == null || approval.getRunId().isBlank()) {
            return;
        }
        try {
            String payload = MAPPER.writeValueAsString(Map.of(
                "eventType", "TOOL_APPROVAL_RESULT",
                "status", status,
                "toolName", approval.getToolName() == null ? "" : approval.getToolName(),
                "message", message == null ? "" : message));
            eventBuffer.publish(approval.getRunId(), "cs_approval_result", payload);
        } catch (Exception e) {
            log.warn("[F4] 审批单 {} 重放回执推送失败: {}", approval.getId(), e.getMessage());
        }
    }
}
