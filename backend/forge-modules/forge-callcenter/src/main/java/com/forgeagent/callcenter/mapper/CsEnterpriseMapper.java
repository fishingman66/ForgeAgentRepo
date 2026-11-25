package com.forgeagent.callcenter.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.forgeagent.callcenter.domain.CsEnterprise;

/** 企业域 Mapper(P0-1):所有查询按 workspace_id 或 id 精确匹配,不做全表下发。 */
public interface CsEnterpriseMapper extends BaseMapper<CsEnterprise> {
}
