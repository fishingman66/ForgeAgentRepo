-- ----------------------------
-- 会话文件附件(在图片附件基础上扩展文档类型)
-- 1. agent_chat_attachment 增加类型/解析状态/临时索引关联等字段
-- 2. agent_knowledge 增加作用域,用于承载会话临时索引并隐藏于知识库列表
-- 执行前请确认当前库为业务库(如 forge-vue)
-- ----------------------------

ALTER TABLE `agent_chat_attachment`
    ADD COLUMN `kind` varchar(10) NOT NULL DEFAULT 'image' COMMENT '附件类型(image图片/file文件)' AFTER `media_type`,
    ADD COLUMN `file_ext` varchar(20) NULL COMMENT '文件扩展名(小写)' AFTER `kind`,
    ADD COLUMN `file_md5` varchar(64) NULL COMMENT '文件内容MD5' AFTER `file_ext`,
    ADD COLUMN `parse_status` char(1) NOT NULL DEFAULT '1' COMMENT '解析状态(0解析中 1可用 2失败)' AFTER `file_md5`,
    ADD COLUMN `chunk_count` int NOT NULL DEFAULT 0 COMMENT '索引分块数' AFTER `parse_status`,
    ADD COLUMN `token_count` int NOT NULL DEFAULT 0 COMMENT '全文估算token数' AFTER `chunk_count`,
    ADD COLUMN `summary` text NULL COMMENT '文件摘要(首段/大纲)' AFTER `token_count`,
    ADD COLUMN `fail_reason` varchar(500) NULL COMMENT '解析失败原因' AFTER `summary`,
    ADD COLUMN `knowledge_id` bigint NULL COMMENT '临时索引知识库ID(仅文件)' AFTER `fail_reason`,
    ADD COLUMN `page_count` int NOT NULL DEFAULT 0 COMMENT '页数/工作表数(PDF/Excel)' AFTER `knowledge_id`;

ALTER TABLE `agent_chat_attachment`
    MODIFY COLUMN `media_type` varchar(120) NOT NULL COMMENT '媒体类型(文档 MIME 较长,如 xlsx)';

ALTER TABLE `agent_chat_attachment`
    ADD INDEX `idx_aca_kind` (`kind`),
    ADD INDEX `idx_aca_knowledge` (`knowledge_id`);

ALTER TABLE `agent_knowledge`
    ADD COLUMN `kb_scope` varchar(10) NOT NULL DEFAULT 'normal' COMMENT '作用域(normal普通/temp会话临时)' AFTER `description`,
    ADD COLUMN `session_id` bigint NULL COMMENT '所属会话ID(仅temp)' AFTER `kb_scope`;

ALTER TABLE `agent_knowledge`
    ADD INDEX `idx_ak_scope` (`kb_scope`),
    ADD INDEX `idx_ak_session` (`session_id`);
