-- =====================================================================
-- 智能客服菜单整理(分组化 + 顶级排序)
-- 目标库: forge-vue
-- 幂等: 分组目录 INSERT ... WHERE NOT EXISTS；叶子节点 UPDATE 精确命中 menu_id
-- 说明: ① 智能客服提到 Agent平台 之后(order_num=2)；
--       ② 11 个平铺页收进 3 个分组目录,与 Agent平台 的 M 型分组同构
--          (component 为 NULL,path 为一级路径段,perms 留空)。
-- =====================================================================
SET NAMES utf8mb4;

-- ---------------- 1. 顶级顺序:智能客服紧跟 Agent平台 ----------------
-- 只把它设成 2 不够:系统管理在部分环境里也是 2(或 1),会靠 menu_id 排到前面。
-- 因此固定 Agent平台=1、智能客服=2,其余一级目录从 3 起按原顺序重排——两个环境结果一致。
UPDATE sys_menu SET order_num = 1 WHERE menu_id = 1761400000000030001;
UPDATE sys_menu SET order_num = 2 WHERE menu_id = 1761400000000040001;

UPDATE sys_menu m
JOIN (
    SELECT menu_id, ROW_NUMBER() OVER (ORDER BY order_num, menu_id) + 2 AS new_order
    FROM sys_menu
    WHERE parent_id = 0 AND menu_type = 'M'
      AND menu_id NOT IN (1761400000000030001, 1761400000000040001)
) ranked ON ranked.menu_id = m.menu_id
SET m.order_num = ranked.new_order;

-- ---------------- 2. 三个分组目录 ----------------
INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param,
                      is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu,
                      create_dept, create_by, create_time, remark)
SELECT 1761400000000041001, '服务运营', 1761400000000040001, 1, 'service-ops', null, null,
       'N', 'Y', 'M', '0', '0', '', 'online', null,
       null, 1, now(), '坐席工作台 / 运营看板 / 通话记录 / 会话满意度'
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE menu_id = 1761400000000041001);

INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param,
                      is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu,
                      create_dept, create_by, create_time, remark)
SELECT 1761400000000041002, '智能能力', 1761400000000040001, 2, 'ai-capability', null, null,
       'N', 'Y', 'M', '0', '0', '', 'model', null,
       null, 1, now(), '意图管理 / 知识答案 / 知识图谱 / 渠道接入'
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE menu_id = 1761400000000041002);

INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param,
                      is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu,
                      create_dept, create_by, create_time, remark)
SELECT 1761400000000041003, '质量与合规', 1761400000000040001, 3, 'quality-compliance', null, null,
       'N', 'Y', 'M', '0', '0', '', 'clipboard', null,
       null, 1, now(), '质检管理 / 辅助看板 / 合规与模板'
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE menu_id = 1761400000000041003);

-- ---------------- 3. 叶子节点归位(顺带把 order_num 重排成组内顺序) ----------------
-- 服务运营
UPDATE sys_menu SET parent_id = 1761400000000041001, order_num = 1 WHERE menu_id = 1761400000000040002; -- 坐席工作台
UPDATE sys_menu SET parent_id = 1761400000000041001, order_num = 2 WHERE menu_id = 1761400000000040006; -- 运营看板
UPDATE sys_menu SET parent_id = 1761400000000041001, order_num = 3 WHERE menu_id = 1761400000000040009; -- 通话记录
UPDATE sys_menu SET parent_id = 1761400000000041001, order_num = 4 WHERE menu_id = 1761400000000040003; -- 会话满意度

-- 智能能力
UPDATE sys_menu SET parent_id = 1761400000000041002, order_num = 1 WHERE menu_id = 1761400000000040010; -- 意图管理
UPDATE sys_menu SET parent_id = 1761400000000041002, order_num = 2 WHERE menu_id = 1761400000000040005; -- 知识答案
UPDATE sys_menu SET parent_id = 1761400000000041002, order_num = 3 WHERE menu_id = 1761400000000040012; -- 知识图谱
UPDATE sys_menu SET parent_id = 1761400000000041002, order_num = 4 WHERE menu_id = 1761400000000040007; -- 渠道接入

-- 质量与合规
UPDATE sys_menu SET parent_id = 1761400000000041003, order_num = 1 WHERE menu_id = 1761400000000040004; -- 质检管理
UPDATE sys_menu SET parent_id = 1761400000000041003, order_num = 2 WHERE menu_id = 1761400000000040011; -- 辅助看板
UPDATE sys_menu SET parent_id = 1761400000000041003, order_num = 3 WHERE menu_id = 1761400000000040008; -- 合规与模板
