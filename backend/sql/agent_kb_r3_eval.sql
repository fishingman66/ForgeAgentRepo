-- =====================================================================
-- R3 评测闭环 · 批次1(R3-1/R3-2/R3-3)
-- 目标库: forge-vue
-- 1) agent_kb_eval_case: 证据标注(evidences) + 状态 + 来源 + 数据集修订
-- 2) agent_kb_eval_run: 五指标列 + 指标明细 JSON + 指纹/快照 + 用例计数
-- 旧用例(expected_doc_name/expected_keywords)保留,判定走 legacy 口径
-- =====================================================================

alter table agent_kb_eval_case
    add column evidences       mediumtext  null comment '证据标注 JSON:[{"documentId":123,"span":"原文片段"}],空=legacy 口径(文档名+关键词)' after expected_keywords,
    add column status          varchar(20) not null default 'ACTIVE' comment 'ACTIVE/EVIDENCE_STALE/DEPRECATED' after evidences,
    add column source          varchar(20) not null default 'MANUAL' comment '来源:MANUAL/DEBUG_PAGE/ZERO_HIT' after status,
    add column source_id       bigint      null comment '来源记录ID(如查询日志ID/分块ID)' after source,
    add column dataset_revision varchar(40) default '' comment '数据集修订号(变更标注时递增)' after source,
    add column update_time     datetime    null comment '更新时间' after create_time;

alter table agent_kb_eval_run
    add column hit_rate          double      null comment 'HitRate@K(命中用例/有效用例)',
    add column recall_at_k       double      null comment 'Recall@K(平均证据覆盖度)',
    add column precision_at_k    double      null comment 'Precision@K(相关条数/K)',
    add column ndcg_at_k         double      null comment 'NDCG@K(二值相关,ideal=min(max(标注数,观测数),K))',
    add column metrics_json      mediumtext  null comment '五指标+分组+Wilson95%置信区间明细 JSON',
    add column config_snapshot   mediumtext  null comment '评测时配置快照 JSON(嵌入模型/分块参数/阈值)',
    add column corpus_fingerprint char(64)   null comment '语料指纹 sha256',
    add column dataset_revision  varchar(40) default '' comment '数据集修订号',
    add column effective_cases   int         not null default 0 comment '有效用例数(ACTIVE)',
    add column stale_cases       int         not null default 0 comment '证据过期用例数(EVIDENCE_STALE)',
    add column degraded_cases    int         not null default 0 comment '检索降级用例数',
    add column gate_state        varchar(20) null comment '门禁判决 PASSED/BLOCKED/LOG_ONLY',
    add column gate_reason       varchar(40) null comment '门禁原因 METRICS_REGRESSED/WITHIN_TOLERANCE/BASELINE_RECORDED/...';
alter table agent_kb_eval_run
    add column case_results_json mediumtext null comment '逐用例判定明细 JSON(交集重算与门禁对比用)';
