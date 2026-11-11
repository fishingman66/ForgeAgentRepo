package com.forgeagent.gen.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.forgeagent.common.mybatis.core.mapper.BaseMapperPlus;
import com.forgeagent.gen.domain.GenTableColumn;

/**
 * 业务字段 数据层
 *
 * @author Lion Li; 改造: 智造 ForgeAgent 2026-11
 */
@InterceptorIgnore(dataPermission = "true", tenantLine = "true")
public interface GenTableColumnMapper extends BaseMapperPlus<GenTableColumn, GenTableColumn> {

}
