package com.forgeagent.system.mapper;

import com.forgeagent.common.mybatis.core.mapper.BaseMapperPlus;
import com.forgeagent.system.domain.SysRoleMenu;

import java.util.Collection;

/**
 * 角色与菜单关联表 数据层
 *
 * @author Lion Li; 改造: 智造 ForgeAgent 2026-11
 */
public interface SysRoleMenuMapper extends BaseMapperPlus<SysRoleMenu, SysRoleMenu> {

    /**
     * 根据菜单ID串删除关联关系
     *
     * @param menuIds 菜单ID串
     * @return 结果
     */
    default int deleteByMenuIds(Collection<Long> menuIds) {
        return this.lambda().in(SysRoleMenu::getMenuId, menuIds).deleteCount();
    }

}
