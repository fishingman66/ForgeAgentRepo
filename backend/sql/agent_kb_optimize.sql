-- ----------------------------
-- 知识库检索质量优化(2026-10-19)
-- 1. agent_knowledge 新增相关度阈值 score_threshold(0~1,作用于向量相似度/精排分,NULL 不过滤)
-- 2. agent_kb_chunk 新增分块管理字段:enabled(启停)/token_count(估算token数)/hit_count(累计命中次数)
-- 3. sys_menu 新增分块管理按钮权限 agent:knowledgeDoc:chunk
-- ----------------------------

-- 1. 知识库相关度阈值
ALTER TABLE `agent_knowledge`
    ADD COLUMN `score_threshold` DECIMAL(4,3) NULL DEFAULT NULL COMMENT '相关度阈值(0~1,作用于向量相似度/精排分,低于该分数的分块不召回;NULL 不过滤)' AFTER `rerank_model`;

-- 2. 分块管理字段
ALTER TABLE `agent_kb_chunk`
    ADD COLUMN `enabled` TINYINT(1) NOT NULL DEFAULT 1 COMMENT '是否启用(1启用 0禁用,禁用不参与检索)' AFTER `content`,
    ADD COLUMN `token_count` INT NOT NULL DEFAULT 0 COMMENT '估算 token 数(中文按字、英文按词)' AFTER `enabled`,
    ADD COLUMN `hit_count` INT NOT NULL DEFAULT 0 COMMENT '累计检索命中次数' AFTER `token_count`;

-- 存量分块 token 数回填(字符数上界估计,新增/编辑的分块由后端精确估算)
UPDATE `agent_kb_chunk` SET `token_count` = CHAR_LENGTH(`content`) WHERE `token_count` = 0;

-- 3. 分块管理按钮权限(父菜单:知识库 1761400000000030121)
insert into sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param,
                      is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu,
                      create_dept, create_by, create_time, remark)
select 1761400000000030132, '分块管理', 1761400000000030121, 11, '', null, null, 'N', 'Y', 'F', '0', '0',
       'agent:knowledgeDoc:chunk', '#', null, null, 1, now(), ''
from dual where not exists (select 1 from sys_menu where menu_id = 1761400000000030132);

-- 超管角色授权(与既有知识库按钮一致)
insert into sys_role_menu (role_id, menu_id)
select 1, 1761400000000030132
from dual where not exists (select 1 from sys_role_menu where role_id = 1 and menu_id = 1761400000000030132);
