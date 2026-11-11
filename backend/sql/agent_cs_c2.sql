-- =====================================================================
-- 智能客服 C2 运营: 质检规则种子 + 4 个运营菜单
-- 目标库: forge-vue
-- 幂等: 按 pattern / menu_id 判断,可重复执行
-- 表结构复用 agent_cs_c0.sql(13 张表已建),本脚本不建表
-- =====================================================================
SET NAMES utf8mb4;

-- 平台级质检规则(workspace_id IS NULL 表示全空间生效)
INSERT INTO agent_cs_quality_rule (workspace_id, rule_type, pattern, score_delta, severity, enabled, create_time)
SELECT NULL, 'KEYWORD', '不知道', -10, 'WARN', '1', now() FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM agent_cs_quality_rule WHERE workspace_id IS NULL AND pattern = '不知道');

INSERT INTO agent_cs_quality_rule (workspace_id, rule_type, pattern, score_delta, severity, enabled, create_time)
SELECT NULL, 'KEYWORD', '不归我管', -20, 'HIGH', '1', now() FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM agent_cs_quality_rule WHERE workspace_id IS NULL AND pattern = '不归我管');

INSERT INTO agent_cs_quality_rule (workspace_id, rule_type, pattern, score_delta, severity, enabled, create_time)
SELECT NULL, 'KEYWORD', '自己看', -15, 'WARN', '1', now() FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM agent_cs_quality_rule WHERE workspace_id IS NULL AND pattern = '自己看');

INSERT INTO agent_cs_quality_rule (workspace_id, rule_type, pattern, score_delta, severity, enabled, create_time)
SELECT NULL, 'KEYWORD', '没办法', -10, 'WARN', '1', now() FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM agent_cs_quality_rule WHERE workspace_id IS NULL AND pattern = '没办法');

INSERT INTO agent_cs_quality_rule (workspace_id, rule_type, pattern, score_delta, severity, enabled, create_time)
SELECT NULL, 'KEYWORD', '保证', -5, 'LOW', '1', now() FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM agent_cs_quality_rule WHERE workspace_id IS NULL AND pattern = '保证');

-- 4 个运营菜单(与「坐席工作台」同级,挂在「智能客服」目录下)
INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param,
                      is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu,
                      create_dept, create_by, create_time, remark)
SELECT 1761400000000040003, '会话满意度', 1761400000000040001, 2, 'csat',
       'customerservice/CsatBoard', null, 'N', 'Y', 'C', '0', '0',
       'customerservice:csat:list', 'rate', null, null, 1, now(), 'CSAT 邀请与评分'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE menu_id = 1761400000000040003);

INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param,
                      is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu,
                      create_dept, create_by, create_time, remark)
SELECT 1761400000000040004, '质检管理', 1761400000000040001, 3, 'quality',
       'customerservice/QualityBoard', null, 'N', 'Y', 'C', '0', '0',
       'customerservice:quality:list', 'skill', null, null, 1, now(), '质检规则与抽检结果'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE menu_id = 1761400000000040004);

INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param,
                      is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu,
                      create_dept, create_by, create_time, remark)
SELECT 1761400000000040005, '知识答案', 1761400000000040001, 4, 'knowledge',
       'customerservice/KnowledgeAnswers', null, 'N', 'Y', 'C', '0', '0',
       'customerservice:knowledge:list', 'documentation', null, null, 1, now(), 'FAQ 补录与审核生效期'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE menu_id = 1761400000000040005);

INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param,
                      is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu,
                      create_dept, create_by, create_time, remark)
SELECT 1761400000000040006, '运营看板', 1761400000000040001, 5, 'dashboard',
       'customerservice/Dashboard', null, 'N', 'Y', 'C', '0', '0',
       'customerservice:metric:list', 'chart', null, null, 1, now(), '首响/解决率/转人工率/CSAT/SLA'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE menu_id = 1761400000000040006);
