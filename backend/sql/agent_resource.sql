-- 防止客户端字符集不是 utf8mb4 时中文被双重编码
SET NAMES utf8mb4;
-- =====================================================================
-- Agent 平台 · M4 资源层 初始化 SQL(模型/工具/知识库注册)
-- 目标库: forge-vue
-- =====================================================================

-- ----------------------------
-- 1. 模型配置表
-- ----------------------------
drop table if exists agent_model;
create table agent_model
(
    id          bigint        not null auto_increment comment '主键',
    name        varchar(50)   not null comment '模型配置名称(全局唯一)',
    provider    varchar(30)   not null comment '提供商(openai/anthropic/dashscope/ollama/custom)',
    base_url    varchar(200)  default '' comment 'API基础地址',
    api_key     varchar(500)  default '' comment 'API密钥',
    model_name  varchar(100)  not null comment '模型标识(如 gpt-4o/qwen-plus)',
    temperature decimal(3, 2) default 0.70 comment '采样温度(0-2)',
    max_tokens  int           default 4096 comment '最大输出tokens',
    status      char(1)       default '0' comment '状态(0正常 1停用)',
    remark      varchar(200)  default '' comment '备注',
    create_dept bigint        null comment '创建部门',
    create_by   bigint        null comment '创建者',
    create_time datetime      null comment '创建时间',
    update_by   bigint        null comment '更新者',
    update_time datetime      null comment '更新时间',
    del_flag    bigint        default 0 comment '删除标志(0存在 其他删除)',
    primary key (id)
) engine = innodb
  auto_increment = 1
  comment = 'Agent 模型配置' collate = utf8mb4_unicode_ci;

-- ----------------------------
-- 2. 工具注册表
-- ----------------------------
drop table if exists agent_tool;
create table agent_tool
(
    id           bigint       not null auto_increment comment '主键',
    name         varchar(50)  not null comment '工具名称(全局唯一)',
    description  varchar(200) default '' comment '工具描述',
    tool_type    varchar(20)  not null default 'builtin' comment '工具类型(builtin内置/http外部接口)',
    http_method  varchar(10)  default '' comment 'HTTP方法(GET/POST/PUT/DELETE)',
    http_url     varchar(300) default '' comment 'HTTP接口地址',
    http_headers text         null comment 'HTTP请求头(JSON)',
    http_params  text         null comment 'HTTP请求参数模板(JSON)',
    status       char(1)      default '0' comment '状态(0正常 1停用)',
    remark       varchar(200) default '' comment '备注',
    create_dept  bigint       null comment '创建部门',
    create_by    bigint       null comment '创建者',
    create_time  datetime     null comment '创建时间',
    update_by    bigint       null comment '更新者',
    update_time  datetime     null comment '更新时间',
    del_flag     bigint       default 0 comment '删除标志(0存在 其他删除)',
    primary key (id)
) engine = innodb
  auto_increment = 1
  comment = 'Agent 工具注册' collate = utf8mb4_unicode_ci;

-- ----------------------------
-- 3. 知识库注册表
-- ----------------------------
drop table if exists agent_knowledge;
create table agent_knowledge
(
    id              bigint       not null auto_increment comment '主键',
    name            varchar(50)  not null comment '知识库名称(全局唯一)',
    description     varchar(200) default '' comment '知识库描述',
    status          char(1)      default '0' comment '状态(0正常 1停用)',
    embedding_model varchar(100) default 'local-hash' comment '嵌入模型(local-hash 或 agent_model 配置名称)',
    vector_backend  varchar(20)  default 'milvus' comment '向量库后端',
    vector_dim      int          default 384 comment '向量维度',
    chunk_size      int          default 500 comment '分块大小(字符数)',
    chunk_overlap   int          default 50 comment '分块重叠字符数',
    doc_count       int          default 0 comment '文档数量',
    remark          varchar(200) default '' comment '备注',
    create_dept     bigint       null comment '创建部门',
    create_by       bigint       null comment '创建者',
    create_time     datetime     null comment '创建时间',
    update_by       bigint       null comment '更新者',
    update_time     datetime     null comment '更新时间',
    del_flag        bigint       default 0 comment '删除标志(0存在 其他删除)',
    primary key (id)
) engine = innodb
  auto_increment = 1
  comment = 'Agent 知识库注册' collate = utf8mb4_unicode_ci;

-- ----------------------------
-- 3b. 知识库文档表
-- ----------------------------
drop table if exists agent_knowledge_document;
create table agent_knowledge_document
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

-- ----------------------------
-- 3c. 知识库分块表
-- ----------------------------
drop table if exists agent_kb_chunk;
create table agent_kb_chunk
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

-- ----------------------------
-- 4. 菜单: Agent 平台 -> 模型/工具/知识库
-- ----------------------------
insert into sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param,
                      is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu,
                      create_dept, create_by, create_time, remark)
