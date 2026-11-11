-- 智能问数语义层 P0:指标/维度/枚举值字典 + 数据源启用开关与版本号
-- 执行: mysql -h127.0.0.1 -uroot -p forge-vue < agent_semantic_p0.sql
SET NAMES utf8mb4;

-- 数据源扩展:语义层启用开关 + 版本号(每次语义资产编辑自增,用于缓存失效)
alter table agent_datasource add column semantic_enabled char(1) not null default '0' comment '是否启用语义层注入(0否 1是)' after table_whitelist;
alter table agent_datasource add column semantic_version int not null default 0 comment '语义层版本号(每次编辑自增)' after semantic_enabled;

-- 指标定义表
create table agent_semantic_metric (
  id bigint not null auto_increment comment '主键',
  datasource_id bigint not null comment '数据源ID',
  name varchar(100) not null comment '指标名',
  synonyms varchar(500) default '' comment '同义词(逗号分隔)',
  base_table varchar(128) not null comment '基表名',
  expression varchar(1000) not null comment '可执行表达式,如 SUM(pay_amount)',
  default_filters varchar(500) default '' comment '默认过滤条件,如 pay_status IN (''PAID'',''SHIPPED'')',
  unit varchar(20) default '' comment '单位(元/人/次等)',
  format varchar(50) default '' comment '展示格式,如 #,##0.00',
  description varchar(500) default '' comment '口径说明',
  status char(1) default '0' comment '状态(0正常 1停用)',
  sort int default 0 comment '排序',
  create_dept bigint null,
  create_by bigint null,
  create_time datetime null,
  update_by bigint null,
  update_time datetime null,
  del_flag bigint default 0 comment '删除标志(0存在 其他删除)',
  primary key (id),
  key idx_ds (datasource_id)
) engine=innodb comment='问数语义指标' collate=utf8mb4_unicode_ci;

-- 维度定义表
create table agent_semantic_dimension (
  id bigint not null auto_increment comment '主键',
  datasource_id bigint not null comment '数据源ID',
  table_name varchar(128) not null comment '表名',
  column_name varchar(128) not null comment '列名',
  display_name varchar(100) not null comment '业务显示名',
  synonyms varchar(500) default '' comment '同义词(逗号分隔)',
  dim_type varchar(20) default 'text' comment '维度类型(enum/time/id/text)',
  is_time tinyint default 0 comment '是否默认时间维度(0否 1是)',
  time_format varchar(50) default '' comment '时间粒度格式,如 %Y-%m',
  description varchar(500) default '' comment '说明',
  status char(1) default '0' comment '状态(0正常 1停用)',
  sort int default 0 comment '排序',
  create_dept bigint null,
  create_by bigint null,
  create_time datetime null,
  update_by bigint null,
  update_time datetime null,
  del_flag bigint default 0 comment '删除标志(0存在 其他删除)',
  primary key (id),
  key idx_ds_table_col (datasource_id, table_name, column_name)
) engine=innodb comment='问数语义维度' collate=utf8mb4_unicode_ci;

-- 枚举值字典表
create table agent_semantic_value (
  id bigint not null auto_increment comment '主键',
  datasource_id bigint not null comment '数据源ID',
  table_name varchar(128) not null comment '表名',
  column_name varchar(128) not null comment '列名',
  raw_value varchar(200) not null comment '库内真实值',
  display_name varchar(100) not null comment '业务显示名',
  synonyms varchar(500) default '' comment '别名(逗号分隔)',
  sort int default 0 comment '排序',
  status char(1) default '0' comment '状态(0正常 1停用)',
  create_dept bigint null,
  create_by bigint null,
  create_time datetime null,
  update_by bigint null,
  update_time datetime null,
  del_flag bigint default 0 comment '删除标志(0存在 其他删除)',
  primary key (id),
  key idx_ds_table_col (datasource_id, table_name, column_name)
) engine=innodb comment='问数语义枚举值字典' collate=utf8mb4_unicode_ci;
