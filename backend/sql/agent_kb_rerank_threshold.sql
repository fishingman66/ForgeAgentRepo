-- =====================================================================
-- Agent 平台 · 知识库精排阈值口径拆分
-- 目标库: forge-vue
-- 说明: score_threshold 语义收敛为「向量相似度阈值」;
--       新增 rerank_threshold 作用于精排分(rerankScore, 0~1),NULL 不过滤精排结果
-- =====================================================================

alter table agent_knowledge
    add column rerank_threshold double DEFAULT NULL COMMENT '精排相关度阈值(0~1,作用于 rerankScore;NULL 不过滤精排结果)';
