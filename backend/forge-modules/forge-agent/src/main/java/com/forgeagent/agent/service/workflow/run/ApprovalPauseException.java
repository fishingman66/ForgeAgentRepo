package com.forgeagent.agent.service.workflow.run;

/**
 * 人工审批暂停信号:审批单已生成,携带等待节点与审批单 ID。
 *
 * <p>原为 {@code AgentWorkflowServiceImpl} 的私有嵌套类;节点执行器拆出后,
 * 执行器(抛出方)与图引擎(捕获方)需要共享该信号,故提升为独立类型。</p>
 */
public class ApprovalPauseException extends RuntimeException {

    public final String nodeId;
    public final String nodeName;
    public final Long approvalId;

    public ApprovalPauseException(String nodeId, String nodeName, Long approvalId) {
        super("等待人工审批:" + nodeName);
        this.nodeId = nodeId;
        this.nodeName = nodeName;
        this.approvalId = approvalId;
    }
}
