-- 智能问数优化(P0/P1):密码加密存储扩容、表级白名单、schema 明细 JSON、示例库
-- 执行: mysql -h127.0.0.1 -uroot -p forge-vue < agent_text2sql_opt.sql
SET NAMES utf8mb4;

-- 密码改 AES 加密存储,长度扩容
alter table agent_datasource modify column password varchar(500) not null comment '连接密码(AES加密存储)';

-- 表级白名单(逗号分隔,空为全部可查询)
alter table agent_datasource add column table_whitelist varchar(2000) not null default '' comment '可查询表白名单(逗号分隔,空为全部)' after timeout_seconds;

-- 表结构明细 JSON(名称/注释/列明细),供 describe 工具按需取用
alter table agent_datasource add column schema_tables mediumtext null comment '表结构JSON(名称/注释/列明细,供describe工具按需取用)' after schema_snapshot;

-- 问数示例库(问题→SQL,注入提示词做 few-shot)
create table agent_datasource_example (
  id bigint not null auto_increment comment '主键',
  datasource_id bigint not null comment '数据源ID',
  question varchar(500) not null comment '示例问题',
  sql_content text not null comment '示例SQL',
  sort int default 0 comment '排序',
  status char(1) default '0' comment '状态(0正常 1停用)',
  remark varchar(200) default '' comment '备注',
  create_dept bigint null,
  create_by bigint null,
  create_time datetime null,
  update_by bigint null,
  update_time datetime null,
  del_flag bigint default 0 comment '删除标志(0存在 其他删除)',
  primary key (id),
  key idx_ds (datasource_id)
) engine=innodb comment='问数示例库(问题→SQL)' collate=utf8mb4_unicode_ci;
