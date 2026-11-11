-- 防止客户端字符集不是 utf8mb4 时中文被双重编码
SET NAMES utf8mb4;
-- =====================================================================
-- Agent 平台 · M7 Token 用量统计 初始化 SQL
-- 目标库: forge-vue
-- =====================================================================

-- ----------------------------
-- 1. Token 用量明细表(每次模型调用一条)
-- ----------------------------
drop table if exists agent_token_usage;
create table agent_token_usage
(
    id                bigint       not null auto_increment comment '主键',
    workspace_id      bigint       not null comment '工作空间ID',
    agent_id          bigint       not null comment 'Agent ID',
    session_id        bigint       null comment '会话ID',
    round_request_id  varchar(64)  default '' comment '回合请求ID(同一回合多次调用共享)',
    user_id           bigint       not null comment '发起人用户ID',
    source            varchar(10)  default 'chat' comment '来源(chat对话/api接口/debug调试)',
    api_key_id        bigint       null comment 'API Key ID(API调用时)',
    model_id          bigint       null comment '模型配置ID',
    model_name        varchar(100) default '' comment '模型标识',
    prompt_tokens     int          default 0 comment '输入token数',
    completion_tokens int          default 0 comment '输出token数',
    total_tokens      int          default 0 comment '总token数',
    estimated         char(1)      default '1' comment '是否估算(0实测 1估算)',
    success           char(1)      default '0' comment '是否成功(0成功 1失败)',
    call_time         datetime     null comment '调用时间',
    create_time       datetime     null comment '记录写入时间',
    primary key (id),
    key idx_ws_time (workspace_id, call_time),
    key idx_agent (agent_id),
    key idx_session (session_id),
    key idx_user (user_id)
) engine = innodb
  auto_increment = 1
  comment = 'Agent Token 用量明细' collate = utf8mb4_unicode_ci;

-- ----------------------------
-- 2. Token 用量日汇总表(由明细异步聚合)
-- ----------------------------
drop table if exists agent_token_usage_daily;
create table agent_token_usage_daily
(
    id                bigint      not null auto_increment comment '主键',
    stat_date         date        not null comment '统计日期',
    workspace_id      bigint      not null comment '工作空间ID',
    agent_id          bigint      not null comment 'Agent ID',
    model_name        varchar(100) default '' comment '模型标识',
    user_id           bigint      not null comment '用户ID',
    source            varchar(10) default 'chat' comment '来源(chat/api/debug)',
    estimated         char(1)     default '1' comment '是否估算(0实测 1估算)',
    prompt_tokens     int         default 0 comment '输入token合计',
    completion_tokens int         default 0 comment '输出token合计',
    total_tokens      int         default 0 comment '总token合计',
    call_count        int         default 0 comment '调用次数',
    create_time       datetime    null comment '创建时间',
    update_time       datetime    null comment '更新时间',
    primary key (id),
    unique key uk_dim (stat_date, workspace_id, agent_id, model_name, user_id, source, estimated),
    key idx_ws_date (workspace_id, stat_date)
) engine = innodb
  auto_increment = 1
  comment = 'Agent Token 用量日汇总' collate = utf8mb4_unicode_ci;

-- ----------------------------
-- 3. 菜单: 用量统计
-- ----------------------------
insert into sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param,
                      is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu,
                      create_dept, create_by, create_time, remark)
values (1761400000000030601, '用量统计', 1761400000000030001, 17, 'usage', 'agent/usage/index', null,
        'N', 'Y', 'C', '0', '0', 'agent:usage:list', 'chart', null,
        null, 1, now(), 'Token 用量统计报表');

insert into sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param,
                      is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu,
                      create_dept, create_by, create_time, remark)
values
(1761400000000030602, '用量查询', 1761400000000030601, 1, '', null, null, 'N', 'Y', 'F', '0', '0', 'agent:usage:query', '#', null, null, 1, now(), ''),
(1761400000000030603, '用量导出', 1761400000000030601, 2, '', null, null, 'N', 'Y', 'F', '0', '0', 'agent:usage:export', '#', null, null, 1, now(), '');
