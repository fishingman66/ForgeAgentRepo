-- 防止客户端字符集不是 utf8mb4 时中文被双重编码
SET NAMES utf8mb4;
-- =====================================================================
-- Agent 平台 · 触发器(Cron 定时 + Webhook 事件)增量 SQL
-- 目标库: forge-vue (MySQL 8, 不支持 ADD COLUMN IF NOT EXISTS)
-- =====================================================================

-- ----------------------------
-- 1. 触发器配置表
-- ----------------------------
drop table if exists agent_trigger;
create table agent_trigger
(
    id              bigint       not null auto_increment comment '主键',
    name            varchar(50)  not null comment '触发器名称',
    workspace_id    bigint       null comment '工作空间ID',
    trigger_type    varchar(10)  not null default 'cron' comment '触发类型(cron定时/webhook事件)',
    target_type     varchar(10)  not null default 'workflow' comment '目标类型(agent对话/workflow工作流)',
    target_id       bigint       not null comment '目标ID(AgentID;工作流即该 Agent 的工作流)',
    inputs          text         null comment '输入模板(agent:消息文本;workflow:输入变量JSON)',
    cron_expr       varchar(50)  null comment 'Cron表达式(定时触发器必填)',
    secret          varchar(64)  null comment 'Webhook密钥(事件触发器必填)',
    enabled         char(1)      default '0' comment '是否启用(0启用 1停用)',
    last_run_time   datetime     null comment '最近执行时间',
    last_run_status varchar(10)  null comment '最近执行状态(success/fail)',
    run_count       int          default 0 comment '累计执行次数',
    remark          varchar(200) default '' comment '备注',
    create_dept     bigint       null comment '创建部门',
    create_by       bigint       null comment '创建者',
    create_time     datetime     null comment '创建时间',
    update_by       bigint       null comment '更新者',
    update_time     datetime     null comment '更新时间',
    del_flag        bigint       default 0 comment '删除标志(0存在 其他删除)',
    primary key (id),
    unique key uk_trigger_secret (secret),
    key idx_trigger_enabled (enabled)
) engine = innodb
  auto_increment = 1
  comment = 'Agent 触发器' collate = utf8mb4_unicode_ci;

-- ----------------------------
-- 2. 触发器执行记录表
-- ----------------------------
drop table if exists agent_trigger_log;
create table agent_trigger_log
(
    id            bigint       not null auto_increment comment '主键',
    trigger_id    bigint       not null comment '触发器ID',
    trigger_name  varchar(50)  default '' comment '触发器名称(冗余便于日志检索)',
    trigger_type  varchar(10)  default '' comment '触发类型(cron/webhook)',
    target_type   varchar(10)  default '' comment '目标类型(agent/workflow)',
    target_id     bigint       null comment '目标ID',
    invoke_source varchar(10)  default 'cron' comment '调用来源(cron定时/webhook事件/manual手动)',
    status        varchar(10)  default 'success' comment '执行状态(success/fail)',
    message       varchar(500) default '' comment '执行结果说明',
    cost_ms       bigint       default 0 comment '执行耗时(毫秒)',
    run_time      datetime     null comment '执行时间',
    create_dept   bigint       null comment '创建部门',
    create_by     bigint       null comment '创建者',
    create_time   datetime     null comment '创建时间',
    update_by     bigint       null comment '更新者',
    update_time   datetime     null comment '更新时间',
    del_flag      bigint       default 0 comment '删除标志(0存在 其他删除)',
    primary key (id),
    key idx_log_trigger (trigger_id)
) engine = innodb
  auto_increment = 1
  comment = 'Agent 触发器执行记录' collate = utf8mb4_unicode_ci;

-- ----------------------------
-- 3. 菜单: 触发器管理
-- ----------------------------
insert into sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param,
                      is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu,
                      create_dept, create_by, create_time, remark)
values (1761400000000030611, '触发器', 1761400000000030001, 12, 'trigger', 'agent/trigger/index', null,
        'N', 'Y', 'C', '0', '0', 'agent:trigger:list', 'time', null,
        null, 1, now(), 'Cron 定时 / Webhook 事件触发器');

insert into sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param,
                      is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu,
                      create_dept, create_by, create_time, remark)
values
(1761400000000030612, '触发器查询', 1761400000000030611, 1, '', null, null, 'N', 'Y', 'F', '0', '0', 'agent:trigger:query', '#', null, null, 1, now(), ''),
(1761400000000030613, '触发器新增', 1761400000000030611, 2, '', null, null, 'N', 'Y', 'F', '0', '0', 'agent:trigger:add', '#', null, null, 1, now(), ''),
(1761400000000030614, '触发器修改', 1761400000000030611, 3, '', null, null, 'N', 'Y', 'F', '0', '0', 'agent:trigger:edit', '#', null, null, 1, now(), ''),
(1761400000000030615, '触发器删除', 1761400000000030611, 4, '', null, null, 'N', 'Y', 'F', '0', '0', 'agent:trigger:remove', '#', null, null, 1, now(), ''),
(1761400000000030616, '触发器执行', 1761400000000030611, 5, '', null, null, 'N', 'Y', 'F', '0', '0', 'agent:trigger:run', '#', null, null, 1, now(), '');
