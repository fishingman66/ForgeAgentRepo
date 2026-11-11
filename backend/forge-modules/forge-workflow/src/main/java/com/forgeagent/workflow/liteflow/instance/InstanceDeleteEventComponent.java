package com.forgeagent.workflow.liteflow.instance;

import cn.hutool.core.util.ObjectUtil;
import com.yomahub.liteflow.annotation.LiteflowComponent;
import com.yomahub.liteflow.core.NodeComponent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import com.forgeagent.common.core.exception.ServiceException;
import com.forgeagent.common.core.utils.StreamUtils;
import com.forgeagent.common.satoken.utils.LoginHelper;
import org.dromara.warm.flow.core.entity.Definition;
import org.dromara.warm.flow.core.entity.Instance;
import org.dromara.warm.flow.core.service.DefService;
import com.forgeagent.workflow.common.ConditionalOnEnable;
import com.forgeagent.workflow.domain.context.InstanceDeleteContext;
import com.forgeagent.workflow.handler.FlowProcessEventHandler;

import java.util.Map;
import java.util.function.Function;

/**
 * 校验实例删除权限并发布业务删除事件。
 *
 * @author may; 改造: 智造 ForgeAgent 2026-11
 */
@ConditionalOnEnable
@RequiredArgsConstructor
@Slf4j
@LiteflowComponent("instanceDeleteEvent")
public class InstanceDeleteEventComponent extends NodeComponent {

    private final DefService defService;
    private final FlowProcessEventHandler flowProcessEventHandler;

    @Override
    public void process() {
        InstanceDeleteContext context = getContextBean(InstanceDeleteContext.class);
        String userId = LoginHelper.getUserIdStr();
        context.getFlowInstances().forEach(flowInstance -> {
            if (!LoginHelper.isSuperAdmin() && !flowInstance.getCreateBy().equals(userId)) {
                throw new ServiceException("权限不足，无法删除流程实例信息!");
            }
        });

        Map<Long, Definition> definitionMap = StreamUtils.toMap(
            defService.getByIds(StreamUtils.toList(context.getFlowInstances(), Instance::getDefinitionId)),
            Definition::getId,
            Function.identity()
        );
        context.getFlowInstances().forEach(instance -> {
            Definition definition = definitionMap.get(instance.getDefinitionId());
            if (ObjectUtil.isNull(definition)) {
                log.warn("实例 ID: {} 对应的流程定义信息未找到，跳过删除事件触发。", instance.getId());
                return;
            }
            flowProcessEventHandler.processDeleteHandler(definition.getFlowCode(), instance.getBusinessId());
        });
    }

}
