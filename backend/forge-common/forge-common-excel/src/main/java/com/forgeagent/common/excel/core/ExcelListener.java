package com.forgeagent.common.excel.core;

import org.apache.fesod.sheet.read.listener.ReadListener;

/**
 * Excel 导入监听
 *
 * @author Lion Li; 改造: 智造 ForgeAgent 2026-11
 */
public interface ExcelListener<T> extends ReadListener<T> {

    /**
     * 获取 Excel 导入结果。
     *
     * @return Excel 导入结果
     */
    ExcelResult<T> getExcelResult();

}
