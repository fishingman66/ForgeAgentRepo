-- =====================================================================
-- R5-5 图片描述(VLM):库级开关
-- 目标库: forge-vue;幂等
-- =====================================================================

set @c := (select count(*) from information_schema.columns where table_schema=database()
    and table_name='agent_knowledge' and column_name='vlm_enabled');
set @ddl := if(@c = 0,
    'alter table agent_knowledge add column vlm_enabled tinyint(1) not null default 0 comment ''R5-5 图片描述(1=解析时对图片调视觉模型生成描述并注入正文)'' after retrieval_overrides',
    'select 1');
prepare stmt from @ddl; execute stmt; deallocate prepare stmt;
