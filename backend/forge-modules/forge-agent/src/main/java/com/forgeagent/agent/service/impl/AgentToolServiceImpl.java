package com.forgeagent.agent.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import com.forgeagent.agent.domain.AgentDatasource;
import com.forgeagent.agent.domain.AgentTool;
import com.forgeagent.agent.domain.bo.AgentToolBo;
import com.forgeagent.agent.domain.vo.AgentToolVo;
import com.forgeagent.agent.mapper.AgentDatasourceMapper;
import com.forgeagent.agent.mapper.AgentToolMapper;
import com.forgeagent.agent.service.IAgentToolService;
import com.forgeagent.common.core.domain.PageResult;
import com.forgeagent.common.core.exception.ServiceException;
import com.forgeagent.common.core.utils.MapstructUtils;
import com.forgeagent.common.core.utils.StringUtils;
import com.forgeagent.common.json.utils.JsonUtils;
import com.forgeagent.common.mybatis.core.page.PageQuery;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;

/**
 * 工具注册 Service 实现
 *
 * @author ForgeAgent
 */
@RequiredArgsConstructor
@Service
public class AgentToolServiceImpl implements IAgentToolService {

    private static final Set<String> HTTP_METHODS = Set.of("GET", "POST", "PUT", "DELETE");

    private final AgentToolMapper toolMapper;
    private final AgentDatasourceMapper datasourceMapper;
    private final com.forgeagent.agentcore.service.ResourceAccessGuard resourceGuard;


    @Override
    public PageResult<AgentToolVo> queryPageList(AgentToolBo bo, PageQuery pageQuery) {
        LambdaQueryWrapper<AgentTool> wrapper = new LambdaQueryWrapper<AgentTool>()
            .like(StringUtils.isNotBlank(bo.getKeyword()), AgentTool::getName, bo.getKeyword())
            .eq(StringUtils.isNotBlank(bo.getStatus()), AgentTool::getStatus, bo.getStatus())
            .orderByDesc(AgentTool::getId);
        resourceGuard.applyVisible(wrapper, AgentTool::getWorkspaceId);
        Page<AgentToolVo> page = toolMapper.selectVoPage(pageQuery.build(), wrapper);
        return PageResult.build(page.getRecords(), page.getTotal());
    }

    @Override
    public AgentToolVo queryById(Long id) {
        AgentTool entity = toolMapper.selectById(id);
        if (entity != null) {
            resourceGuard.assertReadable(entity.getWorkspaceId());
        }
        AgentToolVo vo = MapstructUtils.convert(entity, AgentToolVo.class);
        if (vo == null) {
            throw new ServiceException("工具不存在");
        }
        return vo;
    }

    @Override
    public Boolean insertByBo(AgentToolBo bo) {
        Long scope = resourceGuard.resolveCreateWorkspace(bo.getWorkspaceId(), Boolean.TRUE.equals(bo.getShared()));
        checkNameUnique(bo.getName(), null, scope);
        validateHttpConfig(bo);
        validateSqlConfig(bo, scope);
        AgentTool entity = MapstructUtils.convert(bo, AgentTool.class);
        entity.setWorkspaceId(scope);
        entity.setStatus("0");
        return toolMapper.insert(entity) > 0;
    }

    @Override
    public Boolean updateByBo(AgentToolBo bo) {
        AgentTool exists = toolMapper.selectById(bo.getId());
        if (exists == null) {
            throw new ServiceException("工具不存在");
        }
        resourceGuard.assertWritable(exists.getWorkspaceId());
        checkNameUnique(bo.getName(), bo.getId(), exists.getWorkspaceId());
        validateHttpConfig(bo);
        validateSqlConfig(bo, exists.getWorkspaceId());
        AgentTool update = MapstructUtils.convert(bo, AgentTool.class);
        update.setStatus(null);
        update.setWorkspaceId(null); // 归属不可变
        return toolMapper.updateById(update) > 0;
    }

    @Override
    public Boolean changeStatus(Long id, String status) {
        if (!"0".equals(status) && !"1".equals(status)) {
            throw new ServiceException("非法的工具状态");
        }
        AgentTool exists = toolMapper.selectById(id);
        if (exists != null) {
            resourceGuard.assertWritable(exists.getWorkspaceId());
        }
        AgentTool update = new AgentTool();
        update.setId(id);
        update.setStatus(status);
        return toolMapper.updateById(update) > 0;
    }

    @Override
    public Boolean deleteByIds(List<Long> ids) {
        for (Long id : ids) {
            AgentTool exists = toolMapper.selectById(id);
            if (exists != null) {
                resourceGuard.assertWritable(exists.getWorkspaceId());
            }
        }
        return toolMapper.deleteByIds(ids) > 0;
    }

    private void checkNameUnique(String name, Long excludeId, Long workspaceId) {
        if (StringUtils.isBlank(name)) {
            return;
        }
        LambdaQueryWrapper<AgentTool> wrapper = new LambdaQueryWrapper<AgentTool>()
            .eq(AgentTool::getName, name)
            .ne(excludeId != null, AgentTool::getId, excludeId);
        if (workspaceId == null) {
            wrapper.isNull(AgentTool::getWorkspaceId);
        } else {
            wrapper.eq(AgentTool::getWorkspaceId, workspaceId);
        }
        if (toolMapper.selectCount(wrapper) > 0) {
            throw new ServiceException("工具名称已存在:" + name);
        }
    }

    /**
     * http 类型工具必须提供合法的方法/地址/JSON
     */
    private void validateHttpConfig(AgentToolBo bo) {
        if (!"http".equals(bo.getToolType())) {
            return;
        }
        if (StringUtils.isBlank(bo.getHttpMethod()) || !HTTP_METHODS.contains(bo.getHttpMethod())) {
            throw new ServiceException("HTTP 工具必须选择 GET/POST/PUT/DELETE 方法");
        }
        if (StringUtils.isBlank(bo.getHttpUrl())) {
            throw new ServiceException("HTTP 工具必须填写接口地址");
        }
        checkJson(bo.getHttpHeaders(), "HTTP 请求头");
        checkJson(bo.getHttpParams(), "HTTP 请求参数");
    }

    /**
     * sql 类型工具校验:必须绑定存在且启用的数据源
     */
    private void validateSqlConfig(AgentToolBo bo, Long ownerWorkspaceId) {
        if (!"sql".equals(bo.getToolType())) {
            return;
        }
        if (bo.getDatasourceId() == null) {
            throw new ServiceException("问数工具必须绑定数据源");
        }
        AgentDatasource ds = datasourceMapper.selectById(bo.getDatasourceId());
        if (ds == null) {
            throw new ServiceException("绑定的数据源不存在");
        }
        if ("1".equals(ds.getStatus())) {
            throw new ServiceException("绑定的数据源已停用");
        }
        resourceGuard.assertReadableBy(ds.getWorkspaceId(), ownerWorkspaceId);
    }

    private void checkJson(String json, String label) {
        if (StringUtils.isBlank(json)) {
            return;
        }
        if (!JsonUtils.isJson(json)) {
            throw new ServiceException(label + "不是合法的 JSON");
        }
    }
}
