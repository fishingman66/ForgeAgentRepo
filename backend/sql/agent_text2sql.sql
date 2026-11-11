-- 防止客户端字符集不是 utf8mb4 时中文被双重编码
SET NAMES utf8mb4;
-- =====================================================================
-- Agent 平台 · 智能问数(NL2SQL) P0 增量 SQL
-- 目标库: forge-vue (MySQL 8, 不支持 ADD COLUMN IF NOT EXISTS)
-- =====================================================================

-- ----------------------------
-- 1. 问数数据源注册表
-- ----------------------------
drop table if exists agent_datasource;
create table agent_datasource
(
    id               bigint       not null auto_increment comment '主键',
    name             varchar(50)  not null comment '数据源名称(全局唯一)',
    description      varchar(200) default '' comment '描述',
    db_type          varchar(20)  not null default 'mysql' comment '数据库类型(mysql)',
    host             varchar(200) not null comment '主机地址',
    port             int          not null default 3306 comment '端口',
    db_name          varchar(100) not null comment '数据库名',
    username         varchar(100) not null comment '连接用户名(须为只读账号)',
    password         varchar(500) not null comment '连接密码(AES加密存储)',
    params           varchar(500) default '' comment '额外JDBC参数(如 useSSL=false&serverTimezone=Asia/Shanghai)',
    max_rows         int          default 500 comment '单次查询最大返回行数',
    timeout_seconds  int          default 15 comment '查询超时(秒)',
    table_whitelist  varchar(2000) not null default '' comment '可查询表白名单(逗号分隔,空为全部)',
    schema_snapshot  mediumtext   null comment '表结构快照(注入问数提示词)',
    schema_tables    mediumtext   null comment '表结构JSON(名称/注释/列明细,供describe工具按需取用)',
    schema_synced_at datetime     null comment '表结构最近同步时间',
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
  comment = 'Agent 问数数据源' collate = utf8mb4_unicode_ci;

-- ----------------------------
-- 2. agent_tool 增加 sql 工具类型的数据源绑定列
-- ----------------------------
alter table agent_tool
    add column datasource_id bigint null comment '绑定数据源ID(仅sql类型工具)' after tool_type;

-- ----------------------------
-- 4. 会话消息 ext_json 扩容:问数结果(列名+多行数据)随助手消息落库,原 varchar(500) 不足
-- ----------------------------
alter table agent_message
    modify column ext_json mediumtext null comment '扩展信息(工具执行/问数结果等元数据)';

-- ----------------------------
-- 3. 菜单: 数据源管理(挂在 Agent 管理目录下, 序 8)
-- ----------------------------
insert into sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param,
                      is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu,
                      create_dept, create_by, create_time, remark)
values (1761400000000030171, '数据源管理', 1761400000000030001, 10, 'datasource', 'agent/datasource/index', null,
        'N', 'Y', 'C', '0', '0', 'agent:datasource:list', 'database', null,
        null, 1, now(), '智能问数数据源管理');

insert into sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param,
                      is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu,
                      create_dept, create_by, create_time, remark)
values
(1761400000000030172, '数据源查询', 1761400000000030171, 1, '', null, null, 'N', 'Y', 'F', '0', '0', 'agent:datasource:query', '#', null, null, 1, now(), ''),
(1761400000000030173, '数据源新增', 1761400000000030171, 2, '', null, null, 'N', 'Y', 'F', '0', '0', 'agent:datasource:add', '#', null, null, 1, now(), ''),
(1761400000000030174, '数据源修改', 1761400000000030171, 3, '', null, null, 'N', 'Y', 'F', '0', '0', 'agent:datasource:edit', '#', null, null, 1, now(), ''),
(1761400000000030175, '数据源删除', 1761400000000030171, 4, '', null, null, 'N', 'Y', 'F', '0', '0', 'agent:datasource:remove', '#', null, null, 1, now(), ''),
(1761400000000030176, '数据源测试与同步', 1761400000000030171, 5, '', null, null, 'N', 'Y', 'F', '0', '0', 'agent:datasource:test', '#', null, null, 1, now(), '');

-- ----------------------------
-- 5. 问数示例库(问题→SQL,few-shot 注入提示词)
-- ----------------------------
drop table if exists agent_datasource_example;
create table agent_datasource_example
(
    id            bigint       not null auto_increment comment '主键',
    datasource_id bigint       not null comment '数据源ID',
    question      varchar(500) not null comment '示例问题',
    sql_content   text         not null comment '示例SQL',
    sort          int          default 0 comment '排序',
    status        char(1)      default '0' comment '状态(0正常 1停用)',
    remark        varchar(200) default '' comment '备注',
    create_dept   bigint       null comment '创建部门',
    create_by     bigint       null comment '创建者',
    create_time   datetime     null comment '创建时间',
    update_by     bigint       null comment '更新者',
    update_time   datetime     null comment '更新时间',
    del_flag      bigint       default 0 comment '删除标志(0存在 其他删除)',
    primary key (id),
    key idx_ds (datasource_id)
) engine = innodb
  auto_increment = 1
  comment = '问数示例库(问题→SQL)' collate = utf8mb4_unicode_ci;
