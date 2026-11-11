package com.forgeagent.workflow.mapper;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.github.yulichang.base.MPJBaseMapper;
import com.forgeagent.common.mybatis.core.mapper.BaseMapperPlus;
import com.forgeagent.common.mybatis.core.query.QueryBuilder;
import org.dromara.warm.flow.core.enums.NodeType;
import org.dromara.warm.flow.core.enums.TaskStatus;
import org.dromara.warm.flow.orm.entity.FlowDefinition;
import org.dromara.warm.flow.orm.entity.FlowHisTask;
import org.dromara.warm.flow.orm.entity.FlowInstance;
import com.forgeagent.workflow.domain.FlowInstanceBizExt;
import com.forgeagent.workflow.domain.bo.FlowTaskBo;
import com.forgeagent.workflow.domain.vo.FlowHisTaskVo;

import java.util.List;
import java.util.Map;

/**
 * 任务信息Mapper接口
 *
 * @author may; 改造: 智造 ForgeAgent 2026-11
 * @date 2024-03-02
 */
public interface FlwHisTaskMapper extends BaseMapperPlus<FlowHisTask, FlowHisTaskVo>, MPJBaseMapper<FlowHisTask> {

    /**
     * 分页查询已办任务列表。
     *
     * @param page        分页对象
     * @param bo          查询条件
     * @param categoryIds 流程分类 ID 集合
     * @param userId      当前用户 ID
     * @return 已办任务分页结果
     */
    default Page<FlowHisTaskVo> getListFinishTask(Page<FlowHisTaskVo> page, FlowTaskBo bo, List<String> categoryIds, String userId) {
        Map<String, Object> params = bo.getParams();
        return QueryBuilder.lambdaJoin("a", FlowHisTask.class)
            .select(FlowHisTask::getId, FlowHisTask::getNodeCode, FlowHisTask::getNodeName,
                FlowHisTask::getCooperateType, FlowHisTask::getApprover, FlowHisTask::getCollaborator,
                FlowHisTask::getNodeType, FlowHisTask::getTargetNodeCode, FlowHisTask::getTargetNodeName,
                FlowHisTask::getDefinitionId, FlowHisTask::getInstanceId)
            .selectAs(FlowHisTask::getFlowStatus, FlowHisTaskVo::getFlowTaskStatus)
            .select(FlowHisTask::getMessage, FlowHisTask::getExt, FlowHisTask::getCreateTime,
                FlowHisTask::getUpdateTime, FlowHisTask::getFormCustom)
            // 历史任务快照可能早于定义补充 form_path，为空时回退定义表实时值，保证「查看」跳转可用
            .selectAs("COALESCE(NULLIF(TRIM(a.form_path), ''), NULLIF(TRIM(c.form_path), ''))", FlowHisTaskVo::getFormPath)
            .select("b", FlowInstance::getFlowStatus, FlowInstance::getBusinessId, FlowInstance::getCreateBy)
            .select("c", FlowDefinition::getFlowName, FlowDefinition::getFlowCode, FlowDefinition::getCategory,
                FlowDefinition::getVersion)
            .select("biz", FlowInstanceBizExt::getBusinessCode, FlowInstanceBizExt::getBusinessTitle)
            .leftJoin(FlowInstance.class, "b", FlowInstance::getId, FlowHisTask::getInstanceId)
            .leftJoin(FlowDefinition.class, "c", FlowDefinition::getId, FlowHisTask::getDefinitionId)
            .leftJoin(FlowInstanceBizExt.class, "biz", FlowInstanceBizExt::getInstanceId, FlowInstance::getId)
            .in("a", FlowHisTask::getNodeType, List.of(1, 3, 4))
            .ne("a", FlowHisTask::getFlowStatus, TaskStatus.COPY.getKey())
            .likeIfText("a", FlowHisTask::getNodeName, bo.getNodeName())
            .likeIfText("c", FlowDefinition::getFlowName, bo.getFlowName())
            .likeIfText("c", FlowDefinition::getFlowCode, bo.getFlowCode())
            .likeIfText("b", FlowInstance::getFlowStatus, bo.getFlowStatus())
            .inIfNotEmpty("b", FlowInstance::getCreateBy, bo.getCreateByIds())
            .inIfNotEmpty("c", FlowDefinition::getCategory, categoryIds)
            .betweenParams("a", FlowHisTask::getCreateTime, params, "beginTime", "endTime")
            .eq(userId != null && !userId.isBlank(), "a", FlowHisTask::getNodeType, NodeType.BETWEEN.getKey())
            .eqIfText("a", FlowHisTask::getApprover, userId)
            .orderByDesc("a", FlowHisTask::getCreateTime)
            .orderByDesc("a", FlowHisTask::getUpdateTime)
            .page(page, FlowHisTaskVo.class);
    }

}
