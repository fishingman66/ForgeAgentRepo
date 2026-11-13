-- 向量后端切换回填脚本（mysql <-> milvus 两向通用，手动执行一次）
--
-- 背景：agent_kb_chunk.vector_status 是「当前后端已同步」的单列状态。
--       切换 rag.knowledge.vector-store 后，存量分块仍是 SYNCED，
--       但新后端（milvus-lite / Milvus standalone）里没有对应向量；
--       KbVectorSyncService 只捞 PENDING/FAILED，不会补写 SYNCED，
--       结果就是语义召回静默为空，只剩关键词路兜底。
--
-- 用法：切换 vector-store 并重启应用「之前」执行一次。
--       之后补偿任务每 30s 扫描（batch 200 / maxRetry 5）重新嵌入并写入当前后端。
-- 注意：只在切换时执行一次，不要放进定时脚本反复跑，
--       否则会把刚同步好的分块反复重置、反复调用嵌入模型。
--       重复执行的替代做法：对每个知识库调用 PUT /agent/knowledge/{id}/reindex 全量重建。

-- 1) 执行前观察存量分布
SELECT vector_status, COUNT(*) AS cnt FROM agent_kb_chunk GROUP BY vector_status;

-- 2) 把存量已同步分块重新排队
UPDATE agent_kb_chunk
SET vector_status = 'PENDING',
    vector_retry_count = 0
WHERE vector_status = 'SYNCED';

-- 3) 执行后确认待补偿数量
SELECT COUNT(*) AS pending FROM agent_kb_chunk WHERE vector_status = 'PENDING';
