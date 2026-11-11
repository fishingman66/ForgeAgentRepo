-- =====================================================================
-- Agent 平台 · 工作空间归属部门 增量脚本
-- 目标库: forge-vue
-- 说明: agent_workspace 新增 dept_id(管理归属,可转移),与 create_dept(创建者部门快照,审计语义)分离
-- =====================================================================
SET NAMES utf8mb4;

alter table agent_workspace
    add column dept_id bigint null comment '归属部门ID(管理归属,可转移)' after owner_id,
    add key idx_dept (dept_id);

-- 历史数据回填: 以创建者部门快照作为初始归属部门
update agent_workspace set dept_id = create_dept where dept_id is null;
