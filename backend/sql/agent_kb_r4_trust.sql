-- =====================================================================
-- R4 问答可信度(R4-3 拒答策略 + R4-4 答案层评测)
-- 目标库: forge-vue
-- 1) agent_knowledge + fallback_answer_enabled(库级,零召回兜底开关)
-- 2) agent_eval_case + expected_refusal(期望拒答用例,只在达标用例上跑五维裁判)
-- 幂等:information_schema 判断 + PREPARE/EXECUTE
-- =====================================================================

set @col_exists := (select count(*) from information_schema.columns
    where table_schema = database() and table_name = 'agent_knowledge'
      and column_name = 'fallback_answer_enabled');
set @ddl := if(@col_exists = 0,
    'alter table agent_knowledge add column fallback_answer_enabled tinyint(1) not null default 1 comment ''零召回兜底:1=允许基于通用知识回答但须显式声明,0=严格模式一律拒答(金融/医疗场景)'' after rerank_threshold',
    'select 1');
prepare stmt from @ddl; execute stmt; deallocate prepare stmt;

set @col_exists := (select count(*) from information_schema.columns
    where table_schema = database() and table_name = 'agent_eval_case'
      and column_name = 'expected_refusal');
set @ddl := if(@col_exists = 0,
    'alter table agent_eval_case add column expected_refusal tinyint(1) not null default 0 comment ''期望拒答用例(R4-4 五维裁判:refusal_correctness 只在此类用例上有效)'' after reference_answer',
    'select 1');
prepare stmt from @ddl; execute stmt; deallocate prepare stmt;
