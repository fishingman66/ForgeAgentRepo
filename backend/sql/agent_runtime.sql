-- 防止客户端字符集不是 utf8mb4 时中文被双重编码
SET NAMES utf8mb4;
-- =====================================================================
-- Agent 平台 · M3 运行时 初始化 SQL(会话/消息)
-- 目标库: forge-vue
-- =====================================================================

-- ----------------------------
-- 1. 会话表
-- ----------------------------
drop table if exists agent_session;
create table agent_session
(
    id            bigint       not null auto_increment comment '主键',
    agent_id      bigint       not null comment 'AgentID',
    user_id       bigint       not null comment '会话所属用户ID',
    title         varchar(100) default '新会话' comment '会话标题',
    message_count int          default 0 comment '消息数量',
    last_msg_time datetime     null comment '最后消息时间',
    create_dept   bigint       null comment '创建部门',
    create_by     bigint       null comment '创建者',
    create_time   datetime     null comment '创建时间',
    update_by     bigint       null comment '更新者',
    update_time   datetime     null comment '更新时间',
    del_flag      bigint       default 0 comment '删除标志(0存在 其他删除)',
    primary key (id),
    key idx_agent (agent_id),
    key idx_user (user_id)
) engine = innodb
  auto_increment = 1
  comment = 'Agent 会话' collate = utf8mb4_unicode_ci;

-- ----------------------------
-- 2. 消息表(随会话删除,物理删除)
-- ----------------------------
drop table if exists agent_message;
create table agent_message
(
    id          bigint      not null auto_increment comment '主键',
    session_id  bigint      not null comment '会话ID',
    role        varchar(10) not null comment '角色(user/assistant)',
    content     text        null comment '消息内容',
    ext_json    varchar(500) default '' comment '扩展信息(mock执行元数据)',
    create_dept bigint      null comment '创建部门',
    create_by   bigint      null comment '创建者',
    create_time datetime    null comment '创建时间',
    update_by   bigint      null comment '更新者',
    update_time datetime    null comment '更新时间',
    primary key (id),
    key idx_session (session_id)
) engine = innodb
  auto_increment = 1
  comment = 'Agent 会话消息' collate = utf8mb4_unicode_ci;

-- ----------------------------
-- 3. 菜单: Agent 平台 -> 聊天对话
-- ----------------------------
insert into sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param,
                      is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu,
                      create_dept, create_by, create_time, remark)
values (1761400000000030301, '聊天对话', 1761400000000030001, 1, 'chat', 'agent/chat/index', null,
        'N', 'Y', 'C', '0', '0', 'agent:session:list', 'message', null,
        null, 1, now(), 'Agent 会话与消息');

insert into sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param,
                      is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu,
                      create_dept, create_by, create_time, remark)
values
(1761400000000030302, '会话查询', 1761400000000030301, 1, '', null, null, 'N', 'Y', 'F', '0', '0', 'agent:session:query', '#', null, null, 1, now(), ''),
(1761400000000030303, '会话新增', 1761400000000030301, 2, '', null, null, 'N', 'Y', 'F', '0', '0', 'agent:session:add', '#', null, null, 1, now(), ''),
(1761400000000030304, '会话修改', 1761400000000030301, 3, '', null, null, 'N', 'Y', 'F', '0', '0', 'agent:session:edit', '#', null, null, 1, now(), ''),
(1761400000000030305, '会话删除', 1761400000000030301, 4, '', null, null, 'N', 'Y', 'F', '0', '0', 'agent:session:remove', '#', null, null, 1, now(), ''),
(1761400000000030306, '消息发送', 1761400000000030301, 5, '', null, null, 'N', 'Y', 'F', '0', '0', 'agent:chat:send', '#', null, null, 1, now(), '');
