package com.forgeagent.system.service;

import com.forgeagent.common.core.domain.PageResult;
import com.forgeagent.common.mybatis.core.page.PageQuery;
import com.forgeagent.system.domain.bo.SysOperLogBo;
import com.forgeagent.system.domain.vo.SysOperLogVo;

import java.util.List;

/**
 * 操作日志 服务层
 *
 * @author Lion Li; 改造: 智造 ForgeAgent 2026-11
 */
public interface ISysOperLogService {

    /**
     * 分页查询操作日志列表
     *
     * @param operLog   查询条件
     * @param pageQuery 分页参数
     * @return 操作日志分页列表
     */
    PageResult<SysOperLogVo> selectPageOperLogList(SysOperLogBo operLog, PageQuery pageQuery);

    /**
     * 新增操作日志
     *
     * @param bo 操作日志对象
     */
    void insertOperlog(SysOperLogBo bo);

    /**
     * 查询系统操作日志集合
     *
     * @param operLog 操作日志对象
     * @return 操作日志集合
     */
    List<SysOperLogVo> selectOperLogList(SysOperLogBo operLog);

    /**
     * 批量删除系统操作日志
     *
     * @param operIds 需要删除的操作日志ID
     * @return 结果
     */
    int deleteOperLogByIds(Long[] operIds);

    /**
     * 查询操作日志详细
     *
     * @param operId 操作ID
     * @return 操作日志对象
     */
    SysOperLogVo selectOperLogById(Long operId);

    /**
     * 清空操作日志
     */
    void cleanOperLog();
}
