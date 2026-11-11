-- ----------------------------
-- 检索结果重排序(Rerank 精排)
-- 1. agent_knowledge 增加重排序模型列(配置 agent_model 名称,检索时可开启精排)
-- ----------------------------

ALTER TABLE `agent_knowledge`
    ADD COLUMN `rerank_model` VARCHAR(100) NULL COMMENT '重排序模型名称(agent_model 配置名称)' AFTER `embedding_model`;
