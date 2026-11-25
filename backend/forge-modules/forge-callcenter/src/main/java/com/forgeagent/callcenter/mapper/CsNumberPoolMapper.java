package com.forgeagent.callcenter.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.forgeagent.callcenter.domain.CsNumberPool;

/** 号码池 Mapper(P2-1):仅元数据读写,不含 PSTN 动作。 */
public interface CsNumberPoolMapper extends BaseMapper<CsNumberPool> {
}
