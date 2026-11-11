package com.forgeagent.system.mapper;

import cn.hutool.core.convert.Convert;
import com.forgeagent.common.mybatis.core.mapper.BaseMapperPlus;
import com.forgeagent.system.domain.SysUserRole;

import java.util.List;

/**
 * 用户与角色关联表 数据层
 *
 * @author Lion Li; 改造: 智造 ForgeAgent 2026-11
 */
public interface SysUserRoleMapper extends BaseMapperPlus<SysUserRole, SysUserRole> {

    /**
     * 根据角色ID查询关联的用户ID列表
     *
     * @param roleId 角色ID
     * @return 关联到指定角色的用户ID列表
     */
    default List<Long> selectUserIdsByRoleId(Long roleId) {
        return this.lambda()
            .select(SysUserRole::getUserId)
            .eq(SysUserRole::getRoleId, roleId)
            .objs(Convert::toLong);
    }

}
