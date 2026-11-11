-- 防止客户端字符集不是 utf8mb4 时中文被双重编码
SET NAMES utf8mb4;
-- =====================================================================
-- Agent 平台 · M6 平台管理与安全 初始化 SQL
-- 目标库: forge-vue
-- =====================================================================

-- ----------------------------
-- 1. 模型供应商配置表(平台级)
-- ----------------------------
drop table if exists agent_provider;
create table agent_provider
(
    id            bigint       not null auto_increment comment '主键',
    provider_code varchar(30)  not null comment '供应商编码(唯一,如 openai/anthropic/dashscope/ollama/custom)',
    provider_name varchar(50)  not null comment '供应商展示名',
    status        char(1)      default '0' comment '状态(0正常 1停用)',
    remark        varchar(200) default '' comment '备注',
    create_dept   bigint       null comment '创建部门',
    create_by     bigint       null comment '创建者',
    create_time   datetime     null comment '创建时间',
    update_by     bigint       null comment '更新者',
    update_time   datetime     null comment '更新时间',
    del_flag      bigint       default 0 comment '删除标志(0存在 其他删除)',
    primary key (id),
    unique key uk_code (provider_code)
) engine = innodb
  auto_increment = 1
  comment = 'Agent 模型供应商配置(平台级)' collate = utf8mb4_unicode_ci;

-- 预置常见供应商
insert into agent_provider (provider_code, provider_name, status, remark, create_by, create_time)
values
('openai', 'OpenAI', '0', 'GPT 系列模型', 1, now()),
('anthropic', 'Anthropic', '0', 'Claude 系列模型', 1, now()),
('dashscope', '阿里云百炼', '0', '通义千问系列模型', 1, now()),
('ollama', 'Ollama', '0', '本地开源模型', 1, now()),
('custom', '自定义', '0', 'OpenAI 兼容接口', 1, now());

-- ----------------------------
-- 2. 空间 API Key 表(明文仅创建时展示一次,库存哈希)
-- ----------------------------
drop table if exists agent_api_key;
create table agent_api_key
(
    id             bigint       not null auto_increment comment '主键',
    workspace_id   bigint       not null comment '所属工作空间ID',
    name           varchar(50)  not null comment 'Key 名称',
    key_prefix     varchar(20)  not null comment 'Key 脱敏前缀(如 ak-x1y2...)',
    key_hash       varchar(128) not null comment 'Key 哈希(SHA-256)',
    status         char(1)      default '0' comment '状态(0正常 1已吊销)',
    last_used_time datetime     null comment '最后使用时间',
    create_dept    bigint       null comment '创建部门',
    create_by      bigint       null comment '创建者',
    create_time    datetime     null comment '创建时间',
    update_by      bigint       null comment '更新者',
    update_time    datetime     null comment '更新时间',
    del_flag       bigint       default 0 comment '删除标志(0存在 其他删除)',
    primary key (id),
    key idx_workspace (workspace_id),
    unique key uk_hash (key_hash)
) engine = innodb
  auto_increment = 1
  comment = 'Agent 空间 API Key' collate = utf8mb4_unicode_ci;

