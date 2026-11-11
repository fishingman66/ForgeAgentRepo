-- ----------------------------
-- M4 知识库增强:文档管理与向量检索(Milvus)
-- 1. agent_knowledge 增加嵌入模型/向量库/分块配置列
-- 2. 新增 agent_knowledge_document(文档)与 agent_kb_chunk(分块)
-- 3. 新增文档管理与检索按钮权限
-- ----------------------------

-- 1. 知识库表扩展列
alter table agent_knowledge
    add column embedding_model varchar(100) default 'local-hash' comment '嵌入模型(local-hash 或 agent_model 配置名称)' after status,
    add column vector_backend  varchar(20)  default 'milvus' comment '向量库后端' after embedding_model,
    add column vector_dim      int          default 384 comment '向量维度' after vector_backend,
    add column chunk_size      int          default 500 comment '分块大小(字符数)' after vector_dim,
    add column chunk_overlap   int          default 50 comment '分块重叠字符数' after chunk_size,
    add column doc_count       int          default 0 comment '文档数量' after chunk_overlap;

-- 2. 知识库文档表
create table if not exists agent_knowledge_document
(
    id          bigint       not null auto_increment comment '主键',
    knowledge_id bigint      not null comment '所属知识库ID',
    file_name   varchar(200) not null comment '原始文件名',
    file_ext    varchar(20)  not null comment '文件扩展名(txt/md/docx/xlsx/pdf)',
    file_size   bigint       default 0 comment '文件大小(字节)',
    file_path   varchar(500) default '' comment '文件存储路径',
    status      char(1)      default '0' comment '解析向量化状态(0处理中 1可用 2失败)',
    fail_reason varchar(500) default '' comment '失败原因',
    chunk_count int          default 0 comment '分块数量',
    create_dept bigint       null comment '创建部门',
    create_by   bigint       null comment '创建者',
    create_time datetime     null comment '创建时间',
    update_by   bigint       null comment '更新者',
    update_time datetime     null comment '更新时间',
    del_flag    bigint       default 0 comment '删除标志(0存在 其他删除)',
    primary key (id),
    key idx_kb_doc_knowledge (knowledge_id)
) engine = innodb
  auto_increment = 1
  comment = 'Agent 知识库文档' collate = utf8mb4_unicode_ci;

-- 3. 知识库分块表(id 同时作为 Milvus 向量主键)
create table if not exists agent_kb_chunk
(
    id           bigint not null auto_increment comment '主键(Milvus 向量主键)',
    knowledge_id bigint not null comment '所属知识库ID',
    document_id  bigint not null comment '所属文档ID',
    chunk_index  int    not null comment '分块序号(从 0 开始)',
    content      text   null comment '分块文本内容',
    create_time  datetime null comment '创建时间',
    primary key (id),
    key idx_kb_chunk_knowledge (knowledge_id),
    key idx_kb_chunk_document (document_id)
) engine = innodb
  auto_increment = 1
  comment = 'Agent 知识库分块' collate = utf8mb4_unicode_ci;

-- 4. 按钮权限(挂在知识库菜单 1761400000000030121 下)
insert into sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param,
                      is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu,
                      create_dept, create_by, create_time, remark)
select 1761400000000030126, '知识库检索', 1761400000000030121, 5, '', null, null, 'N', 'Y', 'F', '0', '0',
       'agent:knowledge:search', '#', null, null, 1, now(), ''
from dual where not exists (select 1 from sys_menu where menu_id = 1761400000000030126);

insert into sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param,
                      is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu,
                      create_dept, create_by, create_time, remark)
select 1761400000000030127, '文档列表', 1761400000000030121, 6, '', null, null, 'N', 'Y', 'F', '0', '0',
       'agent:knowledgeDoc:list', '#', null, null, 1, now(), ''
from dual where not exists (select 1 from sys_menu where menu_id = 1761400000000030127);

insert into sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param,
                      is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu,
                      create_dept, create_by, create_time, remark)
select 1761400000000030128, '文档查询', 1761400000000030121, 7, '', null, null, 'N', 'Y', 'F', '0', '0',
       'agent:knowledgeDoc:query', '#', null, null, 1, now(), ''
from dual where not exists (select 1 from sys_menu where menu_id = 1761400000000030128);

insert into sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param,
                      is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu,
                      create_dept, create_by, create_time, remark)
select 1761400000000030129, '文档上传', 1761400000000030121, 8, '', null, null, 'N', 'Y', 'F', '0', '0',
       'agent:knowledgeDoc:upload', '#', null, null, 1, now(), ''
from dual where not exists (select 1 from sys_menu where menu_id = 1761400000000030129);

insert into sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param,
                      is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu,
                      create_dept, create_by, create_time, remark)
select 1761400000000030130, '文档重试', 1761400000000030121, 9, '', null, null, 'N', 'Y', 'F', '0', '0',
       'agent:knowledgeDoc:retry', '#', null, null, 1, now(), ''
from dual where not exists (select 1 from sys_menu where menu_id = 1761400000000030130);

insert into sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param,
                      is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu,
                      create_dept, create_by, create_time, remark)
select 1761400000000030131, '文档删除', 1761400000000030121, 10, '', null, null, 'N', 'Y', 'F', '0', '0',
       'agent:knowledgeDoc:remove', '#', null, null, 1, now(), ''
from dual where not exists (select 1 from sys_menu where menu_id = 1761400000000030131);
