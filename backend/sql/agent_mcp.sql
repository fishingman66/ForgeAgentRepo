-- 防止客户端字符集不是 utf8mb4 时中文被双重编码
SET NAMES utf8mb4;
-- =====================================================================
-- Agent 平台 · MCP 服务器管理 + 工具审批中心 增量 SQL
-- 目标库: forge-vue (MySQL 8, 不支持 ADD COLUMN IF NOT EXISTS)
-- =====================================================================

-- ----------------------------
-- 1. MCP 服务器注册表
-- ----------------------------
drop table if exists agent_mcp_server;
create table agent_mcp_server
(
    id               bigint       not null auto_increment comment '主键',
    name             varchar(50)  not null comment 'MCP服务器名称(全局唯一)',
    description      varchar(200) default '' comment '描述',
    server_type      varchar(20)  not null default 'streamable_http' comment '传输类型(sse/streamable_http)',
    url              varchar(300) not null comment 'MCP服务端地址',
    headers          text         null comment '自定义请求头(JSON)',
    timeout_seconds  int          default 30 comment '请求超时(秒)',
    approval_mode    varchar(10)  default 'auto' comment '工具审批模式(auto自动执行/ask需人工审批/deny拒绝)',
    approval_timeout int          default 120 comment 'ask模式审批等待超时(秒)',
    tool_count       int          default 0 comment '最近一次连接测试发现的工具数',
    status           char(1)      default '0' comment '状态(0正常 1停用)',
    remark           varchar(200) default '' comment '备注',
    create_dept      bigint       null comment '创建部门',
    create_by        bigint       null comment '创建者',
    create_time      datetime     null comment '创建时间',
    update_by        bigint       null comment '更新者',
    update_time      datetime     null comment '更新时间',
    del_flag         bigint       default 0 comment '删除标志(0存在 其他删除)',
    primary key (id)
) engine = innodb
  auto_increment = 1
  comment = 'Agent MCP 服务器注册' collate = utf8mb4_unicode_ci;

-- ----------------------------
-- 2. 工具审批单表(HITL 人工审批)
-- ----------------------------
drop table if exists agent_approval;
create table agent_approval
(
    id           bigint       not null auto_increment comment '主键',
    session_id   bigint       not null comment '会话ID',
    agent_id     bigint       not null comment 'AgentID',
    round_id     varchar(64)  default '' comment '请求轮次ID',
    tool_name    varchar(100) not null comment '工具名称',
    tool_source  varchar(20)  default 'mcp' comment '工具来源(mcp/platform-http)',
    server_name  varchar(50)  default '' comment 'MCP服务器名称(平台工具为空)',
    call_args    text         null comment '调用参数(JSON)',
    status       varchar(20)  default 'pending' comment '状态(pending待审批/approved已通过/rejected已拒绝/cancelled已取消/expired已超时)',
    result_note  varchar(500) default '' comment '审批说明/拒绝原因',
    approve_by   bigint       null comment '审批人',
    approve_time datetime     null comment '审批时间',
    create_dept  bigint       null comment '创建部门',
    create_by    bigint       null comment '创建者',
    create_time  datetime     null comment '创建时间',
    update_by    bigint       null comment '更新者',
    update_time  datetime     null comment '更新时间',
    del_flag     bigint       default 0 comment '删除标志(0存在 其他删除)',
    primary key (id),
    key idx_approval_session (session_id),
    key idx_approval_status (status)
) engine = innodb
  auto_increment = 1
  comment = 'Agent 工具审批单' collate = utf8mb4_unicode_ci;

-- ----------------------------
-- 3. agent_info 增加 MCP 绑定列
-- ----------------------------
alter table agent_info
    add column mcp_ids varchar(500) default '' comment '绑定MCP服务器ID列表(逗号分隔)' after knowledge_ids;

-- ----------------------------
-- 4. agent_tool 增加审批配置列
-- ----------------------------
alter table agent_tool
    add column approval_mode varchar(10) default 'auto' comment '审批模式(auto/ask/deny)' after status,
    add column approval_timeout int default 120 comment 'ask模式审批等待超时(秒)' after approval_mode;

-- ----------------------------
-- 5. 菜单: MCP 服务器 / 工具审批中心
-- ----------------------------
insert into sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param,
                      is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu,
                      create_dept, create_by, create_time, remark)
values (1761400000000030141, 'MCP 服务器', 1761400000000030001, 9, 'mcp', 'agent/mcp/index', null,
        'N', 'Y', 'C', '0', '0', 'agent:mcp:list', 'server', null,
        null, 1, now(), 'MCP 服务器连接管理');

insert into sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param,
                      is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu,
                      create_dept, create_by, create_time, remark)
values
(1761400000000030142, 'MCP查询', 1761400000000030141, 1, '', null, null, 'N', 'Y', 'F', '0', '0', 'agent:mcp:query', '#', null, null, 1, now(), ''),
(1761400000000030143, 'MCP新增', 1761400000000030141, 2, '', null, null, 'N', 'Y', 'F', '0', '0', 'agent:mcp:add', '#', null, null, 1, now(), ''),
(1761400000000030144, 'MCP修改', 1761400000000030141, 3, '', null, null, 'N', 'Y', 'F', '0', '0', 'agent:mcp:edit', '#', null, null, 1, now(), ''),
(1761400000000030145, 'MCP删除', 1761400000000030141, 4, '', null, null, 'N', 'Y', 'F', '0', '0', 'agent:mcp:remove', '#', null, null, 1, now(), ''),
(1761400000000030146, 'MCP测试连接', 1761400000000030141, 5, '', null, null, 'N', 'Y', 'F', '0', '0', 'agent:mcp:test', '#', null, null, 1, now(), '');

insert into sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param,
                      is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu,
                      create_dept, create_by, create_time, remark)
values (1761400000000030151, '工具审批中心', 1761400000000030001, 15, 'approval', 'agent/approval/index', null,
        'N', 'Y', 'C', '0', '0', 'agent:approval:list', 'validCode', null,
        null, 1, now(), 'MCP/平台工具人工审批');

insert into sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param,
                      is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu,
                      create_dept, create_by, create_time, remark)
values
(1761400000000030152, '审批查询', 1761400000000030151, 1, '', null, null, 'N', 'Y', 'F', '0', '0', 'agent:approval:query', '#', null, null, 1, now(), ''),
(1761400000000030153, '审批处理', 1761400000000030151, 2, '', null, null, 'N', 'Y', 'F', '0', '0', 'agent:approval:handle', '#', null, null, 1, now(), '');
