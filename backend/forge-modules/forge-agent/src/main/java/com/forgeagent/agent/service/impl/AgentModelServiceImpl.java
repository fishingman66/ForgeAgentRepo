package com.forgeagent.agent.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import com.forgeagent.agentcore.domain.AgentModel;
import com.forgeagent.agent.domain.bo.AgentModelBo;
import com.forgeagent.agentcore.domain.vo.AgentModelVo;
import com.forgeagent.agentcore.mapper.AgentModelMapper;
import com.forgeagent.agent.service.IAgentModelService;
import com.forgeagent.common.core.domain.PageResult;
import com.forgeagent.common.core.exception.ServiceException;
import com.forgeagent.common.core.utils.MapstructUtils;
import com.forgeagent.common.core.utils.StringUtils;
import com.forgeagent.common.mybatis.core.page.PageQuery;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 模型配置 Service 实现
 *
 * @author ForgeAgent
 */
@RequiredArgsConstructor
@Service
public class AgentModelServiceImpl implements IAgentModelService {

    private final AgentModelMapper modelMapper;
    private final com.forgeagent.agentcore.service.AuditLogHelper auditLogHelper;
    private final com.forgeagent.agentcore.service.AgentConfigCacheService agentConfigCache;
    private final com.forgeagent.agentcore.service.ResourceAccessGuard resourceGuard;


    @Override
    public PageResult<AgentModelVo> queryPageList(AgentModelBo bo, PageQuery pageQuery) {
        LambdaQueryWrapper<AgentModel> wrapper = new LambdaQueryWrapper<AgentModel>()
            .like(StringUtils.isNotBlank(bo.getKeyword()), AgentModel::getName, bo.getKeyword())
            .eq(StringUtils.isNotBlank(bo.getStatus()), AgentModel::getStatus, bo.getStatus())
            .orderByDesc(AgentModel::getId);
        resourceGuard.applyVisible(wrapper, AgentModel::getWorkspaceId);
        Page<AgentModelVo> page = modelMapper.selectVoPage(pageQuery.build(), wrapper);
        page.getRecords().forEach(AgentModelServiceImpl::maskApiKey);
        return PageResult.build(page.getRecords(), page.getTotal());
    }

    @Override
    public AgentModelVo queryById(Long id) {
        AgentModel entity = modelMapper.selectById(id);
        if (entity != null) {
            resourceGuard.assertReadable(entity.getWorkspaceId());
        }
        AgentModelVo vo = MapstructUtils.convert(entity, AgentModelVo.class);
        if (vo == null) {
            throw new ServiceException("模型配置不存在");
        }
        maskApiKey(vo);
        return vo;
    }

    @Override
    public Boolean insertByBo(AgentModelBo bo) {
        Long scope = resourceGuard.resolveCreateWorkspace(bo.getWorkspaceId(), Boolean.TRUE.equals(bo.getShared()));
        checkNameUnique(bo.getName(), null, scope);
        AgentModel entity = MapstructUtils.convert(bo, AgentModel.class);
        entity.setWorkspaceId(scope);
        if (entity.getTemperature() == null) {
            entity.setTemperature(new java.math.BigDecimal("0.70"));
        }
        if (entity.getMaxTokens() == null) {
            entity.setMaxTokens(4096);
        }
        entity.setStatus("0");
        boolean ok = modelMapper.insert(entity) > 0;
        if (ok) {
            agentConfigCache.evictModel(entity.getId());
            auditLogHelper.record(null, "credential.create", "model", String.valueOf(entity.getId()),
                "新增模型配置[" + entity.getName() + "](provider=" + entity.getProvider() + ")");
        }
        return ok;
    }

    @Override
    public Boolean updateByBo(AgentModelBo bo) {
        AgentModel exists = modelMapper.selectById(bo.getId());
        if (exists == null) {
            throw new ServiceException("模型配置不存在");
        }
        resourceGuard.assertWritable(exists.getWorkspaceId());
        checkNameUnique(bo.getName(), bo.getId(), exists.getWorkspaceId());
        AgentModel update = MapstructUtils.convert(bo, AgentModel.class);
        update.setStatus(null);
        update.setWorkspaceId(null); // 归属一经创建不可变,防止绕过共享资源写权限
        // 密钥留空表示不修改
        if (StringUtils.isBlank(update.getApiKey())) {
            update.setApiKey(null);
        }
        boolean ok = modelMapper.updateById(update) > 0;
        if (ok) {
            agentConfigCache.evictModel(bo.getId());
            auditLogHelper.record(null, "credential.update", "model", String.valueOf(bo.getId()),
                "修改模型配置[" + bo.getName() + "]");
        }
        return ok;
    }

    @Override
    public Boolean changeStatus(Long id, String status) {
        if (!"0".equals(status) && !"1".equals(status)) {
            throw new ServiceException("非法的模型状态");
        }
        AgentModel exists = modelMapper.selectById(id);
        if (exists != null) {
            resourceGuard.assertWritable(exists.getWorkspaceId());
        }
        AgentModel update = new AgentModel();
        update.setId(id);
        update.setStatus(status);
        boolean ok = modelMapper.updateById(update) > 0;
        if (ok) {
            agentConfigCache.evictModel(id);
            auditLogHelper.record(null, "credential.update", "model", String.valueOf(id),
                "0".equals(status) ? "启用模型配置" : "停用模型配置");
        }
        return ok;
    }

    @Override
    public Boolean deleteByIds(List<Long> ids) {
        for (Long id : ids) {
            AgentModel exists = modelMapper.selectById(id);
            if (exists == null) {
                continue;
            }
            resourceGuard.assertWritable(exists.getWorkspaceId());
            modelMapper.deleteById(id);
            agentConfigCache.evictModel(id);
            auditLogHelper.record(null, "credential.delete", "model", String.valueOf(id),
                "删除模型配置[" + exists.getName() + "]");
        }
        return true;
    }

    private void checkNameUnique(String name, Long excludeId, Long workspaceId) {
        if (StringUtils.isBlank(name)) {
            return;
        }
        LambdaQueryWrapper<AgentModel> wrapper = new LambdaQueryWrapper<AgentModel>()
            .eq(AgentModel::getName, name)
            .ne(excludeId != null, AgentModel::getId, excludeId);
        // 共享名全局唯一(仅比共享),私有名同空间唯一
        if (workspaceId == null) {
            wrapper.isNull(AgentModel::getWorkspaceId);
        } else {
            wrapper.eq(AgentModel::getWorkspaceId, workspaceId);
        }
        if (modelMapper.selectCount(wrapper) > 0) {
            throw new ServiceException("模型名称已存在:" + name);
        }
    }

    /**
     * 密钥脱敏:仅保留前 4 位
     */
    private static void maskApiKey(AgentModelVo vo) {
        String key = vo.getApiKey();
        if (StringUtils.isNotBlank(key) && key.length() > 4) {
            vo.setApiKey(key.substring(0, 4) + "****");
        }
    }
}
