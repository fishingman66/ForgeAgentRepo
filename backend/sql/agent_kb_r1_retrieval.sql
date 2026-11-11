-- ----------------------------
-- R1-B 检索可观测:知识库检索日志(2026-11-11)
-- 1. 新增 agent_kb_query_log:每次对话检索落一行,记录命中数/首条分/分数口径/降级/耗时
-- 2. 新增查询日志菜单按钮权限 agent:knowledge:querylog
--
-- 与既有 agent_kb_zero_hit_log 的分工:
--   零命中日志回答"什么没找到";检索日志回答"这次检索走成什么样"(各路召回数、降级原因、阈值口径)
-- 幂等:create table if not exists + insert ... where not exists,可重复执行
-- ----------------------------

create table if not exists agent_kb_query_log
(
    id              bigint       not null auto_increment comment '主键',
    agent_id        bigint       null comment 'Agent ID(客服/工作流链路可能为空)',
    session_id      bigint       null comment '会话 ID',
    user_id         bigint       null comment '用户 ID',
    knowledge_ids   varchar(255) default '' comment '参与检索的知识库ID(逗号分隔)',
    query_text      varchar(500) not null comment '用户原始问题(未改写)',
    rewritten_query varchar(500) default '' comment '实际送检的查询(改写后;未改写时与原始问题一致)',
    query_hash      char(32)     not null comment '查询指纹(md5:去空白+小写+折叠空格),用于聚合同类问题',
    result_count    int          default 0 comment '最终命中条数',
    zero_hit        tinyint(1)   default 0 comment '是否零命中(1是 0否)',
    top1_score      decimal(8, 6) null comment '首条分数(按 score_type 解释)',
    score_type      varchar(20)  null comment '首条分数口径(cosine/rrf/rerank/bm25)',
    vector_hits     int          default 0 comment '向量路召回条数(过滤前)',
    keyword_hits    int          default 0 comment '关键词路召回条数(过滤前)',
    degraded        varchar(255) default '' comment '降级原因(逗号分隔的 code,空表示全链路正常)',
    retrieval_ms    int          default 0 comment '检索耗时(毫秒)',
    create_time     datetime     null comment '创建时间',
    primary key (id),
    key idx_kbql_hash (query_hash, create_time),
    key idx_kbql_agent (agent_id, create_time),
    key idx_kbql_zero (zero_hit, create_time)
) engine = innodb
  auto_increment = 1
  comment = '知识库检索日志' collate = utf8mb4_unicode_ci;

-- 查询日志按钮权限(父菜单:知识库 1761400000000030121)
insert into sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param,
                      is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu,
                      create_dept, create_by, create_time, remark)
select 1761400000000030133, '检索日志', 1761400000000030121, 12, '', null, null, 'N', 'Y', 'F', '0', '0',
       'agent:knowledge:querylog', '#', null, null, 1, now(), ''
from dual where not exists (select 1 from sys_menu where menu_id = 1761400000000030133);

insert into sys_role_menu (role_id, menu_id)
select 1, 1761400000000030133
from dual where not exists (select 1 from sys_role_menu where role_id = 1 and menu_id = 1761400000000030133);
