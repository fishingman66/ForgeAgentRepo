package com.forgeagent.agent.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import com.forgeagent.agent.domain.AgentDatasourceExample;
import com.forgeagent.agent.domain.bo.AgentDatasourceExampleBo;
import com.forgeagent.agent.domain.vo.AgentDatasourceExampleVo;
import com.forgeagent.agent.mapper.AgentDatasourceExampleMapper;
import com.forgeagent.agent.service.IAgentDatasourceExampleService;
import com.forgeagent.common.core.utils.MapstructUtils;
import com.forgeagent.common.core.utils.StringUtils;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 问数示例库Service实现
 *
 * @author ForgeAgent
 */
@Slf4j
@RequiredArgsConstructor
@Service
public class AgentDatasourceExampleServiceImpl implements IAgentDatasourceExampleService {

    private final AgentDatasourceExampleMapper exampleMapper;

    @Override
    public List<AgentDatasourceExampleVo> queryList(Long datasourceId) {
        LambdaQueryWrapper<AgentDatasourceExample> wrapper = new LambdaQueryWrapper<AgentDatasourceExample>()
            .eq(AgentDatasourceExample::getDatasourceId, datasourceId)
            .orderByAsc(AgentDatasourceExample::getSort)
            .orderByAsc(AgentDatasourceExample::getId);
        return exampleMapper.selectVoList(wrapper);
    }

    @Override
    public Boolean insertByBo(AgentDatasourceExampleBo bo) {
        AgentDatasourceExample entity = MapstructUtils.convert(bo, AgentDatasourceExample.class);
        if (StringUtils.isBlank(entity.getStatus())) {
            entity.setStatus("0");
        }
        return exampleMapper.insert(entity) > 0;
    }

    @Override
    public Boolean updateByBo(AgentDatasourceExampleBo bo) {
        AgentDatasourceExample update = MapstructUtils.convert(bo, AgentDatasourceExample.class);
        return exampleMapper.updateById(update) > 0;
    }

    @Override
    public Boolean deleteByIds(List<Long> ids) {
        return exampleMapper.deleteByIds(ids) > 0;
    }
}
