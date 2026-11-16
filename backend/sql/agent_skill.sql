-- 防止客户端字符集不是 utf8mb4 时中文被双重编码
SET NAMES utf8mb4;
-- =====================================================================
-- 08方案 / A1: 声明式技能包(agent_skill) + 技能安装绑定(agent_skill_install)
-- 目标库: forge-vue
-- 说明: Skill = Markdown 操作手册 + 工具/MCP/知识库引用 + 提示词片段,
--       平台解释执行,不含任何可执行代码(「不做可执行插件」决策)。
--       agent_info 不加列,绑定关系走 agent_skill_install。
-- =====================================================================

-- ----------------------------
-- 1. 技能包定义表
-- ----------------------------
drop table if exists agent_skill;
create table agent_skill
(
    id              bigint        not null auto_increment comment '主键',
    name            varchar(50)   not null comment '技能名称(同一空间内唯一)',
    description     varchar(100)  default '' comment '一行摘要(懒加载目录展示用)',
    instructions    mediumtext    null comment '操作手册(Markdown,模型按需加载)',
    tool_ids        varchar(500)  default '' comment '引用平台工具ID列表(逗号分隔)',
    mcp_ids         varchar(500)  default '' comment '引用MCP服务器ID列表(逗号分隔)',
    knowledge_ids   varchar(500)  default '' comment '引用知识库ID列表(逗号分隔)',
    prompt_fragment varchar(2000) default '' comment '加载后追加的提示词片段',
    version         int           default 1 comment '技能版本(内容变更自增)',
    status          char(1)       default '0' comment '状态(0正常 1停用)',
    workspace_id    bigint        null comment '归属工作空间(NULL=平台共享)',
    remark          varchar(200)  default '' comment '备注',
    create_dept     bigint        null comment '创建部门',
    create_by       bigint        null comment '创建者',
    create_time     datetime      null comment '创建时间',
    update_by       bigint        null comment '更新者',
    update_time     datetime      null comment '更新时间',
    del_flag        bigint        default 0 comment '删除标志(0存在 其他删除)',
    primary key (id)
) engine = innodb
  auto_increment = 1
  comment = 'Agent 声明式技能包' collate = utf8mb4_unicode_ci;

-- ----------------------------
-- 2. 技能-应用安装绑定表
-- ----------------------------
drop table if exists agent_skill_install;
create table agent_skill_install
(
    id           bigint   not null auto_increment comment '主键',
    skill_id     bigint   not null comment '技能ID',
    agent_id     bigint   not null comment 'AgentID',
    workspace_id bigint   null comment '安装时技能归属空间(审计)',
    create_dept  bigint   null comment '创建部门',
    create_by    bigint   null comment '安装人',
    create_time  datetime null comment '安装时间',
    update_by    bigint   null comment '更新者',
    update_time  datetime null comment '更新时间',
    del_flag     bigint   default 0 comment '删除标志(0存在 其他删除)',
    primary key (id),
    unique key uk_skill_agent (skill_id, agent_id)
) engine = innodb
  auto_increment = 1
  comment = 'Agent 技能安装绑定' collate = utf8mb4_unicode_ci;

-- ----------------------------
-- 3. 菜单: 技能管理（号段 30181~30186；30161~30166 是记忆管理菜单、30171~30176 是数据源管理，勿撞号）
-- ----------------------------
delete from sys_menu where menu_id between 1761400000000030181 and 1761400000000030186;
insert into sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param,
                      is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu,
                      create_dept, create_by, create_time, remark)
values (1761400000000030181, '技能管理', 1761400000000030001, 11, 'skill', 'agent/skill/index', null,
        'N', 'Y', 'C', '0', '0', 'agent:skill:list', 'skill', null,
        null, 1, now(), 'Agent 声明式技能包管理');

insert into sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param,
                      is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu,
                      create_dept, create_by, create_time, remark)
values
(1761400000000030182, '技能查询', 1761400000000030181, 1, '', null, null, 'N', 'Y', 'F', '0', '0', 'agent:skill:query', '#', null, null, 1, now(), ''),
(1761400000000030183, '技能新增', 1761400000000030181, 2, '', null, null, 'N', 'Y', 'F', '0', '0', 'agent:skill:add', '#', null, null, 1, now(), ''),
(1761400000000030184, '技能修改', 1761400000000030181, 3, '', null, null, 'N', 'Y', 'F', '0', '0', 'agent:skill:edit', '#', null, null, 1, now(), ''),
(1761400000000030185, '技能删除', 1761400000000030181, 4, '', null, null, 'N', 'Y', 'F', '0', '0', 'agent:skill:remove', '#', null, null, 1, now(), ''),
(1761400000000030186, '技能安装', 1761400000000030181, 5, '', null, null, 'N', 'Y', 'F', '0', '0', 'agent:skill:install', '#', null, null, 1, now(), '');
