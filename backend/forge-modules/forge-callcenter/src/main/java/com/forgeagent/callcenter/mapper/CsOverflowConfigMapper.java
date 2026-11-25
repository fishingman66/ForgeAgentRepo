package com.forgeagent.callcenter.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.forgeagent.callcenter.domain.CsOverflowConfig;

/** 溢出策略 Mapper(P0-3):按 workspace_id 精确查询,不做跨空间聚合。 */
public interface CsOverflowConfigMapper extends BaseMapper<CsOverflowConfig> {
}
