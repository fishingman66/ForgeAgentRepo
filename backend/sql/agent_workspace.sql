-- 防止客户端字符集不是 utf8mb4 时中文被双重编码
SET NAMES utf8mb4;
-- =====================================================================
-- Agent 平台 · M1 工作空间模块 初始化 SQL
-- 目标库: forge-vue
-- =====================================================================

-- ----------------------------
-- 1. 工作空间表
-- ----------------------------
drop table if exists agent_workspace;
create table agent_workspace
(
    id           bigint       not null auto_increment comment '主键',
    name         varchar(30)  not null comment '空间名称(全局唯一,2-30字符)',
    description  varchar(200) default '' comment '空间描述',
    status       char(1)      default '0' comment '状态(0正常 1停用)',
    owner_id     bigint       not null comment '空间所有者(首任空间管理员)用户ID',
    create_dept  bigint       null comment '创建部门',
    create_by    bigint       null comment '创建者',
    create_time  datetime     null comment '创建时间',
    update_by    bigint       null comment '更新者',
    update_time  datetime     null comment '更新时间',
    del_flag     bigint       default 0 comment '删除标志(0存在 其他删除)',
    primary key (id)
) engine = innodb
  auto_increment = 1
  comment = 'Agent 工作空间' collate = utf8mb4_unicode_ci;

-- ----------------------------
-- 2. 工作空间成员表(成员移除为物理删除)
-- ----------------------------
drop table if exists agent_workspace_member;
create table agent_workspace_member
(
    id           bigint       not null comment '主键(雪花)',
    workspace_id bigint       not null comment '工作空间ID',
    user_id      bigint       not null comment '系统用户ID',
    role         varchar(20)  not null comment '空间角色(admin/developer/runner)',
    create_by    bigint       null comment '创建者',
    create_time  datetime     null comment '创建时间',
    update_by    bigint       null comment '更新者',
    update_time  datetime     null comment '更新时间',
    primary key (id),
    unique key uk_ws_user (workspace_id, user_id),
    key idx_user (user_id)
) engine = innodb
  comment = 'Agent 工作空间成员' collate = utf8mb4_unicode_ci;

-- ----------------------------
-- 3. 菜单: Agent 平台 -> 工作空间
-- ----------------------------
insert into sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param,
                      is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu,
                      create_dept, create_by, create_time, remark)
values (1761400000000030001, 'Agent平台', 0, 1, 'agent', null, null,
        'N', 'Y', 'M', '0', '0', '', 'build', null,
        null, 1, now(), 'Agent 平台目录');

insert into sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param,
                      is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu,
                      create_dept, create_by, create_time, remark)
values (1761400000000030002, '工作空间', 1761400000000030001, 14, 'workspace', 'agent/workspace/index', null,
        'N', 'Y', 'C', '0', '0', 'agent:workspace:list', 'peoples', null,
        null, 1, now(), '工作空间管理');

insert into sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param,
                      is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu,
                      create_dept, create_by, create_time, remark)
values
(1761400000000030010, '空间查询', 1761400000000030002, 1, '', null, null, 'N', 'Y', 'F', '0', '0', 'agent:workspace:query', '#', null, null, 1, now(), ''),
(1761400000000030011, '空间新增', 1761400000000030002, 2, '', null, null, 'N', 'Y', 'F', '0', '0', 'agent:workspace:add', '#', null, null, 1, now(), ''),
(1761400000000030012, '空间修改', 1761400000000030002, 3, '', null, null, 'N', 'Y', 'F', '0', '0', 'agent:workspace:edit', '#', null, null, 1, now(), ''),
(1761400000000030013, '空间删除', 1761400000000030002, 4, '', null, null, 'N', 'Y', 'F', '0', '0', 'agent:workspace:remove', '#', null, null, 1, now(), ''),
(1761400000000030014, '成员管理', 1761400000000030002, 5, '', null, null, 'N', 'Y', 'F', '0', '0', 'agent:workspace:member', '#', null, null, 1, now(), '');
