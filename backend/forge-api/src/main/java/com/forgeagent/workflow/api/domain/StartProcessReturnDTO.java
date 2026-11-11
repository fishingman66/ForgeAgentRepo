package com.forgeagent.workflow.api.domain;

/**
 * 启动流程后的返回结果对象。
 *
 * @param processInstanceId 流程实例 ID
 * @param taskId            首个任务 ID
 * @author Lion Li; 改造: 智造 ForgeAgent 2026-11
 */
public record StartProcessReturnDTO(
    Long processInstanceId,
    Long taskId
) {
}
