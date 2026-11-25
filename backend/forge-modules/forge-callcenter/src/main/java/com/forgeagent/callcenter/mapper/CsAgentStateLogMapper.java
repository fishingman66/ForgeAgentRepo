package com.forgeagent.callcenter.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.forgeagent.callcenter.domain.CsAgentStateLog;

/** 坐席状态轨迹 Mapper(P1-2):单表 + month 归档键,不做动态表名。 */
public interface CsAgentStateLogMapper extends BaseMapper<CsAgentStateLog> {
}
