package com.forgeagent.workflow.liteflow.start;

import cn.hutool.core.collection.CollUtil;
import com.yomahub.liteflow.annotation.LiteflowComponent;
import com.yomahub.liteflow.core.NodeComponent;
import lombok.RequiredArgsConstructor;
import com.forgeagent.common.core.enums.BusinessStatusEnum;
import com.forgeagent.common.core.exception.ServiceException;
import org.dromara.warm.flow.core.entity.Task;
import org.dromara.warm.flow.core.service.InsService;
import org.dromara.warm.flow.core.service.TaskService;
import org.dromara.warm.flow.orm.entity.FlowTask;
import com.forgeagent.workflow.api.domain.StartProcessReturnDTO;
import com.forgeagent.workflow.common.ConditionalOnEnable;
import com.forgeagent.workflow.domain.FlowInstanceBizExt;
import com.forgeagent.workflow.domain.context.StartProcessContext;
import com.forgeagent.workflow.mapper.FlwInstanceBizExtMapper;

import java.util.List;

/**
 * 已存在流程实例时执行续提交。
 *
 * @author may; 改造: 智造 ForgeAgent 2026-11
 */
@ConditionalOnEnable
@RequiredArgsConstructor
@LiteflowComponent("startResume")
public class StartResumeComponent extends NodeComponent {

    private final TaskService taskService;
    private final InsService insService;
    private final FlwInstanceBizExtMapper flwInstanceBizExtMapper;

    @Override
    public void process() {
        StartProcessContext context = getContextBean(StartProcessContext.class);
        BusinessStatusEnum.checkStartStatus(context.getExistingInstance().getFlowStatus());
        List<Task> taskList = taskService.list(new FlowTask().setInstanceId(context.getExistingInstance().getId()));
        if (CollUtil.isEmpty(taskList)) {
            throw new ServiceException("流程实例缺少任务，请检查流程定义配置");
        }
        taskService.mergeVariable(context.getExistingInstance(), context.getVariables());
        insService.updateById(context.getExistingInstance());
        saveBizExt(context);
        context.setStartProcessReturn(new StartProcessReturnDTO(taskList.getFirst().getInstanceId(), taskList.getFirst().getId()));
    }

    private void saveBizExt(StartProcessContext context) {
        FlowInstanceBizExt bizExt = context.getBizExt();
        bizExt.setInstanceId(context.getExistingInstance().getId());
        bizExt.setBusinessId(context.getExistingInstance().getBusinessId());
        flwInstanceBizExtMapper.saveOrUpdateByInstanceId(bizExt);
    }

}
