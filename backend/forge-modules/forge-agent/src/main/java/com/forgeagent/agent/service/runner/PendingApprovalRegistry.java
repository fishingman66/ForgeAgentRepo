package com.forgeagent.agent.service.runner;

import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * C4 异步 HITL:待执行工具注册表(进程内)。
 *
 * <p>ask_async 模式下,{@link ApprovalGateTool} 创建审批单后立即回执,但{@code delegate +
 * param} 只存在于当次请求的 Toolkit 实例中。本表以 approvalId 为键暂存这对对象,
 * 坐席在审批中心点通过/拒绝时由 {@code AgentApprovalServiceImpl.handle} 取回并异步执行。</p>
 *
 * <p>边界(用户已确认):进程内单例,仅适用于单实例/会话粘滞;实例重启未执行的审批单会
 * 停留在 approved 状态但无人执行——与 docs/11「C4 首版」定位一致,跨实例可靠属二期。
 * 并发闸门由审批单的条件更新(status=pending)承担:只有一次 handle 成功,故每个键最多消费一次。</p>
 *
 * @author ForgeAgent
 */
@Slf4j
@Component
public class PendingApprovalRegistry {

    /**
     * 一条待执行任务:工具本体、原始调用参数、以及回执所需上下文。
     *
     * @param delegate       审批通过后真正执行的工具
     * @param param          当次调用参数(含 input/agent/runtimeContext,进程内复用)
     * @param runId          聊天轮次 runId(SSE 回执通道)
     * @param actionLogId    客服动作日志ID(回调收尾写 DONE/FAILED;可空)
     */
    public record PendingTask(AgentTool delegate, ToolCallParam param, String runId, Long actionLogId) {
    }

    private final Map<Long, PendingTask> pending = new ConcurrentHashMap<>();

    /** 登记待执行任务。同 approvalId 重复登记以先到者为准。 */
    public void register(Long approvalId, PendingTask task) {
        if (approvalId == null || task == null) {
            return;
        }
        PendingTask prev = pending.putIfAbsent(approvalId, task);
        if (prev != null) {
            log.warn("[C4] approvalId={} 重复登记待执行任务,忽略后者", approvalId);
        }
    }

    /**
     * 取出并移除待执行任务(原子)。返回 null 表示无登记项(同步 ask / 已消费 / 实例重启)。
     */
    public PendingTask remove(Long approvalId) {
        return approvalId == null ? null : pending.remove(approvalId);
    }

    /** 轮级中断时按 runId 清理该轮全部待执行任务,避免事后审批触发已终止轮次的工具。 */
    public int removeByRunId(String runId) {
        if (runId == null) {
            return 0;
        }
        int removed = 0;
        var it = pending.entrySet().iterator();
        while (it.hasNext()) {
            if (runId.equals(it.next().getValue().runId())) {
                it.remove();
                removed++;
            }
        }
        return removed;
    }

    /** 仅供测试/诊断:当前登记数量。 */
    int size() {
        return pending.size();
    }
}
