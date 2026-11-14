package com.forgeagent.callcenter.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.forgeagent.callcenter.domain.CsCallProfile;

/** 通话画像 Mapper(C8);幂等依靠唯一键 uk_cccp_call(call_id)。 */
public interface CsCallProfileMapper extends BaseMapper<CsCallProfile> {
}
