package com.forgeagent.system.listener;

import cn.hutool.core.lang.tree.Tree;
import lombok.RequiredArgsConstructor;
import com.forgeagent.common.core.utils.SpringUtils;
import com.forgeagent.common.core.utils.TreeBuildUtils;
import com.forgeagent.common.excel.core.ExcelOptionsProvider;
import com.forgeagent.system.domain.bo.SysDeptBo;
import com.forgeagent.system.service.ISysDeptService;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

/**
 * Excel 部门下拉选项数据源
 *
 * @author AprilWind; 改造: 智造 ForgeAgent 2026-11
 */
@Component
@RequiredArgsConstructor
public class DeptExcelOptions implements ExcelOptionsProvider {

    /**
     * 获取下拉选项数据
     *
     * @return 下拉选项列表
     */
    @Override
    public Set<String> getOptions() {
        ISysDeptService deptService = SpringUtils.getBean(ISysDeptService.class);
        List<Tree<Long>> trees = deptService.selectDeptTreeList(new SysDeptBo());
        return TreeBuildUtils.buildTreeNodeMap(trees, "/", Tree::getName).keySet();
    }

}
