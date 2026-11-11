package com.forgeagent.agent.service;

import com.forgeagent.agent.domain.bo.AgentDatasourceBo;
import com.forgeagent.agent.domain.vo.AgentDatasourceVo;
import com.forgeagent.common.core.domain.PageResult;
import com.forgeagent.common.mybatis.core.page.PageQuery;

import java.util.List;

/**
 * 问数数据源Service接口
 *
 * @author ForgeAgent
 */
public interface IAgentDatasourceService {

    /**
     * 分页查询数据源
     */
    PageResult<AgentDatasourceVo> queryPageList(AgentDatasourceBo bo, PageQuery pageQuery);

    /**
     * 数据源详情
     */
    AgentDatasourceVo queryById(Long id);

    /**
     * 新增数据源
     */
    Boolean insertByBo(AgentDatasourceBo bo);

    /**
     * 修改数据源
     */
    Boolean updateByBo(AgentDatasourceBo bo);

    /**
     * 启用/停用数据源
     */
    Boolean changeStatus(Long id, String status);

    /**
     * 删除数据源
     */
    Boolean deleteByIds(List<Long> ids);

    /**
     * 测试连通性(支持未保存配置的临时测试),返回耗时毫秒
     */
    Long testConnection(AgentDatasourceBo bo);

    /**
     * 同步表结构快照,返回快照文本
     */
    String refreshSchema(Long id);

    /**
     * 查看表结构快照
     */
    String getSchema(Long id);

    /**
     * 可查询表清单(从已同步的 schemaTables 读取,供白名单选择与展示)
     */
    List<java.util.Map<String, Object>> listTables(Long id);
}
