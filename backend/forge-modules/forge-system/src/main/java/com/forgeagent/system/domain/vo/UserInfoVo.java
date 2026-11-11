package com.forgeagent.system.domain.vo;

import lombok.Data;

import java.util.Set;

/**
 * 登录用户信息
 *
 * @author Michelle.Chung; 改造: 智造 ForgeAgent 2026-11
 */
@Data
public class UserInfoVo {

    /**
     * 用户基本信息
     */
    private SysUserVo user;

    /**
     * 菜单权限
     */
    private Set<String> permissions;

    /**
     * 角色权限
     */
    private Set<String> roles;

}
