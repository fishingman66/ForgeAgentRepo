-- =====================================================================
-- R3-5 零命中 → 用例 → 质量问题(闭环)
-- 目标库: forge-vue
-- 轻量版 3 态:NEW → IN_PROGRESS → RESOLVED(+ reopen 回 IN_PROGRESS)
-- 去重:(knowledge_id, source, query_hash) 唯一 —— 同来源同指纹只登记一次
-- =====================================================================

create table agent_kb_quality_issue
(
    id             bigint        not null auto_increment comment '主键',
    knowledge_id   bigint        not null comment '知识库ID',
    query_text     varchar(1000) not null comment '触发问题的查询原文',
    query_hash     char(32)      not null comment '查询指纹(与 agent_kb_query_log.query_hash 同口径)',
    reason         varchar(32)   not null default 'RETRIEVAL_MISS' comment 'MISSING_KNOWLEDGE/OUTDATED_KNOWLEDGE/RETRIEVAL_MISS/ANSWER_INCORRECT/OTHER',
    source         varchar(32)   not null default 'ZERO_HIT' comment 'ZERO_HIT/DEBUG_PAGE/MANUAL/CASE_FAIL',
    source_id      bigint        null comment '来源记录ID(查询日志ID/评测用例ID)',
    owner          varchar(64)   default '' comment '负责人',
    linked_case_id bigint        null comment '关联评测用例ID',
    status         varchar(20)   not null default 'NEW' comment 'NEW/IN_PROGRESS/RESOLVED',
    note           varchar(2000) default '' comment '处理备注',
    create_time    datetime      null,
    update_time    datetime      null,
    resolve_time   datetime      null comment '解决时间(RESOLVED 时落)',
    revision       int           not null default 0 comment '乐观锁(每次状态变更 +1)',
    primary key (id),
    key idx_qi_kb (knowledge_id, status),
    unique key uq_qi_source (knowledge_id, source, query_hash)
) engine = innodb comment = '知识库质量问题';
