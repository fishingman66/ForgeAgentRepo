-- =====================================================================
-- 智能客服菜单(C1):与「Agent 平台」平级的一级目录 + 坐席工作台
-- 幂等:按 menu_id 判断,可重复执行
-- 前端路由由 sys_menu 驱动(dynamicRoutes = [])
-- =====================================================================
SET NAMES utf8mb4;

INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param,
                      is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu,
                      create_dept, create_by, create_time, remark)
SELECT 1761400000000040001, '智能客服', 0, 9, 'customerservice', null, null,
       'N', 'Y', 'M', '0', '0', '', 'message', null,
       null, 1, now(), '智能客服业务域'
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE menu_id = 1761400000000040001);

INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param,
                      is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu,
                      create_dept, create_by, create_time, remark)
SELECT 1761400000000040002, '坐席工作台', 1761400000000040001, 1, 'workbench',
       'customerservice/Workbench', null,
       'N', 'Y', 'C', '0', '0', 'customerservice:workbench:list', 'peoples', null,
       null, 1, now(), '工单池 / 接单 / 回复 / 结案 / 流转轨迹'
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE menu_id = 1761400000000040002);

-- 修正历史导入:service.svg 在前端图标目录中不存在,symbol #icon-service 无法解析,
-- 改用仓库内置的 message.svg;仅命中错误值,可重复执行,不覆盖管理端后续自定义图标。
UPDATE sys_menu SET icon = 'message'
WHERE menu_id = 1761400000000040001
  AND (icon IS NULL OR icon = '' OR icon = 'service');
