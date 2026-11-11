-- 防止客户端字符集不是 utf8mb4 时中文被双重编码
SET NAMES utf8mb4;
-- =====================================================================
-- Agent 平台 · 运营看板 增量 SQL
-- 目标库: forge-vue
-- 单接口 /agent/dashboard/overview,按角色裁剪 section:
--   平台管理员 agent:dashboard:platform / 空间管理员 agent:dashboard:workspace
-- =====================================================================

-- ----------------------------
-- 1. 菜单: 运营看板(置于 Agent平台 分组首位)
-- ----------------------------
insert into sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param,
                      is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu,
                      create_dept, create_by, create_time, remark)
values (1761400000000030621, '运营看板', 1761400000000030001, 0, 'dashboard', 'agent/dashboard/index', null,
        'N', 'Y', 'C', '0', '0', 'agent:dashboard:list', 'dashboard', null,
        null, 1, now(), '平台 / 空间视角运营看板');

insert into sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param,
                      is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu,
                      create_dept, create_by, create_time, remark)
values
(1761400000000030622, '平台看板', 1761400000000030621, 1, '', null, null, 'N', 'Y', 'F', '0', '0', 'agent:dashboard:platform', '#', null, null, 1, now(), ''),
(1761400000000030623, '空间看板', 1761400000000030621, 2, '', null, null, 'N', 'Y', 'F', '0', '0', 'agent:dashboard:workspace', '#', null, null, 1, now(), '');

-- ----------------------------
-- 2. 角色授权: 普通用户角色(role_key=common)授予页面 + 空间看板权限
--    平台管理员角色由框架超管机制自动放行,无需显式授权
-- ----------------------------
insert ignore into sys_role_menu (role_id, menu_id)
select r.role_id, m.menu_id
from sys_role r
         cross join (select 1761400000000030621 as menu_id
                     union all
                     select 1761400000000030623) m
where r.role_key = 'common'
  and r.del_flag = '0';
