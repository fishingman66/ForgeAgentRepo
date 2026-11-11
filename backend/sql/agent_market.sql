-- 防止客户端字符集不是 utf8mb4 时中文被双重编码
SET NAMES utf8mb4;
-- =====================================================================
-- Agent 平台 · M5 智能体市场 初始化 SQL
-- 目标库: forge-vue
-- =====================================================================

-- ----------------------------
-- 1. 市场条目表(Listing)
-- ----------------------------
drop table if exists agent_market_listing;
create table agent_market_listing
(
    id            bigint       not null auto_increment comment '主键',
    agent_id      bigint       not null comment '源Agent ID',
    workspace_id  bigint       not null comment '源工作空间ID',
    version_no    int          not null default 1 comment '版本号(每次提交递增)',
    name          varchar(50)  not null comment '市场展示名',
    category      varchar(30)  default 'general' comment '分类(general/office/dev/data/life/other)',
    icon          varchar(200) default '' comment '图标(emoji或URL)',
    description   varchar(500) default '' comment '详情说明',
    usage_doc     varchar(500) default '' comment '使用说明',
    deps_json     text         null comment '依赖声明摘要(JSON:模型/工具/知识库)',
    snapshot_json longtext     null comment '版本快照(Agent配置不可变快照)',
    status        char(1)      default '0' comment '状态(0待审核 1已上架 2已驳回 3已下架)',
    review_reason varchar(300) default '' comment '审核/下架理由',
    publisher_id  bigint       not null comment '发布者用户ID',
    install_count int          default 0 comment '安装量',
    rating_sum    int          default 0 comment '评分星数合计',
    rating_count  int          default 0 comment '评分人数',
    create_dept   bigint       null comment '创建部门',
    create_by     bigint       null comment '创建者',
    create_time   datetime     null comment '创建时间',
    update_by     bigint       null comment '更新者',
    update_time   datetime     null comment '更新时间',
    del_flag      bigint       default 0 comment '删除标志(0存在 其他删除)',
    primary key (id),
    key idx_agent (agent_id),
    key idx_status (status),
    key idx_category (category)
) engine = innodb
  auto_increment = 1
  comment = 'Agent 市场条目' collate = utf8mb4_unicode_ci;

-- ----------------------------
-- 2. 市场安装记录表
-- ----------------------------
drop table if exists agent_market_install;
create table agent_market_install
(
    id                bigint       not null auto_increment comment '主键',
    listing_id        bigint       not null comment '市场条目ID',
    workspace_id      bigint       not null comment '安装目标工作空间ID',
    agent_copy_id     bigint       not null comment '生成的Agent副本ID',
    version_no        int          not null comment '安装的Listing版本号',
    bindings_json     text         null comment '资源绑定关系(JSON)',
    prev_snapshot_json longtext    null comment '升级前配置快照(一键回退用)',
    user_id           bigint       not null comment '安装人用户ID',
    create_dept       bigint       null comment '创建部门',
    create_by         bigint       null comment '创建者',
    create_time       datetime     null comment '创建时间',
    update_by         bigint       null comment '更新者',
    update_time       datetime     null comment '更新时间',
    primary key (id),
    key idx_listing (listing_id),
    key idx_workspace (workspace_id),
    key idx_agent_copy (agent_copy_id)
) engine = innodb
  auto_increment = 1
  comment = 'Agent 市场安装记录' collate = utf8mb4_unicode_ci;

-- ----------------------------
-- 3. 市场评分表(每个安装记录限评一次)
-- ----------------------------
drop table if exists agent_market_review;
create table agent_market_review
(
    id          bigint      not null auto_increment comment '主键',
    listing_id  bigint      not null comment '市场条目ID',
    install_id  bigint      not null comment '安装记录ID',
    user_id     bigint      not null comment '评分用户ID',
    stars       int         not null comment '星数(1-5)',
    comment     varchar(300) default '' comment '文字评价(可选)',
    create_dept bigint      null comment '创建部门',
    create_by   bigint      null comment '创建者',
    create_time datetime    null comment '创建时间',
    update_by   bigint      null comment '更新者',
    update_time datetime    null comment '更新时间',
    primary key (id),
    unique key uk_install (install_id),
    key idx_listing (listing_id)
) engine = innodb
  auto_increment = 1
  comment = 'Agent 市场评分' collate = utf8mb4_unicode_ci;

-- ----------------------------
-- 4. 菜单: 智能体市场 / 我的发布 / 审核队列
-- ----------------------------
insert into sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param,
                      is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu,
                      create_dept, create_by, create_time, remark)
values (1761400000000030401, '智能体市场', 1761400000000030001, 3, 'market', 'agent/market/index', null,
        'N', 'Y', 'C', '0', '0', 'agent:market:list', 'shop', null,
        null, 1, now(), '市场浏览与搜索');

insert into sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param,
                      is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu,
                      create_dept, create_by, create_time, remark)
values
(1761400000000030402, '市场查询', 1761400000000030401, 1, '', null, null, 'N', 'Y', 'F', '0', '0', 'agent:market:query', '#', null, null, 1, now(), ''),
(1761400000000030403, '安装Agent', 1761400000000030401, 2, '', null, null, 'N', 'Y', 'F', '0', '0', 'agent:market:install', '#', null, null, 1, now(), ''),
(1761400000000030404, '评分', 1761400000000030401, 3, '', null, null, 'N', 'Y', 'F', '0', '0', 'agent:market:rate', '#', null, null, 1, now(), '');

insert into sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param,
                      is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu,
                      create_dept, create_by, create_time, remark)
values (1761400000000030411, '我的发布', 1761400000000030001, 4, 'market-my', 'agent/market/my', null,
        'N', 'Y', 'C', '0', '0', 'agent:market:my', 'promotion', null,
        null, 1, now(), '我的发布与提交审核');

insert into sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param,
                      is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu,
                      create_dept, create_by, create_time, remark)
values
(1761400000000030412, '发布提交', 1761400000000030411, 1, '', null, null, 'N', 'Y', 'F', '0', '0', 'agent:market:publish', '#', null, null, 1, now(), ''),
(1761400000000030413, '主动下架', 1761400000000030411, 2, '', null, null, 'N', 'Y', 'F', '0', '0', 'agent:market:offline', '#', null, null, 1, now(), '');

insert into sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param,
                      is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu,
                      create_dept, create_by, create_time, remark)
values (1761400000000030421, '市场审核', 1761400000000030001, 16, 'market-review', 'agent/market/review', null,
        'N', 'Y', 'C', '0', '0', 'agent:market:review', 'lock', null,
        null, 1, now(), '市场审核队列(平台管理员)');

insert into sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param,
                      is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu,
                      create_dept, create_by, create_time, remark)
values
(1761400000000030422, '审核操作', 1761400000000030421, 1, '', null, null, 'N', 'Y', 'F', '0', '0', 'agent:market:audit', '#', null, null, 1, now(), ''),
(1761400000000030423, '强制下架', 1761400000000030421, 2, '', null, null, 'N', 'Y', 'F', '0', '0', 'agent:market:forceoff', '#', null, null, 1, now(), '');
