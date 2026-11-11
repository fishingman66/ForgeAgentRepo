package com.forgeagent.workflow.mapper;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.github.yulichang.base.MPJBaseMapper;
import com.forgeagent.common.core.enums.BusinessStatusEnum;
import com.forgeagent.common.mybatis.core.mapper.BaseMapperPlus;
import com.forgeagent.common.mybatis.core.query.QueryBuilder;
import org.dromara.warm.flow.core.enums.ActivityStatus;
import org.dromara.warm.flow.core.enums.NodeType;
import org.dromara.warm.flow.orm.entity.*;
import com.forgeagent.workflow.domain.FlowInstanceBizExt;
import com.forgeagent.workflow.domain.bo.FlowTaskBo;
import com.forgeagent.workflow.domain.vo.FlowTaskVo;

import java.util.List;
import java.util.Map;

/**
 * 任务信息Mapper接口
 *
 * @author may; 改造: 智造 ForgeAgent 2026-11
 * @date 2024-03-02
 */
public interface FlwTaskMapper extends BaseMapperPlus<FlowTask, FlowTaskVo>, MPJBaseMapper<FlowTask> {

    /**
     * 分页查询运行中的待办任务。
     *
     * @param page        分页对象
     * @param bo          查询条件
     * @param categoryIds 流程分类 id 列表
     * @param userId      当前用户 id
     * @return 待办任务分页结果
     */
    default Page<FlowTaskVo> getListRunTask(Page<FlowTaskVo> page, FlowTaskBo bo, List<String> categoryIds, String userId) {
        Map<String, Object> params = bo.getParams();
        return QueryBuilder.lambdaJoin("t", FlowTask.class)
            .distinct()
            .select(FlowTask::getId, FlowTask::getNodeCode, FlowTask::getNodeName, FlowTask::getNodeType,
                FlowTask::getDefinitionId, FlowTask::getInstanceId, FlowTask::getCreateTime, FlowTask::getUpdateTime)
            .select("i", FlowInstance::getBusinessId, FlowInstance::getFlowStatus, FlowInstance::getCreateBy)
            .select("d", FlowDefinition::getFlowName, FlowDefinition::getFlowCode, FlowDefinition::getFormCustom,
                FlowDefinition::getCategory, FlowDefinition::getVersion)
            .select("uu", FlowUser::getProcessedBy, FlowUser::getType)
            .select("biz", FlowInstanceBizExt::getBusinessCode, FlowInstanceBizExt::getBusinessTitle)
            .selectAs("COALESCE(NULLIF(TRIM(t.form_path), ''), NULLIF(TRIM(d.form_path), ''))", FlowTaskVo::getFormPath)
            .leftJoin(FlowUser.class, "uu", FlowUser::getAssociated, FlowTask::getId)
            .leftJoin(FlowDefinition.class, "d", FlowDefinition::getId, FlowTask::getDefinitionId)
            .leftJoin(FlowInstance.class, "i", FlowInstance::getId, FlowTask::getInstanceId)
            .leftJoin(FlowInstanceBizExt.class, "biz", FlowInstanceBizExt::getInstanceId, FlowInstance::getId)
            .eq("t", FlowTask::getNodeType, NodeType.BETWEEN.getKey())
            // 挂起实例的任务不允许流转，不进入待办（我的待办/全部待办共用此查询）
            .eq("i", FlowInstance::getActivityStatus, ActivityStatus.ACTIVITY.getKey())
            .in("uu", FlowUser::getType, List.of("1", "2", "3"))
            .likeIfText("t", FlowTask::getNodeName, bo.getNodeName())
            .likeIfText("d", FlowDefinition::getFlowName, bo.getFlowName())
            .likeIfText("d", FlowDefinition::getFlowCode, bo.getFlowCode())
            .likeIfText("i", FlowInstance::getFlowStatus, bo.getFlowStatus())
            .inIfNotEmpty("i", FlowInstance::getCreateBy, bo.getCreateByIds())
            .inIfNotEmpty("d", FlowDefinition::getCategory, categoryIds)
            .betweenParams("t", FlowTask::getCreateTime, params, "beginTime", "endTime")
            .eqIfText("uu", FlowUser::getProcessedBy, userId)
            .eq(userId != null && !userId.isBlank(), "i", FlowInstance::getFlowStatus, BusinessStatusEnum.WAITING.getStatus())
            .orderByDesc("t", FlowTask::getCreateTime)
            .orderByDesc("t", FlowTask::getUpdateTime)
            .page(page, FlowTaskVo.class);
    }

    /**
     * 分页查询抄送任务。
     *
     * @param page        分页对象
     * @param bo          查询条件
     * @param categoryIds 流程分类 id 列表
     * @param userId      当前用户 id
     * @return 抄送任务分页结果
     */
    default Page<FlowTaskVo> getTaskCopyByPage(Page<FlowTaskVo> page, FlowTaskBo bo, List<String> categoryIds, String userId) {
        Map<String, Object> params = bo.getParams();
        return QueryBuilder.lambdaJoin("a", FlowUser.class)
            .select(FlowUser::getProcessedBy, FlowUser::getCreateTime)
            .select("b", FlowHisTask::getId, FlowHisTask::getUpdateTime, FlowHisTask::getFormCustom,
                FlowHisTask::getNodeName, FlowHisTask::getNodeCode)
            // 抄送行取历史快照 form_path，为空时回退定义表实时值，与待办/已办行为一致
            .selectAs("COALESCE(NULLIF(TRIM(b.form_path), ''), NULLIF(TRIM(d.form_path), ''))", FlowTaskVo::getFormPath)
            .select("c", FlowInstance::getBusinessId, FlowInstance::getFlowStatus, FlowInstance::getCreateBy)
            .select("d", FlowDefinition::getFlowName, FlowDefinition::getFlowCode, FlowDefinition::getCategory,
                FlowDefinition::getVersion)
            .select("biz", FlowInstanceBizExt::getBusinessCode, FlowInstanceBizExt::getBusinessTitle)
            .leftJoin(FlowHisTask.class, "b", FlowHisTask::getId, FlowUser::getAssociated)
            .leftJoin(FlowInstance.class, "c", FlowInstance::getId, FlowHisTask::getInstanceId)
            .leftJoin(FlowDefinition.class, "d", FlowDefinition::getId, FlowInstance::getDefinitionId)
            .leftJoin(FlowInstanceBizExt.class, "biz", FlowInstanceBizExt::getInstanceId, FlowInstance::getId)
            .eq("a", FlowUser::getType, "4")
            .likeIfText("b", FlowHisTask::getNodeName, bo.getNodeName())
            .likeIfText("d", FlowDefinition::getFlowName, bo.getFlowName())
            .likeIfText("d", FlowDefinition::getFlowCode, bo.getFlowCode())
            .likeIfText("c", FlowInstance::getFlowStatus, bo.getFlowStatus())
            .inIfNotEmpty("c", FlowInstance::getCreateBy, bo.getCreateByIds())
            .inIfNotEmpty("d", FlowDefinition::getCategory, categoryIds)
            .betweenParams("a", FlowUser::getCreateTime, params, "beginTime", "endTime")
            .eqIfText("a", FlowUser::getProcessedBy, userId)
            .orderByDesc("a", FlowUser::getCreateTime)
            .orderByDesc("b", FlowHisTask::getUpdateTime)
            .page(page, FlowTaskVo.class);
    }

}
