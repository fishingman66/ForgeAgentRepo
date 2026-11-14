package com.forgeagent.callcenter.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.forgeagent.callcenter.domain.CsCallbackTask;

/** 呼损回拨单 Mapper(C8);幂等依靠唯一键 uk_ccct_call(call_id)。 */
public interface CsCallbackTaskMapper extends BaseMapper<CsCallbackTask> {
}