-- ----------------------------
-- 3. 审计日志表(只增不改,物理保留)
-- ----------------------------
drop table if exists agent_audit_log;
create table agent_audit_log
(
    id            bigint       not null auto_increment comment '主键',
    workspace_id  bigint       null comment '所属工作空间ID(平台事件为空)',
    event_type    varchar(40)  not null comment '事件类型(如 member.role/credential.create/agent.publish/market.submit/apikey.revoke)',
    target_type   varchar(30)  default '' comment '目标对象类型',
    target_id     varchar(64)  default '' comment '目标对象ID',
    source_channel varchar(20) default 'web' comment '来源 web/api_key/system',
    client_ip     varchar(50)  default '' comment '客户端IP',
    user_agent    varchar(255) default '' comment 'User-Agent',
    result        char(1)      default '0' comment '操作结果(0成功 1失败)',
    severity      varchar(10)  default 'INFO' comment '事件级别(INFO/WARN/RISK)',
    trace_id      varchar(64)  default '' comment '链路追踪ID',
    old_value     text         null comment '变更前快照(JSON)',
    new_value     text         null comment '变更后快照(JSON)',
    detail        varchar(500) default '' comment '事件详情描述',
    operator_id   bigint       not null comment '操作人用户ID',
    operator_name varchar(30)  default '' comment '操作人用户名',
    create_time   datetime     null comment '创建时间',
    primary key (id),
    key idx_workspace (workspace_id),
    key idx_event (event_type),
    key idx_create_time (create_time),
    key idx_severity (severity),
    key idx_trace (trace_id),
    key idx_result (result)
) engine = innodb
  auto_increment = 1
  comment = 'Agent 平台审计日志' collate = utf8mb4_unicode_ci;

-- ----------------------------
-- 4. 菜单: 供应商配置 / API密钥 / 审计日志
-- ----------------------------
insert into sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param,
                      is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu,
                      create_dept, create_by, create_time, remark)
values (1761400000000030501, '供应商配置', 1761400000000030001, 5, 'provider', 'agent/provider/index', null,
        'N', 'Y', 'C', '0', '0', 'agent:provider:list', 'server', null,
        null, 1, now(), '模型供应商配置(平台管理员)');

insert into sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param,
                      is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu,
                      create_dept, create_by, create_time, remark)
values
(1761400000000030502, '供应商查询', 1761400000000030501, 1, '', null, null, 'N', 'Y', 'F', '0', '0', 'agent:provider:query', '#', null, null, 1, now(), ''),
(1761400000000030503, '供应商新增', 1761400000000030501, 2, '', null, null, 'N', 'Y', 'F', '0', '0', 'agent:provider:add', '#', null, null, 1, now(), ''),
(1761400000000030504, '供应商修改', 1761400000000030501, 3, '', null, null, 'N', 'Y', 'F', '0', '0', 'agent:provider:edit', '#', null, null, 1, now(), ''),
(1761400000000030505, '供应商删除', 1761400000000030501, 4, '', null, null, 'N', 'Y', 'F', '0', '0', 'agent:provider:remove', '#', null, null, 1, now(), '');

insert into sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param,
                      is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu,
                      create_dept, create_by, create_time, remark)
values (1761400000000030511, 'API密钥', 1761400000000030001, 13, 'apikey', 'agent/apikey/index', null,
        'N', 'Y', 'C', '0', '0', 'agent:apikey:list', 'key', null,
        null, 1, now(), '空间级 API Key 管理');

insert into sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param,
                      is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu,
                      create_dept, create_by, create_time, remark)
values
(1761400000000030512, 'Key查询', 1761400000000030511, 1, '', null, null, 'N', 'Y', 'F', '0', '0', 'agent:apikey:query', '#', null, null, 1, now(), ''),
(1761400000000030513, 'Key创建', 1761400000000030511, 2, '', null, null, 'N', 'Y', 'F', '0', '0', 'agent:apikey:add', '#', null, null, 1, now(), ''),
(1761400000000030514, 'Key吊销', 1761400000000030511, 3, '', null, null, 'N', 'Y', 'F', '0', '0', 'agent:apikey:revoke', '#', null, null, 1, now(), '');

insert into sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param,
                      is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu,
                      create_dept, create_by, create_time, remark)
values (1761400000000030521, '审计日志', 1761400000000030001, 19, 'audit', 'agent/audit/index', null,
        'N', 'Y', 'C', '0', '0', 'agent:audit:list', 'form', null,
        null, 1, now(), '平台与空间审计日志');

insert into sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param,
                      is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu,
                      create_dept, create_by, create_time, remark)
values
(1761400000000030522, '日志查询', 1761400000000030521, 1, '', null, null, 'N', 'Y', 'F', '0', '0', 'agent:audit:query', '#', null, null, 1, now(), '');
