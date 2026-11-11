-- 知识库工作空间隔离:agent_knowledge 增加 workspace_id 列(NULL 表示全局/历史数据,向后兼容)
ALTER TABLE `agent_knowledge`
    ADD COLUMN `workspace_id` bigint DEFAULT NULL COMMENT '所属工作空间ID(NULL为全局/历史数据)' AFTER `session_id`,
    ADD INDEX `idx_ak_workspace` (`workspace_id`);
