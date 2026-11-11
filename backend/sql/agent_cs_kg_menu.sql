-- =====================================================================
-- P3 / M3 后续: 领域知识图谱维护页菜单
-- 目标库: forge-vue
-- 挂在「智能客服」目录下(与意图管理/辅助看板同级),控制器为
-- CsKnowledgeGraphController(/agent/customerservice/knowledge/graph/*),
-- 坐席登录态 + CsWorkspaceResolver 解析空间,权限沿用 @SaCheckLogin。
-- 幂等: WHERE NOT EXISTS,可重复执行
-- =====================================================================
SET NAMES utf8mb4;

-- 运营菜单:领域知识图谱(挂在「智能客服」目录下)
INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param,
                      is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu,
                      create_dept, create_by, create_time, remark)
SELECT 1761400000000040012, '知识图谱', 1761400000000040001, 8, 'knowledge-graph',
       'customerservice/KnowledgeGraph', null, 'N', 'Y', 'C', '0', '0',
       'customerservice:kg:list', 'guide', null, null, 1, now(), '领域三元组登记/冲突盘点/实体盘点'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE menu_id = 1761400000000040012);
