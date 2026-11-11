-- =====================================================================
-- Agent 平台 · 知识库检索评测集
-- 目标库: forge-vue
-- 说明: 为知识库维护评测问题集(期望命中文档/关键词),批量运行检索并
--       统计命中率与 MRR(平均倒数排名),让分块/阈值/重排调参有数据依据
-- =====================================================================

create table agent_kb_eval_case
(
    id                 bigint       not null auto_increment comment '主键',
    knowledge_id       bigint       not null comment '知识库ID',
    question           varchar(1000) not null comment '评测问题',
    expected_doc_name  varchar(200) default '' comment '期望命中的文档名(模糊包含,空=不校验)',
    expected_keywords  varchar(500) default '' comment '期望命中内容包含的关键词(逗号分隔,任一命中即可,空=不校验)',
    remark             varchar(500) default '' comment '备注',
    create_time        datetime     null,
    primary key (id),
    key idx_eval_case_kb (knowledge_id)
) engine = innodb comment = '知识库检索评测用例';

create table agent_kb_eval_run
(
    id           bigint   not null auto_increment comment '主键',
    knowledge_id bigint   not null comment '知识库ID',
    top_k        int      not null default 5 comment '评测时的召回条数',
    total_cases  int      not null default 0 comment '用例总数',
    hit_cases    int      not null default 0 comment '命中用例数',
    mrr          double   default 0 comment '平均倒数排名(MRR,0~1)',
    avg_score    double   default 0 comment '命中用例的平均相关度分',
    duration_ms  bigint   default 0 comment '评测耗时(毫秒)',
    create_time  datetime null,
    primary key (id),
    key idx_eval_run_kb (knowledge_id)
) engine = innodb comment = '知识库检索评测运行记录';
