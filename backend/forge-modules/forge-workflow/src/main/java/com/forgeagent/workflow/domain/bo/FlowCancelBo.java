package com.forgeagent.workflow.domain.bo;

import jakarta.validation.constraints.NotBlank;
import com.forgeagent.common.core.validate.AddGroup;

/**
 * 撤销流程请求对象。
 *
 * @param businessId 业务 ID
 * @param message    撤销说明
 * @author may; 改造: 智造 ForgeAgent 2026-11
 */
public record FlowCancelBo(
    @NotBlank(message = "业务ID不能为空", groups = AddGroup.class)
    String businessId,
    String message
) {
}