values (1761400000000030101, '模型管理', 1761400000000030001, 6, 'model', 'agent/model/index', null,
        'N', 'Y', 'C', '0', '0', 'agent:model:list', 'monitor', null,
        null, 1, now(), '模型配置管理');

insert into sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param,
                      is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu,
                      create_dept, create_by, create_time, remark)
values
(1761400000000030102, '模型查询', 1761400000000030101, 1, '', null, null, 'N', 'Y', 'F', '0', '0', 'agent:model:query', '#', null, null, 1, now(), ''),
(1761400000000030103, '模型新增', 1761400000000030101, 2, '', null, null, 'N', 'Y', 'F', '0', '0', 'agent:model:add', '#', null, null, 1, now(), ''),
(1761400000000030104, '模型修改', 1761400000000030101, 3, '', null, null, 'N', 'Y', 'F', '0', '0', 'agent:model:edit', '#', null, null, 1, now(), ''),
(1761400000000030105, '模型删除', 1761400000000030101, 4, '', null, null, 'N', 'Y', 'F', '0', '0', 'agent:model:remove', '#', null, null, 1, now(), '');

insert into sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param,
                      is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu,
                      create_dept, create_by, create_time, remark)
values (1761400000000030111, '工具管理', 1761400000000030001, 8, 'tool', 'agent/tool/index', null,
        'N', 'Y', 'C', '0', '0', 'agent:tool:list', 'component', null,
        null, 1, now(), '工具注册管理');

insert into sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param,
                      is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu,
                      create_dept, create_by, create_time, remark)
values
(1761400000000030112, '工具查询', 1761400000000030111, 1, '', null, null, 'N', 'Y', 'F', '0', '0', 'agent:tool:query', '#', null, null, 1, now(), ''),
(1761400000000030113, '工具新增', 1761400000000030111, 2, '', null, null, 'N', 'Y', 'F', '0', '0', 'agent:tool:add', '#', null, null, 1, now(), ''),
(1761400000000030114, '工具修改', 1761400000000030111, 3, '', null, null, 'N', 'Y', 'F', '0', '0', 'agent:tool:edit', '#', null, null, 1, now(), ''),
(1761400000000030115, '工具删除', 1761400000000030111, 4, '', null, null, 'N', 'Y', 'F', '0', '0', 'agent:tool:remove', '#', null, null, 1, now(), '');

insert into sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param,
                      is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu,
                      create_dept, create_by, create_time, remark)
values (1761400000000030121, '知识库', 1761400000000030001, 7, 'knowledge', 'agent/knowledge/index', null,
        'N', 'Y', 'C', '0', '0', 'agent:knowledge:list', 'documentation', null,
        null, 1, now(), '知识库注册管理');

insert into sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param,
                      is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu,
                      create_dept, create_by, create_time, remark)
values
(1761400000000030122, '知识库查询', 1761400000000030121, 1, '', null, null, 'N', 'Y', 'F', '0', '0', 'agent:knowledge:query', '#', null, null, 1, now(), ''),
(1761400000000030123, '知识库新增', 1761400000000030121, 2, '', null, null, 'N', 'Y', 'F', '0', '0', 'agent:knowledge:add', '#', null, null, 1, now(), ''),
(1761400000000030124, '知识库修改', 1761400000000030121, 3, '', null, null, 'N', 'Y', 'F', '0', '0', 'agent:knowledge:edit', '#', null, null, 1, now(), ''),
(1761400000000030125, '知识库删除', 1761400000000030121, 4, '', null, null, 'N', 'Y', 'F', '0', '0', 'agent:knowledge:remove', '#', null, null, 1, now(), ''),
(1761400000000030126, '知识库检索', 1761400000000030121, 5, '', null, null, 'N', 'Y', 'F', '0', '0', 'agent:knowledge:search', '#', null, null, 1, now(), ''),
(1761400000000030127, '文档列表', 1761400000000030121, 6, '', null, null, 'N', 'Y', 'F', '0', '0', 'agent:knowledgeDoc:list', '#', null, null, 1, now(), ''),
(1761400000000030128, '文档查询', 1761400000000030121, 7, '', null, null, 'N', 'Y', 'F', '0', '0', 'agent:knowledgeDoc:query', '#', null, null, 1, now(), ''),
(1761400000000030129, '文档上传', 1761400000000030121, 8, '', null, null, 'N', 'Y', 'F', '0', '0', 'agent:knowledgeDoc:upload', '#', null, null, 1, now(), ''),
(1761400000000030130, '文档重试', 1761400000000030121, 9, '', null, null, 'N', 'Y', 'F', '0', '0', 'agent:knowledgeDoc:retry', '#', null, null, 1, now(), ''),
(1761400000000030131, '文档删除', 1761400000000030121, 10, '', null, null, 'N', 'Y', 'F', '0', '0', 'agent:knowledgeDoc:remove', '#', null, null, 1, now(), '');
