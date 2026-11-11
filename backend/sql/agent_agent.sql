-- 防止客户端字符集不是 utf8mb4 时中文被双重编码
SET NAMES utf8mb4;
-- =====================================================================
-- Agent 平台 · M2 Agent 核心 初始化 SQL(Agent 定义与配置)
-- 目标库: forge-vue
-- =====================================================================

-- ----------------------------
-- 1. Agent 定义表
-- ----------------------------
drop table if exists agent_info;
create table agent_info
(
    id            bigint        not null auto_increment comment '主键',
    workspace_id  bigint        not null comment '所属工作空间ID',
    name          varchar(50)   not null comment 'Agent名称(空间内唯一,2-50字符)',
    description   varchar(200)  default '' comment 'Agent描述',
    system_prompt text          null comment '系统提示词',
    welcome_msg   varchar(500)  default '' comment '欢迎语',
    model_id      bigint        null comment '绑定模型配置ID',
    temperature   decimal(3, 2) default 0.70 comment '采样温度(0-2)',
    max_tokens    int           default 4096 comment '最大输出tokens',
    tool_ids      varchar(500)  default '' comment '绑定工具ID列表(逗号分隔)',
    knowledge_ids varchar(500)  default '' comment '绑定知识库ID列表(逗号分隔)',
    workflow_json longtext      null comment '工作流编排JSON(画布,预留)',
    status        char(1)       default '0' comment '状态(0草稿 1已发布 2已停用)',
    create_dept   bigint        null comment '创建部门',
    create_by     bigint        null comment '创建者',
    create_time   datetime      null comment '创建时间',
    update_by     bigint        null comment '更新者',
    update_time   datetime      null comment '更新时间',
    del_flag      bigint        default 0 comment '删除标志(0存在 其他删除)',
    primary key (id),
    key idx_workspace (workspace_id),
    key idx_model (model_id)
) engine = innodb
  auto_increment = 1
  comment = 'Agent 定义' collate = utf8mb4_unicode_ci;

-- ----------------------------
-- 2. 菜单: Agent 平台 -> Agent 管理
-- ----------------------------
insert into sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param,
                      is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu,
                      create_dept, create_by, create_time, remark)
values (1761400000000030201, 'Agent管理', 1761400000000030001, 2, 'agent', 'agent/agent/index', null,
        'N', 'Y', 'C', '0', '0', 'agent:agent:list', 'star', null,
        null, 1, now(), 'Agent 定义与配置管理');

insert into sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param,
                      is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu,
                      create_dept, create_by, create_time, remark)
values
(1761400000000030202, 'Agent查询', 1761400000000030201, 1, '', null, null, 'N', 'Y', 'F', '0', '0', 'agent:agent:query', '#', null, null, 1, now(), ''),
(1761400000000030203, 'Agent新增', 1761400000000030201, 2, '', null, null, 'N', 'Y', 'F', '0', '0', 'agent:agent:add', '#', null, null, 1, now(), ''),
(1761400000000030204, 'Agent修改', 1761400000000030201, 3, '', null, null, 'N', 'Y', 'F', '0', '0', 'agent:agent:edit', '#', null, null, 1, now(), ''),
(1761400000000030205, 'Agent删除', 1761400000000030201, 4, '', null, null, 'N', 'Y', 'F', '0', '0', 'agent:agent:remove', '#', null, null, 1, now(), '');
