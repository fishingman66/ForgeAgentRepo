package com.forgeagent.workflow.domain.bo;

import jakarta.validation.constraints.NotNull;
import com.forgeagent.common.core.validate.AddGroup;

/**
 * 终止任务请求对象
 *
 * @param taskId  任务 ID
 * @param comment 终止意见
 * @author may; 改造: 智造 ForgeAgent 2026-11
 */
public record FlowTerminationBo(
    @NotNull(message = "任务id为空", groups = AddGroup.class)
    Long taskId,
    String comment
) {
}
