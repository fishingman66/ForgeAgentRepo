-- =====================================================================
-- R5-1 文档级 ACL + R5-2 内容治理
-- 目标库: forge-vue
-- 1) agent_knowledge_document: visibility / publish_status / 生效过期窗口 / 回收站
-- 2) agent_kb_doc_acl: 文档-角色授权(visibility=RESTRICTED 时生效)
-- 3) agent_knowledge: review_required(上传后需审核才可检索)
-- 幂等:information_schema 判断 + PREPARE/EXECUTE
-- 检索可见性统一谓词:trashed=0 AND publish_status='PUBLISHED'
--   AND (effective_at IS NULL OR effective_at<=NOW()) AND (expires_at IS NULL OR expires_at>NOW())
--   ACL 在其上再裁一次(角色求交)
-- =====================================================================

set @c := (select count(*) from information_schema.columns where table_schema=database()
    and table_name='agent_knowledge_document' and column_name='visibility');
set @ddl := if(@c = 0,
    'alter table agent_knowledge_document add column visibility varchar(20) not null default ''INHERIT'' comment ''可见性:INHERIT 继承空间(默认)/RESTRICTED 仅授权角色可见'' after notice',
    'select 1');
prepare stmt from @ddl; execute stmt; deallocate prepare stmt;

set @c := (select count(*) from information_schema.columns where table_schema=database()
    and table_name='agent_knowledge_document' and column_name='publish_status');
set @ddl := if(@c = 0,
    'alter table agent_knowledge_document add column publish_status varchar(20) not null default ''PUBLISHED'' comment ''发布态:DRAFT/PENDING_REVIEW/PUBLISHED/REJECTED;仅 PUBLISHED 参与检索'' after visibility',
    'select 1');
prepare stmt from @ddl; execute stmt; deallocate prepare stmt;

set @c := (select count(*) from information_schema.columns where table_schema=database()
    and table_name='agent_knowledge_document' and column_name='effective_at');
set @ddl := if(@c = 0,
    'alter table agent_knowledge_document add column effective_at datetime null comment ''生效时间(NULL=立即生效)'' after publish_status',
    'select 1');
prepare stmt from @ddl; execute stmt; deallocate prepare stmt;

set @c := (select count(*) from information_schema.columns where table_schema=database()
    and table_name='agent_knowledge_document' and column_name='expires_at');
set @ddl := if(@c = 0,
    'alter table agent_knowledge_document add column expires_at datetime null comment ''过期时间(NULL=不过期)'' after effective_at',
    'select 1');
prepare stmt from @ddl; execute stmt; deallocate prepare stmt;

set @c := (select count(*) from information_schema.columns where table_schema=database()
    and table_name='agent_knowledge_document' and column_name='trashed');
set @ddl := if(@c = 0,
    'alter table agent_knowledge_document add column trashed tinyint(1) not null default 0 comment ''回收站(1=已入回收站,不参与检索,超期物理清理)'' after expires_at',
    'select 1');
prepare stmt from @ddl; execute stmt; deallocate prepare stmt;

set @c := (select count(*) from information_schema.columns where table_schema=database()
    and table_name='agent_knowledge_document' and column_name='trashed_at');
set @ddl := if(@c = 0,
    'alter table agent_knowledge_document add column trashed_at datetime null comment ''入回收站时间(清理任务按此计算保留期)'' after trashed',
    'select 1');
prepare stmt from @ddl; execute stmt; deallocate prepare stmt;

set @c := (select count(*) from information_schema.statistics where table_schema=database()
    and table_name='agent_knowledge_document' and index_name='idx_kb_doc_visible');
set @ddl := if(@c = 0,
    'alter table agent_knowledge_document add key idx_kb_doc_visible (knowledge_id, trashed, publish_status)',
    'select 1');
prepare stmt from @ddl; execute stmt; deallocate prepare stmt;

set @c := (select count(*) from information_schema.columns where table_schema=database()
    and table_name='agent_knowledge' and column_name='review_required');
set @ddl := if(@c = 0,
    'alter table agent_knowledge add column review_required tinyint(1) not null default 0 comment ''上传后需审核(1=新文档落 PENDING_REVIEW,审核通过才参与检索)'' after fallback_answer_enabled',
    'select 1');
prepare stmt from @ddl; execute stmt; deallocate prepare stmt;

create table if not exists agent_kb_doc_acl
(
    id          bigint   not null auto_increment comment '主键',
    document_id bigint   not null comment '文档ID',
    role_id     bigint   not null comment '平台角色ID(与登录用户 roleIds 求交)',
    create_time datetime null,
    primary key (id),
    unique key uq_acl_doc_role (document_id, role_id),
    key idx_acl_role (role_id)
) engine = innodb comment = '知识库文档角色授权(visibility=RESTRICTED 时生效)';
