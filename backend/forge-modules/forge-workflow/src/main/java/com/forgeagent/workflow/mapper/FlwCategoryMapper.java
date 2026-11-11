package com.forgeagent.workflow.mapper;

import com.forgeagent.common.mybatis.core.mapper.BaseMapperPlus;
import com.forgeagent.workflow.domain.FlowCategory;
import com.forgeagent.workflow.domain.vo.FlowCategoryVo;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 流程分类Mapper接口
 *
 * @author may; 改造: 智造 ForgeAgent 2026-11
 * @date 2023-06-27
 */
public interface FlwCategoryMapper extends BaseMapperPlus<FlowCategory, FlowCategoryVo> {

    /**
     * 根据父流程分类ID查询其所有子流程分类的列表
     *
     * @param parentId 父流程分类ID
     * @return 包含子流程分类的列表
     */
    default List<FlowCategory> selectListByParentId(Long parentId) {
        return this.lambda()
            .select(FlowCategory::getCategoryId)
            .findInSet(parentId, FlowCategory::getAncestors)
            .list();
    }

    /**
     * 根据父流程分类ID查询包括父ID及其所有子流程分类ID的列表
     *
     * @param parentId 父流程分类ID
     * @return 包含父ID和子流程分类ID的列表
     */
    default List<Long> selectCategoryIdsByParentId(Long parentId) {
        return Stream.concat(
            this.selectListByParentId(parentId).stream()
                .map(FlowCategory::getCategoryId),
            Stream.of(parentId)
        ).collect(Collectors.toList());
    }

}
