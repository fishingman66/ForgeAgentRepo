-- ----------------------------
-- 混合检索:向量检索 + 全文关键词检索(RRF融合)
-- 1. agent_kb_chunk.content 加 FULLTEXT ngram 索引(支持中文关键词检索)
-- ----------------------------

-- 分块文本全文索引(ngram 分词器,适用于中文短词检索)
ALTER TABLE `agent_kb_chunk`
    ADD FULLTEXT INDEX `ft_chunk_content` (`content`) WITH PARSER ngram;
