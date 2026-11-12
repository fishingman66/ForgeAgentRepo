-- =====================================================================
-- R5-3 图谱辅助检索 + R5-4 外部源 + R5-6 库级检索参数/MCP filters
-- 目标库: forge-vue
-- 幂等:information_schema 判断 + PREPARE / create table if not exists
-- =====================================================================

-- R5-6 库级检索参数覆盖(空=跟随全局;JSON 只写需要覆盖的键)
set @c := (select count(*) from information_schema.columns where table_schema=database()
    and table_name='agent_knowledge' and column_name='retrieval_overrides');
set @ddl := if(@c = 0,
    'alter table agent_knowledge add column retrieval_overrides json null comment ''库级检索参数覆盖 JSON:{topK,searchMode,vectorWeight,keywordWeight,scoreThreshold,rerank}'' after review_required',
    'select 1');
prepare stmt from @ddl; execute stmt; deallocate prepare stmt;

-- R5-3 图谱实体↔分块映射(kg 模块保持通用,rag 侧记录"哪个分块提到哪个实体")
create table if not exists agent_kb_kg_entity
(
    id           bigint      not null auto_increment comment '主键',
    knowledge_id bigint      not null comment '知识库ID',
    document_id  bigint      null comment '文档ID',
    chunk_id     bigint      not null comment '分块ID',
    node_key     varchar(128) not null comment '实体归一化键(与 kg_node.name_key 同口径)',
    node_name    varchar(128) not null comment '实体展示名',
    create_time  datetime    null,
    primary key (id),
    unique key uq_kb_kg_chunk_node (chunk_id, node_key),
    key idx_kb_kg_node (knowledge_id, node_key)
) engine = innodb comment = '知识库分块-实体映射(图谱辅助检索回落到分块用)';

-- R5-4 外部源
create table if not exists agent_kb_ext_source
(
    id           bigint       not null auto_increment comment '主键',
    knowledge_id bigint       not null comment '知识库ID',
    source_type  varchar(20)  not null comment 'URL/S3/CONFLUENCE',
    name         varchar(200) not null comment '来源名称',
    config_json  text         null comment '连接配置 JSON(URL 为 {url,selector};凭据不落明文)',
    status       varchar(20)  not null default 'IDLE' comment 'IDLE/SYNCING/FAILED',
    last_sync_at datetime     null,
    last_error   varchar(500) null,
    create_time  datetime     null,
    primary key (id),
    key idx_ext_src_kb (knowledge_id)
) engine = innodb comment = '知识库外部源';

create table if not exists agent_kb_ext_item
(
    id           bigint       not null auto_increment comment '主键',
    source_id    bigint       not null comment '外部源ID',
    external_id  varchar(200) null comment '外部唯一标识(URL 或对象键)',
    title        varchar(300) null comment '条目标题',
    document_id  bigint       null comment '入库后的文档ID',
    status       varchar(20)  not null default 'PENDING' comment 'PENDING/IMPORTED/FAILED/SKIPPED',
    last_error   varchar(500) null,
    create_time  datetime     null,
    primary key (id),
    unique key uq_ext_item (source_id, external_id),
    key idx_ext_item_doc (document_id)
) engine = innodb comment = '知识库外部源条目(去重与增量同步依据)';
