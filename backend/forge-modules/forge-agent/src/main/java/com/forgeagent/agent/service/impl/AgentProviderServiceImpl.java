package com.forgeagent.agent.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import com.forgeagent.agentcore.domain.AgentModel;
import com.forgeagent.agent.domain.AgentProvider;
import com.forgeagent.agent.domain.bo.AgentProviderBo;
import com.forgeagent.agent.domain.vo.AgentProviderVo;
import com.forgeagent.agentcore.mapper.AgentModelMapper;
import com.forgeagent.agent.mapper.AgentProviderMapper;
import com.forgeagent.agent.service.IAgentProviderService;
import com.forgeagent.common.core.domain.PageResult;
import com.forgeagent.common.core.exception.ServiceException;
import com.forgeagent.common.core.utils.MapstructUtils;
import com.forgeagent.common.core.utils.StringUtils;
import com.forgeagent.common.mybatis.core.page.PageQuery;
import com.forgeagent.common.satoken.utils.LoginHelper;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 模型供应商配置 Service 实现
 *
 * @author ForgeAgent
 */
@RequiredArgsConstructor
@Service
public class AgentProviderServiceImpl implements IAgentProviderService {

    private final AgentProviderMapper providerMapper;
    private final AgentModelMapper modelMapper;

    @Override
    public PageResult<AgentProviderVo> queryPageList(AgentProviderBo bo, PageQuery pageQuery) {
        LambdaQueryWrapper<AgentProvider> wrapper = new LambdaQueryWrapper<AgentProvider>()
            .and(StringUtils.isNotBlank(bo.getKeyword()),
                w -> w.like(AgentProvider::getProviderName, bo.getKeyword())
                    .or().like(AgentProvider::getProviderCode, bo.getKeyword()))
            .eq(StringUtils.isNotBlank(bo.getStatus()), AgentProvider::getStatus, bo.getStatus())
            .orderByAsc(AgentProvider::getId);
        Page<AgentProviderVo> page = providerMapper.selectVoPage(pageQuery.build(), wrapper);
        return PageResult.build(page.getRecords(), page.getTotal());
    }

    @Override
    public AgentProviderVo queryById(Long id) {
        AgentProviderVo vo = MapstructUtils.convert(providerMapper.selectById(id), AgentProviderVo.class);
        if (vo == null) {
            throw new ServiceException("供应商不存在");
        }
        return vo;
    }

    @Override
    public List<AgentProviderVo> listAll() {
        return providerMapper.selectVoList(new LambdaQueryWrapper<AgentProvider>()
            .orderByAsc(AgentProvider::getId));
    }

    @Override
    public Boolean insertByBo(AgentProviderBo bo) {
        checkSuperAdmin();
        checkCodeUnique(bo.getProviderCode(), null);
        AgentProvider entity = MapstructUtils.convert(bo, AgentProvider.class);
        entity.setId(null);
        entity.setStatus("0");
        return providerMapper.insert(entity) > 0;
    }

    @Override
    public Boolean updateByBo(AgentProviderBo bo) {
        checkSuperAdmin();
        if (providerMapper.selectById(bo.getId()) == null) {
            throw new ServiceException("供应商不存在");
        }
        AgentProvider update = MapstructUtils.convert(bo, AgentProvider.class);
        // 编码创建后不可修改
        update.setProviderCode(null);
        update.setStatus(null);
        return providerMapper.updateById(update) > 0;
    }

    @Override
    public Boolean changeStatus(Long id, String status) {
        checkSuperAdmin();
        if (!"0".equals(status) && !"1".equals(status)) {
            throw new ServiceException("非法的供应商状态");
        }
        AgentProvider exists = providerMapper.selectById(id);
        if (exists == null) {
            throw new ServiceException("供应商不存在");
        }
        AgentProvider update = new AgentProvider();
        update.setId(id);
        update.setStatus(status);
        return providerMapper.updateById(update) > 0;
    }

    @Override
    public Boolean deleteByIds(List<Long> ids) {
        checkSuperAdmin();
        for (Long id : ids) {
            AgentProvider exists = providerMapper.selectById(id);
            if (exists == null) {
                continue;
            }
            Long refCount = modelMapper.selectCount(new LambdaQueryWrapper<AgentModel>()
                .eq(AgentModel::getProvider, exists.getProviderCode()));
            if (refCount != null && refCount > 0) {
                throw new ServiceException("供应商[" + exists.getProviderName() + "]已被 " + refCount + " 个模型配置引用,无法删除");
            }
            providerMapper.deleteById(id);
        }
        return true;
    }

    private void checkSuperAdmin() {
        if (!LoginHelper.isSuperAdmin()) {
            throw new ServiceException("仅平台管理员可执行该操作");
        }
    }

    private void checkCodeUnique(String code, Long excludeId) {
        LambdaQueryWrapper<AgentProvider> wrapper = new LambdaQueryWrapper<AgentProvider>()
            .eq(AgentProvider::getProviderCode, code)
            .ne(excludeId != null, AgentProvider::getId, excludeId);
        if (providerMapper.selectCount(wrapper) > 0) {
            throw new ServiceException("供应商编码已存在:" + code);
        }
    }
}
