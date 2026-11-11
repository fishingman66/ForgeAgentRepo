package com.forgeagent.common.excel.core;

import java.util.Set;

/**
 * Excel下拉选项数据提供接口
 *
 * @author Angus; 改造: 智造 ForgeAgent 2026-11
 */
public interface ExcelOptionsProvider {

    /**
     * 获取下拉选项数据
     *
     * @return 下拉选项列表
     */
    Set<String> getOptions();

}
