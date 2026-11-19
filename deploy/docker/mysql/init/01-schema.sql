-- ForgeAgent 全量表结构(由 forge-vue 开发库导出,与代码保持一致)
-- 说明：本文件全部 CREATE TABLE 均为 IF NOT EXISTS，所有增量列已直接写入
-- CREATE TABLE 定义（不使用 ALTER TABLE ADD COLUMN），因此可在已有库上重复执行。
-- 字段顺序不影响 MyBatis-Plus 映射，故源脚本中的 AFTER 子句已移除。

SET NAMES utf8mb4;
DROP TABLE IF EXISTS `agent_api_key`;
CREATE TABLE IF NOT EXISTS `agent_api_key` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `workspace_id` bigint NOT NULL COMMENT '所属工作空间ID',
  `name` varchar(50) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT 'Key 名称',
  `key_prefix` varchar(20) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT 'Key 脱敏前缀(如 ak-x1y2...)',
  `key_hash` varchar(128) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT 'Key 哈希(SHA-256)',
  `status` char(1) COLLATE utf8mb4_unicode_ci DEFAULT '0' COMMENT '状态(0正常 1已吊销)',
  `last_used_time` datetime DEFAULT NULL COMMENT '最后使用时间',
  `create_dept` bigint DEFAULT NULL COMMENT '创建部门',
  `create_by` bigint DEFAULT NULL COMMENT '创建者',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `update_by` bigint DEFAULT NULL COMMENT 'æ›´æ–°è€…',
  `update_time` datetime DEFAULT NULL COMMENT 'æ›´æ–°æ—¶é—´',
  `del_flag` bigint DEFAULT '0' COMMENT '删除标志(0存在 其他删除)',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_hash` (`key_hash`),
  KEY `idx_workspace` (`workspace_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Agent 空间 API Key';
DROP TABLE IF EXISTS `agent_approval`;
CREATE TABLE IF NOT EXISTS `agent_approval` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `session_id` bigint DEFAULT NULL COMMENT '会话ID(工作流审批单为空)',
  `agent_id` bigint NOT NULL COMMENT 'AgentID',
  `round_id` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '请求轮次ID',
  `tool_name` varchar(100) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '工具名称',
  `tool_source` varchar(20) COLLATE utf8mb4_unicode_ci DEFAULT 'mcp' COMMENT '工具来源(mcp/platform-http)',
  `server_name` varchar(50) COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT 'MCP服务器名称(平台工具为空)',
  `call_args` text COLLATE utf8mb4_unicode_ci COMMENT '调用参数(JSON)',
  `status` varchar(20) COLLATE utf8mb4_unicode_ci DEFAULT 'pending' COMMENT '状态(pending待审批/approved已通过/rejected已拒绝/cancelled已取消/expired已超时)',
  `result_note` varchar(500) COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '审批说明/拒绝原因',
  `approve_by` bigint DEFAULT NULL COMMENT '审批人',
  `approve_time` datetime DEFAULT NULL COMMENT '审批时间',
  `create_dept` bigint DEFAULT NULL COMMENT '创建部门',
  `create_by` bigint DEFAULT NULL COMMENT '创建者',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `update_by` bigint DEFAULT NULL COMMENT '更新者',
  `update_time` datetime DEFAULT NULL COMMENT '更新时间',
  `del_flag` bigint DEFAULT '0' COMMENT '删除标志(0存在 其他删除)',
  `expired_at` DATETIME NULL COMMENT '审批截止时间(过期清扫置 expired,run 置 fail)',
  PRIMARY KEY (`id`),
  KEY `idx_approval_session` (`session_id`),
  KEY `idx_approval_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Agent 工具审批单';
DROP TABLE IF EXISTS `agent_audit_log`;
CREATE TABLE IF NOT EXISTS `agent_audit_log` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `workspace_id` bigint DEFAULT NULL COMMENT '所属工作空间ID(平台事件为空)',
  `event_type` varchar(40) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '事件类型(如 member.role/credential.create/agent.publish/market.submit/apikey.revoke)',
  `target_type` varchar(30) COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '目标对象类型',
  `target_id` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '目标对象ID',
  `source_channel` varchar(20) COLLATE utf8mb4_unicode_ci DEFAULT 'web' COMMENT '来源 web/api_key/system',
  `client_ip` varchar(50) COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '客户端IP',
  `user_agent` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT 'User-Agent',
  `result` char(1) COLLATE utf8mb4_unicode_ci DEFAULT '0' COMMENT '操作结果(0成功 1失败)',
  `severity` varchar(10) COLLATE utf8mb4_unicode_ci DEFAULT 'INFO' COMMENT '事件级别(INFO/WARN/RISK)',
  `trace_id` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '链路追踪ID',
  `old_value` text COLLATE utf8mb4_unicode_ci COMMENT '变更前快照(JSON)',
  `new_value` text COLLATE utf8mb4_unicode_ci COMMENT '变更后快照(JSON)',
  `detail` varchar(500) COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '事件详情描述',
  `operator_id` bigint NOT NULL COMMENT '操作人用户ID',
  `operator_name` varchar(30) COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '操作人用户名',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  PRIMARY KEY (`id`),
  KEY `idx_workspace` (`workspace_id`),
  KEY `idx_event` (`event_type`),
  KEY `idx_create_time` (`create_time`),
  KEY `idx_severity` (`severity`),
  KEY `idx_trace` (`trace_id`),
  KEY `idx_result` (`result`),
  KEY `idx_ws_ctime` (`workspace_id`,`create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Agent 平台审计日志';
DROP TABLE IF EXISTS `agent_chat_attachment`;
CREATE TABLE IF NOT EXISTS `agent_chat_attachment` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `stored_name` varchar(80) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '存储文件名(含扩展名)',
  `original_name` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '用户原始文件名',
  `media_type` varchar(120) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '媒体类型',
  `kind` varchar(10) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'image' COMMENT '附件类型(image图片/file文件)',
  `file_ext` varchar(20) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '文件扩展名(小写)',
  `file_md5` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '文件内容MD5',
  `parse_status` char(1) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT '1' COMMENT '解析状态(0解析中 1可用 2失败)',
  `chunk_count` int NOT NULL DEFAULT '0' COMMENT '索引分块数',
  `token_count` int NOT NULL DEFAULT '0' COMMENT '全文估算token数',
  `summary` text COLLATE utf8mb4_unicode_ci COMMENT '文件摘要(首段/大纲)',
  `fail_reason` varchar(500) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '解析失败原因',
  `knowledge_id` bigint DEFAULT NULL COMMENT '临时索引知识库ID(仅文件)',
  `page_count` int NOT NULL DEFAULT '0' COMMENT '页数/工作表数(PDF/Excel)',
  `file_size` bigint NOT NULL DEFAULT '0' COMMENT '文件大小(字节)',
  `thumb` longtext COLLATE utf8mb4_unicode_ci COMMENT '缩略图(data URI)',
  `workspace_id` bigint DEFAULT NULL COMMENT '所属工作空间ID',
  `session_id` bigint DEFAULT NULL COMMENT '发送后回填的会话ID',
  `message_id` bigint DEFAULT NULL COMMENT '发送后回填的消息ID',
  `status` char(1) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT '0' COMMENT '状态(0正常 1删除)',
  `create_dept` bigint DEFAULT NULL COMMENT '创建部门',
  `create_by` bigint DEFAULT NULL COMMENT '创建者',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `update_by` bigint DEFAULT NULL COMMENT '更新者',
  `update_time` datetime DEFAULT NULL COMMENT '更新时间',
  PRIMARY KEY (`id`),
  KEY `idx_aca_ws` (`workspace_id`),
  KEY `idx_message_ws` (`message_id`,`workspace_id`),
  KEY `idx_session` (`session_id`),
  KEY `idx_aca_kind` (`kind`),
  KEY `idx_aca_knowledge` (`knowledge_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='会话图片附件';
DROP TABLE IF EXISTS `agent_datasource`;
CREATE TABLE IF NOT EXISTS `agent_datasource` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `name` varchar(50) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '数据源名称(全局唯一)',
  `description` varchar(200) COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '描述',
  `db_type` varchar(20) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'mysql' COMMENT '数据库类型(mysql)',
  `host` varchar(200) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '主机地址',
  `port` int NOT NULL DEFAULT '3306' COMMENT '端口',
  `db_name` varchar(100) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '数据库名',
  `username` varchar(100) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '连接用户名(须为只读账号)',
  `password` varchar(500) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '连接密码(AES加密存储)',
  `params` varchar(500) COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '额外JDBC参数(如 useSSL=false&serverTimezone=Asia/Shanghai)',
  `max_rows` int DEFAULT '500' COMMENT '单次查询最大返回行数',
  `timeout_seconds` int DEFAULT '15' COMMENT '查询超时(秒)',
  `table_whitelist` varchar(2000) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT '' COMMENT '可查询表白名单(逗号分隔,空为全部)',
  `semantic_enabled` char(1) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT '0' COMMENT '是否启用语义层注入(0否 1是)',
  `semantic_version` int NOT NULL DEFAULT '0' COMMENT '语义层版本号(每次编辑自增)',
  `schema_snapshot` mediumtext COLLATE utf8mb4_unicode_ci COMMENT '表结构快照(注入问数提示词)',
  `schema_tables` mediumtext COLLATE utf8mb4_unicode_ci COMMENT '表结构JSON(名称/注释/列明细,供describe工具按需取用)',
  `schema_synced_at` datetime DEFAULT NULL COMMENT '表结构最近同步时间',
  `status` char(1) COLLATE utf8mb4_unicode_ci DEFAULT '0' COMMENT '状态(0正常 1停用)',
  `remark` varchar(200) COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '备注',
  `create_dept` bigint DEFAULT NULL COMMENT '创建部门',
  `create_by` bigint DEFAULT NULL COMMENT '创建者',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `update_by` bigint DEFAULT NULL COMMENT '更新者',
  `update_time` datetime DEFAULT NULL COMMENT '更新时间',
  `del_flag` bigint DEFAULT '0' COMMENT '删除标志(0存在 其他删除)',
  `workspace_id` bigint DEFAULT NULL COMMENT '归属工作空间(NULL=平台共享 非空=空间私有)',
  KEY `idx_ds_ws` (`workspace_id`, `del_flag`),
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Agent 问数数据源';
DROP TABLE IF EXISTS `agent_datasource_example`;
CREATE TABLE IF NOT EXISTS `agent_datasource_example` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `datasource_id` bigint NOT NULL COMMENT '数据源ID',
  `question` varchar(500) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '示例问题',
  `sql_content` text COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '示例SQL',
  `sort` int DEFAULT '0' COMMENT '排序',
  `status` char(1) COLLATE utf8mb4_unicode_ci DEFAULT '0' COMMENT '状态(0正常 1停用)',
  `remark` varchar(200) COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '备注',
  `create_dept` bigint DEFAULT NULL,
  `create_by` bigint DEFAULT NULL,
  `create_time` datetime DEFAULT NULL,
  `update_by` bigint DEFAULT NULL,
  `update_time` datetime DEFAULT NULL,
  `del_flag` bigint DEFAULT '0' COMMENT '删除标志(0存在 其他删除)',
  PRIMARY KEY (`id`),
  KEY `idx_ds` (`datasource_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='问数示例库(问题→SQL)';
DROP TABLE IF EXISTS `agent_info`;
CREATE TABLE IF NOT EXISTS `agent_info` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `workspace_id` bigint NOT NULL COMMENT '所属工作空间ID',
  `name` varchar(50) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT 'Agent名称(空间内唯一,2-50字符)',
  `description` varchar(200) COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT 'Agent描述',
  `system_prompt` text COLLATE utf8mb4_unicode_ci COMMENT '系统提示词',
  `welcome_msg` varchar(500) COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '欢迎语',
  `suggested_questions` text COLLATE utf8mb4_unicode_ci COMMENT '推荐问题JSON数组,会话空态展示为可点击快捷提问',
  `model_id` bigint DEFAULT NULL COMMENT '绑定模型配置ID',
  `temperature` decimal(3,2) DEFAULT '0.70' COMMENT '采样温度(0-2)',
  `max_tokens` int DEFAULT '4096' COMMENT '最大输出tokens',
  `token_budget` bigint DEFAULT NULL COMMENT 'Token累计预算(总tokens上限,空为不限)',
  `output_schema` text COLLATE utf8mb4_unicode_ci COMMENT '结构化输出JSON Schema(空为关闭)',
  `team_mode` varchar(20) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'none' COMMENT '团队协作模式(none/sequential)',
  `team_ids` varchar(2000) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '协作成员AgentID列表(逗号分隔)',
  `memory_enabled` char(1) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT '0' COMMENT '长期记忆开关(0关1开)',
  `tool_ids` varchar(500) COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '绑定工具ID列表(逗号分隔)',
  `knowledge_ids` varchar(500) COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '绑定知识库ID列表(逗号分隔)',
  `mcp_ids` varchar(500) COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '绑定MCP服务器ID列表(逗号分隔)',
  `workflow_json` longtext COLLATE utf8mb4_unicode_ci COMMENT '工作流编排JSON(画布,预留)',
  `status` char(1) COLLATE utf8mb4_unicode_ci DEFAULT '0' COMMENT '状态(0草稿 1已发布 2已停用)',
  `create_dept` bigint DEFAULT NULL COMMENT '创建部门',
  `create_by` bigint DEFAULT NULL COMMENT '创建者',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `update_by` bigint DEFAULT NULL COMMENT 'æ›´æ–°è€…',
  `update_time` datetime DEFAULT NULL COMMENT 'æ›´æ–°æ—¶é—´',
  `del_flag` bigint DEFAULT '0' COMMENT '删除标志(0存在 其他删除)',
  `config_hash` VARCHAR(64) NULL COMMENT '发布配置 SHA-256',
  `memory_quota` INT NULL COMMENT '每 agent+user 记忆条数上限,NULL/0=不限;超限按评分淘汰进归档',
  `prompt_binding_mode` VARCHAR(24) NOT NULL DEFAULT 'MANUAL' COMMENT 'MANUAL/LOCKED/FOLLOW_STABLE',
  `prompt_template_id` BIGINT NULL COMMENT '绑定 Prompt 模板ID',
  `prompt_variables` TEXT NULL COMMENT '模板变量值 JSON',
  `prompt_version_id` BIGINT NULL COMMENT 'LOCKED 模式锁定的模板版本ID',
  `published_version` INT NULL COMMENT '当前生效的发布版本号',
  `version` INT NOT NULL DEFAULT 1 COMMENT '配置版本号(每次编辑递增)',
  `workflow_trigger_mode` CHAR(1) NOT NULL DEFAULT '0' COMMENT '工作流触发模式(0关 1每轮自动 2按意图路由)',
  PRIMARY KEY (`id`),
  KEY `idx_workspace` (`workspace_id`),
  KEY `idx_model` (`model_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Agent 定义';
DROP TABLE IF EXISTS `agent_kb_chunk`;
CREATE TABLE IF NOT EXISTS `agent_kb_chunk` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键(Milvus 向量主键)',
  `knowledge_id` bigint NOT NULL COMMENT '所属知识库ID',
  `document_id` bigint NOT NULL COMMENT '所属文档ID',
  `chunk_index` int NOT NULL COMMENT '分块序号(从 0 开始)',
  `content` text COLLATE utf8mb4_unicode_ci COMMENT '分块文本内容',
  `enabled` tinyint(1) NOT NULL DEFAULT '1' COMMENT '是否启用(1启用 0禁用,禁用不参与检索)',
  `page_label` varchar(100) DEFAULT '' COMMENT '来源定位标记(如 page=3/sheet=销售表)',
  `chunk_role` char(1) NOT NULL DEFAULT 'c' COMMENT '分块角色(c=检索子块 p=父块上下文块)',
  `parent_id` bigint DEFAULT NULL COMMENT '父块ID(chunk_role=c 时指向所属父块)',
  `token_count` int NOT NULL DEFAULT '0' COMMENT '估算 token 数(中文按字、英文按词)',
  `hit_count` int NOT NULL DEFAULT '0' COMMENT '累计检索命中次数',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `page_no` int DEFAULT NULL COMMENT '页码(PDF/Excel 页标记解析)',
  `sheet_name` varchar(100) DEFAULT NULL COMMENT '工作表名(Excel)',
  `generation` int NOT NULL DEFAULT '1' COMMENT '分块代次(重建时递增,检索只认文档当前代次)',
  `vector_status` varchar(20) NOT NULL DEFAULT 'SYNCED' COMMENT '向量同步状态(PENDING/SYNCED/FAILED/SKIPPED)',
  `vector_retry_count` int NOT NULL DEFAULT '0' COMMENT '向量写入补偿重试次数',
  `parent_offset_start` int DEFAULT NULL COMMENT '子块在父块内的起始偏移(用于精确剔除被禁用内容)',
  `parent_offset_end` int DEFAULT NULL COMMENT '子块在父块内的结束偏移',
  `heading_path` varchar(500) DEFAULT NULL COMMENT '所属章节路径(如 部署 > 数据库),无标题为 NULL',
  `embedding` BLOB NULL COMMENT 'float32 小端向量(384 维=1536 字节),仅 vector-store=mysql 时使用',
  `embedding_dim` SMALLINT NULL COMMENT '向量维度,与当前配置不一致时检索跳过',
  PRIMARY KEY (`id`),
  KEY `idx_kb_chunk_doc_gen` (`document_id`,`generation`),
  KEY `idx_kb_chunk_vsync` (`vector_status`,`create_time`),
  KEY `idx_kb_chunk_knowledge` (`knowledge_id`),
  KEY `idx_kb_chunk_document` (`document_id`),
  FULLTEXT KEY `ft_chunk_content` (`content`) /*!50100 WITH PARSER `ngram` */ 
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Agent 知识库分块';
DROP TABLE IF EXISTS `agent_kb_zero_hit_log`;
CREATE TABLE IF NOT EXISTS `agent_kb_zero_hit_log` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `agent_id` bigint DEFAULT NULL COMMENT 'Agent ID',
  `session_id` bigint DEFAULT NULL COMMENT '会话 ID',
  `user_id` bigint DEFAULT NULL COMMENT '用户 ID',
  `original_query` varchar(500) DEFAULT NULL COMMENT '用户原始问题',
  `rewritten_query` varchar(500) DEFAULT NULL COMMENT '改写后的检索查询(未改写时与原始问题一致)',
  `knowledge_ids` varchar(200) DEFAULT NULL COMMENT '参与检索的知识库ID(逗号分隔)',
  `create_time` datetime DEFAULT NULL COMMENT '发生时间',
  PRIMARY KEY (`id`),
  KEY `idx_azhl_agent_time` (`agent_id`,`create_time`),
  KEY `idx_azhl_time` (`create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='知识库零命中查询日志';
DROP TABLE IF EXISTS `agent_kb_query_log`;
CREATE TABLE IF NOT EXISTS `agent_kb_query_log` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `agent_id` bigint DEFAULT NULL COMMENT 'Agent ID(客服/工作流链路可能为空)',
  `session_id` bigint DEFAULT NULL COMMENT '会话 ID',
  `user_id` bigint DEFAULT NULL COMMENT '用户 ID',
  `knowledge_ids` varchar(255) DEFAULT '' COMMENT '参与检索的知识库ID(逗号分隔)',
  `query_text` varchar(500) NOT NULL COMMENT '用户原始问题(未改写)',
  `rewritten_query` varchar(500) DEFAULT '' COMMENT '实际送检的查询(改写后;未改写时与原始问题一致)',
  `query_hash` char(32) NOT NULL COMMENT '查询指纹(md5:去空白+小写+折叠空格)',
  `result_count` int DEFAULT '0' COMMENT '最终命中条数',
  `zero_hit` tinyint(1) DEFAULT '0' COMMENT '是否零命中(1是 0否)',
  `top1_score` decimal(8,6) DEFAULT NULL COMMENT '首条分数(按 score_type 解释)',
  `score_type` varchar(20) DEFAULT NULL COMMENT '首条分数口径(cosine/rrf/rerank/bm25)',
  `vector_hits` int DEFAULT '0' COMMENT '向量路召回条数(过滤前)',
  `keyword_hits` int DEFAULT '0' COMMENT '关键词路召回条数(过滤前)',
  `degraded` varchar(255) DEFAULT '' COMMENT '降级原因(逗号分隔的 code)',
  `retrieval_ms` int DEFAULT '0' COMMENT '检索耗时(毫秒)',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  PRIMARY KEY (`id`),
  KEY `idx_kbql_hash` (`query_hash`,`create_time`),
  KEY `idx_kbql_agent` (`agent_id`,`create_time`),
  KEY `idx_kbql_zero` (`zero_hit`,`create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='知识库检索日志';
DROP TABLE IF EXISTS `agent_knowledge`;
CREATE TABLE IF NOT EXISTS `agent_knowledge` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `name` varchar(50) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '知识库名称(全局唯一)',
  `description` varchar(200) COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '知识库描述',
  `kb_scope` varchar(10) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'normal' COMMENT '作用域(normal普通/temp会话临时)',
  `session_id` bigint DEFAULT NULL COMMENT '所属会话ID(仅temp)',
  `status` char(1) COLLATE utf8mb4_unicode_ci DEFAULT '0' COMMENT '状态(0正常 1停用)',
  `embedding_model` varchar(100) COLLATE utf8mb4_unicode_ci DEFAULT 'local-hash' COMMENT '嵌入模型(local-hash 或 agent_model 配置名称)',
  `rerank_model` varchar(100) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '重排序模型名称(agent_model 配置名称)',
  `score_threshold` decimal(4,3) DEFAULT NULL COMMENT '相关度阈值(0~1,作用于向量相似度/精排分,低于该分数的分块不召回;NULL 不过滤)',
  `rerank_threshold` double DEFAULT NULL COMMENT '精排相关度阈值(0~1,作用于 rerankScore;NULL 不过滤精排结果)',
  `fallback_answer_enabled` tinyint(1) NOT NULL DEFAULT 1 COMMENT '零召回兜底:1=允许通用知识回答但须显式声明,0=严格模式一律拒答(金融/医疗场景)',
  `review_required` tinyint(1) NOT NULL DEFAULT 0 COMMENT '上传后需审核(1=新文档落 PENDING_REVIEW)',
  `retrieval_overrides` json DEFAULT NULL COMMENT '库级检索参数覆盖 JSON',
  `vlm_enabled` tinyint(1) NOT NULL DEFAULT 0 COMMENT 'R5-5 图片描述库级开关',
  `vector_backend` varchar(20) COLLATE utf8mb4_unicode_ci DEFAULT 'milvus' COMMENT '向量库后端',
  `vector_dim` int DEFAULT '384' COMMENT '向量维度',
  `chunk_size` int DEFAULT '500' COMMENT '分块大小(字符数)',
  `chunk_overlap` int DEFAULT '50' COMMENT '分块重叠字符数',
  `parent_chunk_enabled` char(1) NOT NULL DEFAULT '0' COMMENT '父子分块开关(1=先切父块再切子块,检索命中子块注入父块全文)',
  `parent_chunk_size` int DEFAULT '2000' COMMENT '父块目标大小(字符)',
  `doc_count` int DEFAULT '0' COMMENT '文档数量',
  `remark` varchar(200) COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '备注',
  `create_dept` bigint DEFAULT NULL COMMENT '创建部门',
  `create_by` bigint DEFAULT NULL COMMENT '创建者',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `update_by` bigint DEFAULT NULL COMMENT 'æ›´æ–°è€…',
  `update_time` datetime DEFAULT NULL COMMENT 'æ›´æ–°æ—¶é—´',
  `del_flag` bigint DEFAULT '0' COMMENT '删除标志(0存在 其他删除)',
  `hide_parent_with_disabled_child` char(1) NOT NULL DEFAULT '1' COMMENT '父块含禁用子块时:1整块不返回 0返回但挖除禁用片段',
  `chunk_unit` varchar(10) NOT NULL DEFAULT 'char' COMMENT '分块计价单位(char字符/token估算)',
  `split_strategy` varchar(20) NOT NULL DEFAULT 'structure' COMMENT '分块策略(structure结构感知/fixed定长对照)',
  `clean_rules` json DEFAULT NULL COMMENT '解析清洗规则 JSON,NULL=默认(headerFooter/blankNormalize 开,nfkc/piiMask 关)',
  `parse_fingerprint` varchar(64) DEFAULT NULL COMMENT '解析管线指纹(sha256:管线版本+清洗规则)',
  `chunk_fingerprint` varchar(64) DEFAULT NULL COMMENT '分块配置指纹(sha256:单位+策略+大小+重叠+父子参数)',
  `workspace_id` BIGINT NULL COMMENT '所属工作空间ID(NULL为全局/历史数据)',
  PRIMARY KEY (`id`),
  KEY `idx_ak_scope` (`kb_scope`),
  KEY `idx_ak_workspace` (`workspace_id`),
  KEY `idx_ak_session` (`session_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Agent 知识库注册';
DROP TABLE IF EXISTS `agent_knowledge_document`;
CREATE TABLE IF NOT EXISTS `agent_knowledge_document` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `knowledge_id` bigint NOT NULL COMMENT '所属知识库ID',
  `file_name` varchar(200) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '原始文件名',
  `file_ext` varchar(20) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '文件扩展名(txt/md/docx/xlsx/pdf)',
  `file_size` bigint DEFAULT '0' COMMENT '文件大小(字节)',
  `file_md5` varchar(32) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '文件内容MD5(同库去重)',
  `file_path` varchar(500) COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '文件存储路径',
  `status` char(1) COLLATE utf8mb4_unicode_ci DEFAULT '0' COMMENT '解析向量化状态(0处理中 1可用 2失败)',
  `fail_reason` varchar(500) COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '失败原因',
  `chunk_count` int DEFAULT '0' COMMENT '分块数量',
  `create_dept` bigint DEFAULT NULL COMMENT '创建部门',
  `create_by` bigint DEFAULT NULL COMMENT '创建者',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `update_by` bigint DEFAULT NULL COMMENT 'æ›´æ–°è€…',
  `update_time` datetime DEFAULT NULL COMMENT 'æ›´æ–°æ—¶é—´',
  `del_flag` bigint DEFAULT '0' COMMENT '删除标志(0存在 其他删除)',
  `active_generation` int NOT NULL DEFAULT '1' COMMENT '当前生效代次(重建成功后才切换)',
  `notice` varchar(200) DEFAULT '' COMMENT '处理提示(如向量写入失败待补偿)',
  `visibility` varchar(20) NOT NULL DEFAULT 'INHERIT' COMMENT '可见性:INHERIT/RESTRICTED',
  `publish_status` varchar(20) NOT NULL DEFAULT 'PUBLISHED' COMMENT 'DRAFT/PENDING_REVIEW/PUBLISHED/REJECTED',
  `effective_at` datetime DEFAULT NULL COMMENT '生效时间(NULL=立即)',
  `expires_at` datetime DEFAULT NULL COMMENT '过期时间(NULL=不过期)',
  `trashed` tinyint(1) NOT NULL DEFAULT 0 COMMENT '回收站(1=已入回收站)',
  `trashed_at` datetime DEFAULT NULL COMMENT '入回收站时间',
  `config_stale` tinyint(1) NOT NULL DEFAULT 0 COMMENT '配置过期(1=知识库口径已变,待差异重建)',
  `parse_fingerprint` varchar(64) DEFAULT NULL COMMENT '摄取时的解析管线指纹(复用判定依据)',
  `parsed_text` mediumtext COMMENT '解析+清洗后的正文(超长不落不阻断)',
  PRIMARY KEY (`id`),
  KEY `idx_kb_doc_knowledge` (`knowledge_id`),
  KEY `idx_kb_doc_visible` (`knowledge_id`,`trashed`,`publish_status`),
  KEY `idx_akd_kb_md5` (`knowledge_id`,`file_md5`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Agent 知识库文档';
DROP TABLE IF EXISTS `agent_market_install`;
CREATE TABLE IF NOT EXISTS `agent_market_install` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `listing_id` bigint NOT NULL COMMENT '市场条目ID',
  `workspace_id` bigint NOT NULL COMMENT '安装目标工作空间ID',
  `agent_copy_id` bigint NOT NULL COMMENT 'ç”Ÿæˆçš„Agentå‰¯æœ¬ID',
  `version_no` int NOT NULL COMMENT '安装的Listing版本号',
  `bindings_json` text COLLATE utf8mb4_unicode_ci COMMENT '资源绑定关系(JSON)',
  `prev_snapshot_json` longtext COLLATE utf8mb4_unicode_ci COMMENT '升级前配置快照(一键回退用)',
  `user_id` bigint NOT NULL COMMENT '安装人用户ID',
  `create_dept` bigint DEFAULT NULL COMMENT '创建部门',
  `create_by` bigint DEFAULT NULL COMMENT '创建者',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `update_by` bigint DEFAULT NULL COMMENT 'æ›´æ–°è€…',
  `update_time` datetime DEFAULT NULL COMMENT 'æ›´æ–°æ—¶é—´',
  PRIMARY KEY (`id`),
  KEY `idx_listing` (`listing_id`),
  KEY `idx_workspace` (`workspace_id`),
  KEY `idx_agent_copy` (`agent_copy_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Agent 市场安装记录';
DROP TABLE IF EXISTS `agent_market_listing`;
CREATE TABLE IF NOT EXISTS `agent_market_listing` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `agent_id` bigint NOT NULL COMMENT '源Agent ID',
  `workspace_id` bigint NOT NULL COMMENT '源工作空间ID',
  `version_no` int NOT NULL DEFAULT '1' COMMENT '版本号(每次提交递增)',
  `name` varchar(50) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '市场展示名',
  `category` varchar(30) COLLATE utf8mb4_unicode_ci DEFAULT 'general' COMMENT '分类(general/office/dev/data/life/other)',
  `icon` varchar(200) COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '图标(emoji或URL)',
  `description` varchar(500) COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '详情说明',
  `usage_doc` varchar(500) COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '使用说明',
  `deps_json` text COLLATE utf8mb4_unicode_ci COMMENT '依赖声明摘要(JSON:模型/工具/知识库)',
  `snapshot_json` longtext COLLATE utf8mb4_unicode_ci COMMENT '版本快照(Agent配置不可变快照)',
  `status` char(1) COLLATE utf8mb4_unicode_ci DEFAULT '0' COMMENT '状态(0待审核 1已上架 2已驳回 3已下架)',
  `review_reason` varchar(300) COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '审核/下架理由',
  `publisher_id` bigint NOT NULL COMMENT '发布者用户ID',
  `install_count` int DEFAULT '0' COMMENT '安装量',
  `rating_sum` int DEFAULT '0' COMMENT '评分星数合计',
  `rating_count` int DEFAULT '0' COMMENT '评分人数',
  `create_dept` bigint DEFAULT NULL COMMENT '创建部门',
  `create_by` bigint DEFAULT NULL COMMENT '创建者',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `update_by` bigint DEFAULT NULL COMMENT 'æ›´æ–°è€…',
  `update_time` datetime DEFAULT NULL COMMENT 'æ›´æ–°æ—¶é—´',
  `del_flag` bigint DEFAULT '0' COMMENT '删除标志(0存在 其他删除)',
  PRIMARY KEY (`id`),
  KEY `idx_agent` (`agent_id`),
  KEY `idx_status` (`status`),
  KEY `idx_category` (`category`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Agent 市场条目';
DROP TABLE IF EXISTS `agent_market_review`;
CREATE TABLE IF NOT EXISTS `agent_market_review` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `listing_id` bigint NOT NULL COMMENT '市场条目ID',
  `install_id` bigint NOT NULL COMMENT '安装记录ID',
  `user_id` bigint NOT NULL COMMENT '评分用户ID',
  `stars` int NOT NULL COMMENT 'æ˜Ÿæ•°(1-5)',
  `comment` varchar(300) COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '文字评价(可选)',
  `create_dept` bigint DEFAULT NULL COMMENT '创建部门',
  `create_by` bigint DEFAULT NULL COMMENT '创建者',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `update_by` bigint DEFAULT NULL COMMENT 'æ›´æ–°è€…',
  `update_time` datetime DEFAULT NULL COMMENT 'æ›´æ–°æ—¶é—´',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_install` (`install_id`),
  KEY `idx_listing` (`listing_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Agent 市场评分';
DROP TABLE IF EXISTS `agent_mcp_server`;
CREATE TABLE IF NOT EXISTS `agent_mcp_server` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `workspace_id` bigint DEFAULT NULL COMMENT '归属工作空间(NULL=平台共享,非空=空间私有)',
  `name` varchar(50) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT 'MCP服务器名称(同一空间内唯一)',
  `description` varchar(200) COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '描述',
  `server_type` varchar(20) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'streamable_http' COMMENT '传输类型(sse/streamable_http)',
  `url` varchar(300) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT 'MCP服务端地址',
  `headers` text COLLATE utf8mb4_unicode_ci COMMENT '自定义请求头(JSON)',
  `timeout_seconds` int DEFAULT '30' COMMENT '请求超时(秒)',
  `approval_mode` varchar(10) COLLATE utf8mb4_unicode_ci DEFAULT 'auto' COMMENT '工具审批模式(auto自动执行/ask需人工审批/deny拒绝)',
  `approval_timeout` int DEFAULT '120' COMMENT 'ask模式审批等待超时(秒)',
  `tool_count` int DEFAULT '0' COMMENT '最近一次连接测试发现的工具数',
  `status` char(1) COLLATE utf8mb4_unicode_ci DEFAULT '0' COMMENT '状态(0正常 1停用)',
  `remark` varchar(200) COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '备注',
  `create_dept` bigint DEFAULT NULL COMMENT '创建部门',
  `create_by` bigint DEFAULT NULL COMMENT '创建者',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `update_by` bigint DEFAULT NULL COMMENT '更新者',
  `update_time` datetime DEFAULT NULL COMMENT '更新时间',
  `del_flag` bigint DEFAULT '0' COMMENT '删除标志(0存在 其他删除)',
  PRIMARY KEY (`id`),
  KEY `idx_mcp_ws` (`workspace_id`,`del_flag`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Agent MCP 服务器注册';
DROP TABLE IF EXISTS `agent_memory`;
CREATE TABLE IF NOT EXISTS `agent_memory` (
  `id` bigint NOT NULL COMMENT '主键',
  `agent_id` bigint NOT NULL COMMENT 'AgentID',
  `workspace_id` bigint DEFAULT NULL COMMENT '工作空间ID',
  `user_id` bigint DEFAULT NULL COMMENT '用户ID',
  `content` varchar(1000) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '记忆内容',
  `source_session_id` bigint DEFAULT NULL COMMENT '来源会话ID',
  `create_dept` bigint DEFAULT NULL COMMENT '创建部门',
  `create_by` bigint DEFAULT NULL COMMENT '创建者',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `update_by` bigint DEFAULT NULL COMMENT '更新者',
  `update_time` datetime DEFAULT NULL COMMENT '更新时间',
  `del_flag` bigint NOT NULL DEFAULT '0' COMMENT '删除标志(0存在 其他删除)',
  `embedding` BLOB NULL COMMENT 'float32 小端向量,仅 memory.vector.backend=mysql 时使用',
  `embedding_dim` SMALLINT NULL COMMENT '向量维度,与当前模型不一致时检索跳过',
  `embedding_model` VARCHAR(64) NULL COMMENT '向量化模型标识,换模型后旧向量失效',
  `expire_at` DATETIME NULL COMMENT 'TTL 过期时间,NULL=永久;注入过滤+治理任务归档',
  `fact_key` VARCHAR(128) NULL COMMENT '事实归一化键(冲突替代依据)',
  `hit_count` INT NOT NULL DEFAULT 0 COMMENT '注入命中次数',
  `importance` INT NOT NULL DEFAULT 5 COMMENT '重要性 1-10',
  `last_hit_at` DATETIME NULL COMMENT '最近一次注入时间',
  PRIMARY KEY (`id`),
  KEY `idx_agent_user` (`agent_id`,`user_id`),
  FULLTEXT INDEX `idx_memory_keyword` (`content`, `fact_key`) WITH PARSER ngram
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Agent 长期记忆';
DROP TABLE IF EXISTS `agent_message`;
CREATE TABLE IF NOT EXISTS `agent_message` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `session_id` bigint NOT NULL COMMENT '会话ID',
  `role` varchar(10) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '角色(user/assistant)',
  `content` text COLLATE utf8mb4_unicode_ci COMMENT '消息内容',
  `ext_json` mediumtext COLLATE utf8mb4_unicode_ci COMMENT '扩展信息(工具执行/问数结果等元数据)',
  `feedback` varchar(10) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '用户反馈(like 点赞 / dislike 点踩)',
  `citations` text COLLATE utf8mb4_unicode_ci COMMENT '引用溯源(知识库召回命中 JSON 数组)',
  `create_dept` bigint DEFAULT NULL COMMENT '创建部门',
  `create_by` bigint DEFAULT NULL COMMENT '创建者',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `update_by` bigint DEFAULT NULL COMMENT 'æ›´æ–°è€…',
  `update_time` datetime DEFAULT NULL COMMENT 'æ›´æ–°æ—¶é—´',
  `attachments` text COLLATE utf8mb4_unicode_ci COMMENT '图片附件(JSON数组)',
  `client_msg_id` VARCHAR(64) NULL COMMENT '客户端幂等键',
  `feedback_comment` VARCHAR(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL COMMENT '反馈备注',
  `feedback_tags` VARCHAR(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL COMMENT '反馈标签(逗号分隔)',
  `feedback_time` DATETIME NULL COMMENT '反馈时间',
  `sender_type` VARCHAR(16) DEFAULT 'user' COMMENT 'user/assistant/seat/system',
  `ticket_id` BIGINT NULL COMMENT '客服工单ID',
  PRIMARY KEY (`id`),
  KEY `idx_session` (`session_id`),
  KEY `idx_session_ctime` (`session_id`,`create_time`),
  KEY `idx_feedback_time` (`feedback`,`feedback_time`),
  UNIQUE KEY `uk_am_client` (`session_id`,`client_msg_id`),
  KEY `idx_session_id` (`session_id`,`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Agent 会话消息';
DROP TABLE IF EXISTS `agent_model`;
CREATE TABLE IF NOT EXISTS `agent_model` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `name` varchar(50) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '模型配置名称(全局唯一)',
  `provider` varchar(30) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '提供商(openai/anthropic/dashscope/ollama/custom)',
  `base_url` varchar(200) COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT 'API基础地址',
  `api_key` varchar(500) COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT 'API密钥',
  `model_name` varchar(100) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '模型标识(如 gpt-4o/qwen-plus)',
  `temperature` decimal(3,2) DEFAULT '0.70' COMMENT '采样温度(0-2)',
  `max_tokens` int DEFAULT '4096' COMMENT '最大输出tokens',
  `status` char(1) COLLATE utf8mb4_unicode_ci DEFAULT '0' COMMENT '状态(0正常 1停用)',
  `remark` varchar(200) COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '备注',
  `create_dept` bigint DEFAULT NULL COMMENT '创建部门',
  `create_by` bigint DEFAULT NULL COMMENT '创建者',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `update_by` bigint DEFAULT NULL COMMENT 'æ›´æ–°è€…',
  `update_time` datetime DEFAULT NULL COMMENT 'æ›´æ–°æ—¶é—´',
  `del_flag` bigint DEFAULT '0' COMMENT '删除标志(0存在 其他删除)',
  `support_vision` char(1) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT '0' COMMENT '支持图片输入(0否 1是)',
  `workspace_id` bigint DEFAULT NULL COMMENT '归属工作空间(NULL=平台共享 非空=空间私有)',
  KEY `idx_model_ws` (`workspace_id`, `del_flag`),
  `context_window` INT NULL COMMENT '上下文窗口(tokens),未配置时会话页不展示剩余进度',
  `price_currency` VARCHAR(8) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'USD' COMMENT '定价币种',
  `price_input` DECIMAL(10,6) NULL COMMENT '输入单价(美元/百万token)',
  `price_output` DECIMAL(10,6) NULL COMMENT '输出单价(美元/百万token)',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Agent 模型配置';
DROP TABLE IF EXISTS `agent_provider`;
CREATE TABLE IF NOT EXISTS `agent_provider` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `provider_code` varchar(30) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '供应商编码(唯一,如 openai/anthropic/dashscope/ollama/custom)',
  `provider_name` varchar(50) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '供应商展示名',
  `status` char(1) COLLATE utf8mb4_unicode_ci DEFAULT '0' COMMENT '状态(0正常 1停用)',
  `remark` varchar(200) COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '备注',
  `create_dept` bigint DEFAULT NULL COMMENT '创建部门',
  `create_by` bigint DEFAULT NULL COMMENT '创建者',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `update_by` bigint DEFAULT NULL COMMENT 'æ›´æ–°è€…',
  `update_time` datetime DEFAULT NULL COMMENT 'æ›´æ–°æ—¶é—´',
  `del_flag` bigint DEFAULT '0' COMMENT '删除标志(0存在 其他删除)',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_code` (`provider_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Agent 模型供应商配置(平台级)';
DROP TABLE IF EXISTS `agent_semantic_dimension`;
CREATE TABLE IF NOT EXISTS `agent_semantic_dimension` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `datasource_id` bigint NOT NULL COMMENT '数据源ID',
  `table_name` varchar(128) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '表名',
  `column_name` varchar(128) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '列名',
  `display_name` varchar(100) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '业务显示名',
  `synonyms` varchar(500) COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '同义词(逗号分隔)',
  `dim_type` varchar(20) COLLATE utf8mb4_unicode_ci DEFAULT 'text' COMMENT '维度类型(enum/time/id/text)',
  `is_time` tinyint DEFAULT '0' COMMENT '是否默认时间维度(0否 1是)',
  `time_format` varchar(50) COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '时间粒度格式,如 %Y-%m',
  `description` varchar(500) COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '说明',
  `status` char(1) COLLATE utf8mb4_unicode_ci DEFAULT '0' COMMENT '状态(0正常 1停用)',
  `sort` int DEFAULT '0' COMMENT '排序',
  `create_dept` bigint DEFAULT NULL,
  `create_by` bigint DEFAULT NULL,
  `create_time` datetime DEFAULT NULL,
  `update_by` bigint DEFAULT NULL,
  `update_time` datetime DEFAULT NULL,
  `del_flag` bigint DEFAULT '0' COMMENT '删除标志(0存在 其他删除)',
  PRIMARY KEY (`id`),
  KEY `idx_ds` (`datasource_id`),
  KEY `idx_ds_table_col` (`datasource_id`,`table_name`,`column_name`),
  KEY `idx_ds_status` (`datasource_id`,`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='问数语义维度';
DROP TABLE IF EXISTS `agent_semantic_metric`;
CREATE TABLE IF NOT EXISTS `agent_semantic_metric` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `datasource_id` bigint NOT NULL COMMENT '数据源ID',
  `name` varchar(100) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '指标名',
  `synonyms` varchar(500) COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '同义词(逗号分隔)',
  `base_table` varchar(128) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '基表名',
  `expression` varchar(1000) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '可执行表达式,如 SUM(pay_amount)',
  `default_filters` varchar(500) COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '默认过滤条件,如 pay_status IN (''PAID'',''SHIPPED'')',
  `unit` varchar(20) COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '单位(元/人/次等)',
  `format` varchar(50) COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '展示格式,如 #,##0.00',
  `description` varchar(500) COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '口径说明',
  `status` char(1) COLLATE utf8mb4_unicode_ci DEFAULT '0' COMMENT '状态(0正常 1停用)',
  `sort` int DEFAULT '0' COMMENT '排序',
  `create_dept` bigint DEFAULT NULL,
  `create_by` bigint DEFAULT NULL,
  `create_time` datetime DEFAULT NULL,
  `update_by` bigint DEFAULT NULL,
  `update_time` datetime DEFAULT NULL,
  `del_flag` bigint DEFAULT '0' COMMENT '删除标志(0存在 其他删除)',
  PRIMARY KEY (`id`),
  KEY `idx_ds` (`datasource_id`),
  KEY `idx_ds_status` (`datasource_id`,`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='问数语义指标';
DROP TABLE IF EXISTS `agent_semantic_value`;
CREATE TABLE IF NOT EXISTS `agent_semantic_value` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `datasource_id` bigint NOT NULL COMMENT '数据源ID',
  `table_name` varchar(128) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '表名',
  `column_name` varchar(128) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '列名',
  `raw_value` varchar(200) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '库内真实值',
  `display_name` varchar(100) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '业务显示名',
  `synonyms` varchar(500) COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '别名(逗号分隔)',
  `sort` int DEFAULT '0' COMMENT '排序',
  `status` char(1) COLLATE utf8mb4_unicode_ci DEFAULT '0' COMMENT '状态(0正常 1停用)',
  `create_dept` bigint DEFAULT NULL,
  `create_by` bigint DEFAULT NULL,
  `create_time` datetime DEFAULT NULL,
  `update_by` bigint DEFAULT NULL,
  `update_time` datetime DEFAULT NULL,
  `del_flag` bigint DEFAULT '0' COMMENT '删除标志(0存在 其他删除)',
  PRIMARY KEY (`id`),
  KEY `idx_ds_table_col` (`datasource_id`,`table_name`,`column_name`),
  KEY `idx_ds_status` (`datasource_id`,`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='问数语义枚举值字典';
DROP TABLE IF EXISTS `agent_session`;
CREATE TABLE IF NOT EXISTS `agent_session` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `agent_id` bigint NOT NULL COMMENT 'AgentID',
  `user_id` bigint NOT NULL COMMENT '会话所属用户ID',
  `title` varchar(100) COLLATE utf8mb4_unicode_ci DEFAULT '新会话' COMMENT '会话标题',
  `message_count` int DEFAULT '0' COMMENT '消息数量',
  `pinned` char(1) COLLATE utf8mb4_unicode_ci DEFAULT '0' COMMENT '是否置顶(0否 1是)',
  `last_msg_time` datetime DEFAULT NULL COMMENT '最后消息时间',
  `create_dept` bigint DEFAULT NULL COMMENT '创建部门',
  `create_by` bigint DEFAULT NULL COMMENT '创建者',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `update_by` bigint DEFAULT NULL COMMENT 'æ›´æ–°è€…',
  `update_time` datetime DEFAULT NULL COMMENT 'æ›´æ–°æ—¶é—´',
  `del_flag` bigint DEFAULT '0' COMMENT '删除标志(0存在 其他删除)',
  `channel_code` VARCHAR(64) NULL COMMENT '客服渠道编码',
  `cs_ticket_id` BIGINT NULL COMMENT '当前工单ID',
  `scene` VARCHAR(16) DEFAULT 'console' COMMENT 'console/cs',
  `summary_text` TEXT NULL COMMENT 'L2 滚动摘要:summary_upto_msg_id 及之前的对话要点',
  `summary_upto_msg_id` BIGINT NOT NULL DEFAULT 0 COMMENT 'L2 摘要水位:最后一条已压缩消息的 id',
  `temp` TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否调试临时会话(0否 1是)',
  `visitor_id` BIGINT NULL COMMENT '客服访客ID',
  PRIMARY KEY (`id`),
  KEY `idx_agent` (`agent_id`),
  KEY `idx_user` (`user_id`),
  KEY `idx_agent_ctime` (`agent_id`,`create_time` DESC),
  KEY `idx_agent_session_temp` (`temp`,`create_time`),
  KEY `idx_as_scene_visitor` (`scene`,`visitor_id`),
  KEY `idx_user_pinned_last` (`user_id`,`pinned`,`last_msg_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Agent 会话';
DROP TABLE IF EXISTS `agent_token_usage`;
CREATE TABLE IF NOT EXISTS `agent_token_usage` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `workspace_id` bigint NOT NULL COMMENT '工作空间ID',
  `agent_id` bigint NOT NULL COMMENT 'Agent ID',
  `session_id` bigint DEFAULT NULL COMMENT '会话ID',
  `round_request_id` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '回合请求ID(同一回合多次调用共享)',
  `user_id` bigint NOT NULL COMMENT '发起人用户ID',
  `source` varchar(10) COLLATE utf8mb4_unicode_ci DEFAULT 'chat' COMMENT '来源(chat对话/api接口/debug调试)',
  `api_key_id` bigint DEFAULT NULL COMMENT 'API Key ID(API调用时)',
  `model_id` bigint DEFAULT NULL COMMENT '模型配置ID',
  `model_name` varchar(100) COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '模型标识',
  `prompt_tokens` int DEFAULT '0' COMMENT '输入token数',
  `completion_tokens` int DEFAULT '0' COMMENT '输出token数',
  `total_tokens` int DEFAULT '0' COMMENT 'æ€»tokenæ•°',
  `estimated` char(1) COLLATE utf8mb4_unicode_ci DEFAULT '1' COMMENT '是否估算(0实测 1估算)',
  `success` char(1) COLLATE utf8mb4_unicode_ci DEFAULT '0' COMMENT '是否成功(0成功 1失败)',
  `call_time` datetime DEFAULT NULL COMMENT '调用时间',
  `create_time` datetime DEFAULT NULL COMMENT '记录写入时间',
  `channel_code` VARCHAR(64) NULL COMMENT '客服渠道编码',
  PRIMARY KEY (`id`),
  KEY `idx_ws_time` (`workspace_id`,`call_time`),
  KEY `idx_agent` (`agent_id`),
  KEY `idx_session` (`session_id`),
  KEY `idx_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Agent Token 用量明细';
DROP TABLE IF EXISTS `agent_token_usage_daily`;
CREATE TABLE IF NOT EXISTS `agent_token_usage_daily` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `stat_date` date NOT NULL COMMENT '统计日期',
  `workspace_id` bigint NOT NULL COMMENT '工作空间ID',
  `agent_id` bigint NOT NULL COMMENT 'Agent ID',
  `model_name` varchar(100) COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '模型标识',
  `user_id` bigint NOT NULL COMMENT 'ç”¨æˆ·ID',
  `source` varchar(10) COLLATE utf8mb4_unicode_ci DEFAULT 'chat' COMMENT '来源(chat/api/debug)',
  `estimated` char(1) COLLATE utf8mb4_unicode_ci DEFAULT '1' COMMENT '是否估算(0实测 1估算)',
  `prompt_tokens` int DEFAULT '0' COMMENT '输入token合计',
  `completion_tokens` int DEFAULT '0' COMMENT '输出token合计',
  `total_tokens` int DEFAULT '0' COMMENT '总token合计',
  `call_count` int DEFAULT '0' COMMENT '调用次数',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `update_time` datetime DEFAULT NULL COMMENT 'æ›´æ–°æ—¶é—´',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_dim` (`stat_date`,`workspace_id`,`agent_id`,`model_name`,`user_id`,`source`,`estimated`),
  KEY `idx_ws_date` (`workspace_id`,`stat_date`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Agent Token 用量日汇总';
DROP TABLE IF EXISTS `agent_tool`;
CREATE TABLE IF NOT EXISTS `agent_tool` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `name` varchar(50) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '工具名称(全局唯一)',
  `description` varchar(200) COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '工具描述',
  `tool_type` varchar(20) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'builtin' COMMENT '工具类型(builtin内置/http外部接口)',
  `datasource_id` bigint DEFAULT NULL COMMENT '绑定数据源ID(仅sql类型工具)',
  `http_method` varchar(10) COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT 'HTTP方法(GET/POST/PUT/DELETE)',
  `http_url` varchar(300) COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT 'HTTP接口地址',
  `http_headers` text COLLATE utf8mb4_unicode_ci COMMENT 'HTTP请求头(JSON)',
  `http_params` text COLLATE utf8mb4_unicode_ci COMMENT 'HTTP请求参数模板(JSON)',
  `sandbox_image` varchar(200) COLLATE utf8mb4_unicode_ci DEFAULT 'python:3.11-slim' COMMENT '沙箱镜像',
  `sandbox_timeout` int DEFAULT '30' COMMENT '沙箱执行超时(秒)',
  `sandbox_memory` varchar(20) COLLATE utf8mb4_unicode_ci DEFAULT '128m' COMMENT '沙箱内存限制',
  `sandbox_cpus` varchar(10) COLLATE utf8mb4_unicode_ci DEFAULT '0.5' COMMENT '沙箱CPU限制',
  `status` char(1) COLLATE utf8mb4_unicode_ci DEFAULT '0' COMMENT '状态(0正常 1停用)',
  `approval_mode` varchar(10) COLLATE utf8mb4_unicode_ci DEFAULT 'auto' COMMENT '审批模式(auto/ask/deny)',
  `approval_timeout` int DEFAULT '120' COMMENT 'ask模式审批等待超时(秒)',
  `risk_level` varchar(16) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'READ' COMMENT '风险分级:READ/WRITE/HIGH_RISK',
  `action_key` varchar(128) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '幂等业务键模板,占位符 {param} 从工具入参取值,空则不做操作幂等',
  `daily_limit` int DEFAULT NULL COMMENT '动作级日限次(按 空间+访客+工具 日窗口,空则不限)',
  `remark` varchar(200) COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '备注',
  `create_dept` bigint DEFAULT NULL COMMENT '创建部门',
  `create_by` bigint DEFAULT NULL COMMENT '创建者',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `update_by` bigint DEFAULT NULL COMMENT 'æ›´æ–°è€…',
  `update_time` datetime DEFAULT NULL COMMENT 'æ›´æ–°æ—¶é—´',
  `del_flag` bigint DEFAULT '0' COMMENT '删除标志(0存在 其他删除)',
  `workspace_id` bigint DEFAULT NULL COMMENT '归属工作空间(NULL=平台共享 非空=空间私有)',
  KEY `idx_tool_ws` (`workspace_id`, `del_flag`),
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Agent 工具注册';
DROP TABLE IF EXISTS `agent_trigger`;
CREATE TABLE IF NOT EXISTS `agent_trigger` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `name` varchar(50) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '触发器名称',
  `workspace_id` bigint DEFAULT NULL COMMENT '工作空间ID',
  `trigger_type` varchar(10) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'cron' COMMENT '触发类型(cron定时/webhook事件)',
  `target_type` varchar(10) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'workflow' COMMENT '目标类型(agent对话/workflow工作流)',
  `target_id` bigint NOT NULL COMMENT '目标ID(AgentID;工作流即该 Agent 的工作流)',
  `inputs` text COLLATE utf8mb4_unicode_ci COMMENT '输入模板(agent:消息文本;workflow:输入变量JSON)',
  `cron_expr` varchar(50) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT 'Cron表达式(定时触发器必填)',
  `secret` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT 'Webhook密钥(事件触发器必填)',
  `enabled` char(1) COLLATE utf8mb4_unicode_ci DEFAULT '0' COMMENT '是否启用(0启用 1停用)',
  `last_run_time` datetime DEFAULT NULL COMMENT '最近执行时间',
  `last_run_status` varchar(10) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '最近执行状态(success/fail)',
  `run_count` int DEFAULT '0' COMMENT '累计执行次数',
  `remark` varchar(200) COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '备注',
  `create_dept` bigint DEFAULT NULL COMMENT '创建部门',
  `create_by` bigint DEFAULT NULL COMMENT '创建者',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `update_by` bigint DEFAULT NULL COMMENT '更新者',
  `update_time` datetime DEFAULT NULL COMMENT '更新时间',
  `del_flag` bigint DEFAULT '0' COMMENT '删除标志(0存在 其他删除)',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_trigger_secret` (`secret`),
  KEY `idx_trigger_enabled` (`enabled`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Agent 触发器';
DROP TABLE IF EXISTS `agent_trigger_log`;
CREATE TABLE IF NOT EXISTS `agent_trigger_log` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `trigger_id` bigint NOT NULL COMMENT '触发器ID',
  `trigger_name` varchar(50) COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '触发器名称(冗余便于日志检索)',
  `trigger_type` varchar(10) COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '触发类型(cron/webhook)',
  `target_type` varchar(10) COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '目标类型(agent/workflow)',
  `target_id` bigint DEFAULT NULL COMMENT '目标ID',
  `invoke_source` varchar(10) COLLATE utf8mb4_unicode_ci DEFAULT 'cron' COMMENT '调用来源(cron定时/webhook事件/manual手动)',
  `status` varchar(10) COLLATE utf8mb4_unicode_ci DEFAULT 'success' COMMENT '执行状态(success/fail)',
  `message` varchar(500) COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '执行结果说明',
  `cost_ms` bigint DEFAULT '0' COMMENT '执行耗时(毫秒)',
  `run_time` datetime DEFAULT NULL COMMENT '执行时间',
  `create_dept` bigint DEFAULT NULL COMMENT '创建部门',
  `create_by` bigint DEFAULT NULL COMMENT '创建者',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `update_by` bigint DEFAULT NULL COMMENT '更新者',
  `update_time` datetime DEFAULT NULL COMMENT '更新时间',
  `del_flag` bigint DEFAULT '0' COMMENT '删除标志(0存在 其他删除)',
  PRIMARY KEY (`id`),
  KEY `idx_log_trigger` (`trigger_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Agent 触发器执行记录';
DROP TABLE IF EXISTS `agent_workflow_run`;
CREATE TABLE IF NOT EXISTS `agent_workflow_run` (
  `id` bigint NOT NULL COMMENT '主键',
  `agent_id` bigint NOT NULL COMMENT 'AgentID',
  `workspace_id` bigint DEFAULT NULL COMMENT '工作空间ID',
  `workflow_json` mediumtext COLLATE utf8mb4_unicode_ci COMMENT '工作流JSON快照',
  `inputs` text COLLATE utf8mb4_unicode_ci COMMENT '试运行输入',
  `vars_json` mediumtext COLLATE utf8mb4_unicode_ci COMMENT '变量上下文快照(JSON)',
  `trace_json` mediumtext COLLATE utf8mb4_unicode_ci COMMENT '节点执行轨迹快照(JSON)',
  `completed_json` text COLLATE utf8mb4_unicode_ci COMMENT '已完成节点ID列表(JSON数组)',
  `skipped_json` text COLLATE utf8mb4_unicode_ci COMMENT '已跳过节点ID列表(JSON数组)',
  `status` varchar(32) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'running' COMMENT '状态(pending_approval/success/fail)',
  `pending_node` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '等待审批的节点ID',
  `approval_id` bigint DEFAULT NULL COMMENT '关联审批单ID',
  `user_id` bigint DEFAULT NULL COMMENT '发起人用户ID',
  `create_dept` bigint DEFAULT NULL COMMENT '创建部门',
  `create_by` bigint DEFAULT NULL COMMENT '创建者',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `update_by` bigint DEFAULT NULL COMMENT '更新者',
  `update_time` datetime DEFAULT NULL COMMENT '更新时间',
  `del_flag` bigint NOT NULL DEFAULT '0' COMMENT '删除标志(0存在 其他删除)',
  `idempotency_key` VARCHAR(128) NULL COMMENT '幂等键(agent内唯一,防重复投递)',
  PRIMARY KEY (`id`),
  KEY `idx_agent_status` (`agent_id`,`status`),
  UNIQUE KEY `uk_run_idem` (`agent_id`,`idempotency_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='工作流运行实例';
DROP TABLE IF EXISTS `agent_workflow_version`;
CREATE TABLE IF NOT EXISTS `agent_workflow_version` (
  `id` bigint NOT NULL COMMENT '主键',
  `agent_id` bigint NOT NULL COMMENT 'AgentID',
  `workspace_id` bigint DEFAULT NULL COMMENT '工作空间ID',
  `version` int NOT NULL COMMENT '版本号(按Agent递增)',
  `workflow_json` mediumtext CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci COMMENT '工作流JSON快照',
  `remark` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '版本备注',
  `create_dept` bigint DEFAULT NULL COMMENT '创建部门',
  `create_by` bigint DEFAULT NULL COMMENT '创建者',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `update_by` bigint DEFAULT NULL COMMENT '更新者',
  `update_time` datetime DEFAULT NULL COMMENT '更新时间',
  `del_flag` bigint NOT NULL DEFAULT '0' COMMENT '删除标志(0存在 其他删除)',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_agent_version` (`agent_id`,`version`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='工作流版本快照';
DROP TABLE IF EXISTS `agent_workspace`;
CREATE TABLE IF NOT EXISTS `agent_workspace` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `name` varchar(30) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '空间名称(全局唯一,2-30字符)',
  `description` varchar(200) COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '空间描述',
  `status` char(1) COLLATE utf8mb4_unicode_ci DEFAULT '0' COMMENT '状态(0正常 1停用)',
  `owner_id` bigint NOT NULL COMMENT '空间所有者(首任空间管理员)用户ID',
  `dept_id` bigint DEFAULT NULL COMMENT '归属部门ID(管理归属,可转移)',
  `create_dept` bigint DEFAULT NULL COMMENT '创建部门',
  `create_by` bigint DEFAULT NULL COMMENT '创建者',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `update_by` bigint DEFAULT NULL COMMENT 'æ›´æ–°è€…',
  `update_time` datetime DEFAULT NULL COMMENT 'æ›´æ–°æ—¶é—´',
  `del_flag` bigint DEFAULT '0' COMMENT '删除标志(0存在 其他删除)',
  PRIMARY KEY (`id`),
  KEY `idx_dept` (`dept_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Agent 工作空间';
DROP TABLE IF EXISTS `agent_workspace_member`;
CREATE TABLE IF NOT EXISTS `agent_workspace_member` (
  `id` bigint NOT NULL COMMENT '主键(雪花)',
  `workspace_id` bigint NOT NULL COMMENT '工作空间ID',
  `user_id` bigint NOT NULL COMMENT '系统用户ID',
  `role` varchar(20) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '空间角色(admin/developer/runner)',
  `create_by` bigint DEFAULT NULL COMMENT '创建者',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `update_by` bigint DEFAULT NULL COMMENT 'æ›´æ–°è€…',
  `update_time` datetime DEFAULT NULL COMMENT 'æ›´æ–°æ—¶é—´',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_ws_user` (`workspace_id`,`user_id`),
  KEY `idx_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Agent 工作空间成员';
DROP TABLE IF EXISTS `flow_category`;
CREATE TABLE IF NOT EXISTS `flow_category` (
  `category_id` bigint NOT NULL COMMENT '流程分类ID',
  `parent_id` bigint DEFAULT '0' COMMENT '父流程分类id',
  `ancestors` varchar(500) COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '祖级列表',
  `category_name` varchar(30) COLLATE utf8mb4_general_ci NOT NULL COMMENT '流程分类名称',
  `order_num` int DEFAULT '0' COMMENT '显示顺序',
  `del_flag` char(1) COLLATE utf8mb4_general_ci DEFAULT '0' COMMENT '删除标志（0代表存在 1代表删除）',
  `create_dept` bigint DEFAULT NULL COMMENT '创建部门',
  `create_by` bigint DEFAULT NULL COMMENT '创建者',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `update_by` bigint DEFAULT NULL COMMENT '更新者',
  `update_time` datetime DEFAULT NULL COMMENT '更新时间',
  PRIMARY KEY (`category_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='流程分类';
DROP TABLE IF EXISTS `flow_definition`;
CREATE TABLE IF NOT EXISTS `flow_definition` (
  `id` bigint NOT NULL COMMENT '主键id',
  `flow_code` varchar(40) COLLATE utf8mb4_general_ci NOT NULL COMMENT '流程编码',
  `flow_name` varchar(100) COLLATE utf8mb4_general_ci NOT NULL COMMENT '流程名称',
  `model_value` varchar(40) COLLATE utf8mb4_general_ci NOT NULL DEFAULT 'CLASSICS' COMMENT '设计器模型（CLASSICS经典模型 MIMIC仿钉钉模型）',
  `category` varchar(100) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '流程类别',
  `version` varchar(20) COLLATE utf8mb4_general_ci NOT NULL COMMENT '流程版本',
  `is_publish` tinyint(1) NOT NULL DEFAULT '0' COMMENT '是否发布（0未发布 1已发布 9失效）',
  `form_custom` char(1) COLLATE utf8mb4_general_ci DEFAULT 'N' COMMENT '审批表单是否自定义（Y是 N否）',
  `form_path` varchar(100) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '审批表单路径',
  `activity_status` tinyint(1) NOT NULL DEFAULT '1' COMMENT '流程激活状态（0挂起 1激活）',
  `listener_type` varchar(100) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '监听器类型',
  `listener_path` varchar(400) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '监听器路径',
  `ext` varchar(500) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '业务详情 存业务表对象json字符串',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `create_by` varchar(64) COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '创建人',
  `update_time` datetime DEFAULT NULL COMMENT '更新时间',
  `update_by` varchar(64) COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '更新人',
  `del_flag` char(1) COLLATE utf8mb4_general_ci DEFAULT '0' COMMENT '删除标志',
  `tenant_id` varchar(40) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '租户id',
  `def_config` TEXT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL COMMENT '流程定义配置（自动审批等引擎配置与自定义扩展属性json）',
  PRIMARY KEY (`id`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='流程定义表';
DROP TABLE IF EXISTS `flow_his_task`;
CREATE TABLE IF NOT EXISTS `flow_his_task` (
  `id` bigint NOT NULL COMMENT '主键id',
  `definition_id` bigint NOT NULL COMMENT '对应flow_definition表的id',
  `instance_id` bigint NOT NULL COMMENT '对应flow_instance表的id',
  `task_id` bigint NOT NULL COMMENT '对应flow_task表的id',
  `node_code` varchar(100) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '开始节点编码',
  `node_name` varchar(100) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '开始节点名称',
  `node_type` tinyint(1) DEFAULT NULL COMMENT '开始节点类型（0开始节点 1中间节点 2结束节点 3互斥网关 4并行网关）',
  `target_node_code` varchar(200) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '目标节点编码',
  `target_node_name` varchar(200) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '结束节点名称',
  `approver` varchar(40) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '审批人',
  `cooperate_type` tinyint(1) NOT NULL DEFAULT '0' COMMENT '协作方式(1审批 2转办 3委派 4会签 5票签 6加签 7减签)',
  `collaborator` varchar(500) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '协作人',
  `skip_type` varchar(10) COLLATE utf8mb4_general_ci NOT NULL COMMENT '流转类型（PASS通过 REJECT退回 NONE无动作）',
  `flow_status` varchar(20) COLLATE utf8mb4_general_ci NOT NULL COMMENT '流程状态（0待提交 1审批中 2审批通过 4终止 5作废 6撤销 8已完成 9已退回 10失效 11拿回）',
  `form_custom` char(1) COLLATE utf8mb4_general_ci DEFAULT 'N' COMMENT '审批表单是否自定义（Y是 N否）',
  `form_path` varchar(100) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '审批表单路径',
  `message` varchar(500) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '审批意见',
  `variable` text COLLATE utf8mb4_general_ci COMMENT '任务变量',
  `ext` text COLLATE utf8mb4_general_ci COMMENT '业务详情 存业务表对象json字符串',
  `create_time` datetime DEFAULT NULL COMMENT '任务开始时间',
  `update_time` datetime DEFAULT NULL COMMENT '审批完成时间',
  `del_flag` char(1) COLLATE utf8mb4_general_ci DEFAULT '0' COMMENT '删除标志',
  `tenant_id` varchar(40) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '租户id',
  `node_config` TEXT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL COMMENT '节点配置（服务任务、事件等引擎节点配置json）',
  PRIMARY KEY (`id`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='历史任务记录表';
DROP TABLE IF EXISTS `flow_instance`;
CREATE TABLE IF NOT EXISTS `flow_instance` (
  `id` bigint NOT NULL COMMENT '主键id',
  `definition_id` bigint NOT NULL COMMENT '对应flow_definition表的id',
  `business_id` varchar(40) COLLATE utf8mb4_general_ci NOT NULL COMMENT '业务id',
  `node_type` tinyint(1) NOT NULL COMMENT '节点类型（0开始节点 1中间节点 2结束节点 3互斥网关 4并行网关）',
  `node_code` varchar(40) COLLATE utf8mb4_general_ci NOT NULL COMMENT '流程节点编码',
  `node_name` varchar(100) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '流程节点名称',
  `variable` text COLLATE utf8mb4_general_ci COMMENT '任务变量',
  `flow_status` varchar(20) COLLATE utf8mb4_general_ci NOT NULL COMMENT '流程状态（0待提交 1审批中 2审批通过 4终止 5作废 6撤销 8已完成 9已退回 10失效 11拿回）',
  `activity_status` tinyint(1) NOT NULL DEFAULT '1' COMMENT '流程激活状态（0挂起 1激活）',
  `def_json` text COLLATE utf8mb4_general_ci COMMENT '流程定义json',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `create_by` varchar(64) COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '创建人',
  `update_time` datetime DEFAULT NULL COMMENT '更新时间',
  `update_by` varchar(64) COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '更新人',
  `ext` varchar(500) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '扩展字段，预留给业务系统使用',
  `del_flag` char(1) COLLATE utf8mb4_general_ci DEFAULT '0' COMMENT '删除标志',
  `tenant_id` varchar(40) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '租户id',
  PRIMARY KEY (`id`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='流程实例表';
DROP TABLE IF EXISTS `flow_instance_biz_ext`;
CREATE TABLE IF NOT EXISTS `flow_instance_biz_ext` (
  `id` bigint NOT NULL COMMENT '主键id',
  `create_dept` bigint DEFAULT NULL COMMENT '创建部门',
  `create_by` bigint DEFAULT NULL COMMENT '创建者',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `update_by` bigint DEFAULT NULL COMMENT '更新者',
  `update_time` datetime DEFAULT NULL COMMENT '更新时间',
  `business_code` varchar(255) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '业务编码',
  `business_title` varchar(1000) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '业务标题',
  `del_flag` char(1) COLLATE utf8mb4_general_ci DEFAULT '0' COMMENT '删除标志（0代表存在 1代表删除）',
  `instance_id` bigint DEFAULT NULL COMMENT '流程实例Id',
  `business_id` varchar(255) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '业务Id',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='流程实例业务扩展表';
DROP TABLE IF EXISTS `flow_node`;
CREATE TABLE IF NOT EXISTS `flow_node` (
  `id` bigint NOT NULL COMMENT '主键id',
  `node_type` tinyint(1) NOT NULL COMMENT '节点类型（0开始节点 1中间节点 2结束节点 3互斥网关 4并行网关）',
  `definition_id` bigint NOT NULL COMMENT '流程定义id',
  `node_code` varchar(100) COLLATE utf8mb4_general_ci NOT NULL COMMENT '流程节点编码',
  `node_name` varchar(100) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '流程节点名称',
  `permission_flag` varchar(200) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '权限标识（权限类型:权限标识，可以多个，用@@隔开)',
  `node_ratio` varchar(200) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '流程签署比例值',
  `coordinate` varchar(100) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '坐标',
  `any_node_skip` varchar(100) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '任意结点跳转',
  `listener_type` varchar(100) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '监听器类型',
  `listener_path` varchar(400) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '监听器路径',
  `form_custom` char(1) COLLATE utf8mb4_general_ci DEFAULT 'N' COMMENT '审批表单是否自定义（Y是 N否）',
  `form_path` varchar(100) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '审批表单路径',
  `version` varchar(20) COLLATE utf8mb4_general_ci NOT NULL COMMENT '版本',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `create_by` varchar(64) COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '创建人',
  `update_time` datetime DEFAULT NULL COMMENT '更新时间',
  `update_by` varchar(64) COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '更新人',
  `ext` text COLLATE utf8mb4_general_ci COMMENT '节点扩展属性',
  `del_flag` char(1) COLLATE utf8mb4_general_ci DEFAULT '0' COMMENT '删除标志',
  `tenant_id` varchar(40) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '租户id',
  `attached_to_node_code` VARCHAR(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL COMMENT '边界事件附着的任务节点编码',
  `event_name` VARCHAR(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL COMMENT '事件名称（消息事件使用）',
  `event_type` VARCHAR(40) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL COMMENT '事件类型',
  `interrupting` TINYINT(1) NULL COMMENT '边界事件是否中断型',
  `node_config` TEXT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL COMMENT '节点配置（服务任务、事件等引擎节点配置json）',
  PRIMARY KEY (`id`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='流程节点表';
DROP TABLE IF EXISTS `flow_skip`;
CREATE TABLE IF NOT EXISTS `flow_skip` (
  `id` bigint NOT NULL COMMENT '主键id',
  `definition_id` bigint NOT NULL COMMENT '流程定义id',
  `now_node_code` varchar(100) COLLATE utf8mb4_general_ci NOT NULL COMMENT '当前流程节点的编码',
  `now_node_type` tinyint(1) DEFAULT NULL COMMENT '当前节点类型（0开始节点 1中间节点 2结束节点 3互斥网关 4并行网关）',
  `next_node_code` varchar(100) COLLATE utf8mb4_general_ci NOT NULL COMMENT '下一个流程节点的编码',
  `next_node_type` tinyint(1) DEFAULT NULL COMMENT '下一个节点类型（0开始节点 1中间节点 2结束节点 3互斥网关 4并行网关）',
  `skip_name` varchar(100) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '跳转名称',
  `skip_type` varchar(40) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '跳转类型（PASS审批通过 REJECT退回）',
  `skip_condition` varchar(200) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '跳转条件',
  `coordinate` varchar(100) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '坐标',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `create_by` varchar(64) COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '创建人',
  `update_time` datetime DEFAULT NULL COMMENT '更新时间',
  `update_by` varchar(64) COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '更新人',
  `del_flag` char(1) COLLATE utf8mb4_general_ci DEFAULT '0' COMMENT '删除标志',
  `tenant_id` varchar(40) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '租户id',
  PRIMARY KEY (`id`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='节点跳转关联表';
DROP TABLE IF EXISTS `flow_spel`;
CREATE TABLE IF NOT EXISTS `flow_spel` (
  `id` bigint NOT NULL COMMENT '主键id',
  `component_name` varchar(255) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '组件名称',
  `method_name` varchar(255) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '方法名',
  `method_params` varchar(255) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '参数',
  `view_spel` varchar(255) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '预览spel表达式',
  `remark` varchar(255) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '备注',
  `status` char(1) COLLATE utf8mb4_general_ci DEFAULT '0' COMMENT '状态（0正常 1停用）',
  `del_flag` char(1) COLLATE utf8mb4_general_ci DEFAULT '0' COMMENT '删除标志',
  `create_dept` bigint DEFAULT NULL COMMENT '创建部门',
  `create_by` bigint DEFAULT NULL COMMENT '创建者',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `update_by` bigint DEFAULT NULL COMMENT '更新者',
  `update_time` datetime DEFAULT NULL COMMENT '更新时间',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='流程spel表达式定义表';
DROP TABLE IF EXISTS `flow_task`;
CREATE TABLE IF NOT EXISTS `flow_task` (
  `id` bigint NOT NULL COMMENT '主键id',
  `definition_id` bigint NOT NULL COMMENT '对应flow_definition表的id',
  `instance_id` bigint NOT NULL COMMENT '对应flow_instance表的id',
  `node_code` varchar(100) COLLATE utf8mb4_general_ci NOT NULL COMMENT '节点编码',
  `node_name` varchar(100) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '节点名称',
  `node_type` tinyint(1) NOT NULL COMMENT '节点类型（0开始节点 1中间节点 2结束节点 3互斥网关 4并行网关）',
  `flow_status` varchar(20) COLLATE utf8mb4_general_ci NOT NULL COMMENT '流程状态（0待提交 1审批中 2审批通过 4终止 5作废 6撤销 8已完成 9已退回 10失效 11拿回）',
  `form_custom` char(1) COLLATE utf8mb4_general_ci DEFAULT 'N' COMMENT '审批表单是否自定义（Y是 N否）',
  `form_path` varchar(100) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '审批表单路径',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `create_by` varchar(64) COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '创建人',
  `update_time` datetime DEFAULT NULL COMMENT '更新时间',
  `update_by` varchar(64) COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '更新人',
  `del_flag` char(1) COLLATE utf8mb4_general_ci DEFAULT '0' COMMENT '删除标志',
  `tenant_id` varchar(40) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '租户id',
  PRIMARY KEY (`id`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='待办任务表';
DROP TABLE IF EXISTS `flow_user`;
CREATE TABLE IF NOT EXISTS `flow_user` (
  `id` bigint NOT NULL COMMENT '主键id',
  `type` char(1) COLLATE utf8mb4_general_ci NOT NULL COMMENT '人员类型（1待办任务的审批人权限 2待办任务的转办人权限 3待办任务的委托人权限）',
  `processed_by` varchar(80) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '权限人',
  `associated` bigint NOT NULL COMMENT '任务表id',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `create_by` varchar(80) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '创建人',
  `update_time` datetime DEFAULT NULL COMMENT '更新时间',
  `update_by` varchar(64) COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '创建人',
  `del_flag` char(1) COLLATE utf8mb4_general_ci DEFAULT '0' COMMENT '删除标志',
  `tenant_id` varchar(40) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '租户id',
  PRIMARY KEY (`id`) USING BTREE,
  KEY `user_processed_type` (`processed_by`,`type`),
  KEY `user_associated` (`associated`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='流程用户表';
DROP TABLE IF EXISTS `gen_table`;
CREATE TABLE IF NOT EXISTS `gen_table` (
  `table_id` bigint NOT NULL COMMENT '编号',
  `data_name` varchar(200) COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '数据源名称',
  `table_name` varchar(200) COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '表名称',
  `table_comment` varchar(500) COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '表描述',
  `class_name` varchar(100) COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '实体类名称',
  `tpl_category` varchar(200) COLLATE utf8mb4_general_ci DEFAULT 'crud' COMMENT '使用的模板（crud单表操作 tree树表操作）',
  `frontend_type` varchar(50) COLLATE utf8mb4_general_ci DEFAULT 'vue' COMMENT '前端模板类型，对应 vm 下的模板目录',
  `package_name` varchar(100) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '生成包路径',
  `module_name` varchar(30) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '生成模块名',
  `business_name` varchar(30) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '生成业务名',
  `function_name` varchar(50) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '生成功能名',
  `function_author` varchar(50) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '生成功能作者',
  `gen_type` char(1) COLLATE utf8mb4_general_ci DEFAULT '0' COMMENT '生成代码方式（0zip压缩包 1自定义路径）',
  `gen_path` varchar(200) COLLATE utf8mb4_general_ci DEFAULT '/' COMMENT '生成路径（不填默认项目路径）',
  `options` varchar(1000) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '其它生成选项',
  `create_dept` bigint DEFAULT NULL COMMENT '创建部门',
  `create_by` bigint DEFAULT NULL COMMENT '创建者',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `update_by` bigint DEFAULT NULL COMMENT '更新者',
  `update_time` datetime DEFAULT NULL COMMENT '更新时间',
  `remark` varchar(500) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '备注',
  PRIMARY KEY (`table_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='代码生成业务表';
DROP TABLE IF EXISTS `gen_table_column`;
CREATE TABLE IF NOT EXISTS `gen_table_column` (
  `column_id` bigint NOT NULL COMMENT '编号',
  `table_id` bigint DEFAULT NULL COMMENT '归属表编号',
  `column_name` varchar(200) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '列名称',
  `column_comment` varchar(500) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '列描述',
  `column_type` varchar(100) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '列类型',
  `java_type` varchar(500) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT 'JAVA类型',
  `java_field` varchar(200) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT 'JAVA字段名',
  `is_pk` char(1) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '是否主键（1是）',
  `is_increment` char(1) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '是否自增（1是）',
  `is_required` char(1) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '是否必填（1是）',
  `is_insert` char(1) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '是否为插入字段（1是）',
  `is_edit` char(1) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '是否编辑字段（1是）',
  `is_list` char(1) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '是否列表字段（1是）',
  `is_query` char(1) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '是否查询字段（1是）',
  `query_type` varchar(200) COLLATE utf8mb4_general_ci DEFAULT 'EQ' COMMENT '查询方式（等于、不等于、大于、小于、范围）',
  `html_type` varchar(200) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '显示类型（文本框、文本域、下拉框、复选框、单选框、日期控件）',
  `dict_type` varchar(200) COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '字典类型',
  `sort` int DEFAULT NULL COMMENT '排序',
  `create_dept` bigint DEFAULT NULL COMMENT '创建部门',
  `create_by` bigint DEFAULT NULL COMMENT '创建者',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `update_by` bigint DEFAULT NULL COMMENT '更新者',
  `update_time` datetime DEFAULT NULL COMMENT '更新时间',
  PRIMARY KEY (`column_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='代码生成业务表字段';
DROP TABLE IF EXISTS `sys_client`;
CREATE TABLE IF NOT EXISTS `sys_client` (
  `id` bigint NOT NULL COMMENT 'id',
  `client_id` varchar(64) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '客户端id',
  `client_key` varchar(32) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '客户端key',
  `client_secret` varchar(255) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '客户端秘钥',
  `grant_type` varchar(255) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '授权类型',
  `device_type` varchar(32) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '设备类型',
  `access_path` varchar(2000) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '允许访问路径',
  `ip_whitelist` varchar(1000) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT 'IP白名单',
  `active_timeout` int DEFAULT '1800' COMMENT 'token活跃超时时间',
  `timeout` int DEFAULT '604800' COMMENT 'token固定超时',
  `status` char(1) COLLATE utf8mb4_general_ci DEFAULT '0' COMMENT '状态（0正常 1停用）',
  `del_flag` char(1) COLLATE utf8mb4_general_ci DEFAULT '0' COMMENT '删除标志（0代表存在 1代表删除）',
  `create_dept` bigint DEFAULT NULL COMMENT '创建部门',
  `create_by` bigint DEFAULT NULL COMMENT '创建者',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `update_by` bigint DEFAULT NULL COMMENT '更新者',
  `update_time` datetime DEFAULT NULL COMMENT '更新时间',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='系统授权表';
DROP TABLE IF EXISTS `sys_config`;
CREATE TABLE IF NOT EXISTS `sys_config` (
  `config_id` bigint NOT NULL COMMENT '参数主键',
  `config_name` varchar(100) COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '参数名称',
  `config_key` varchar(100) COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '参数键名',
  `config_value` varchar(500) COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '参数键值',
  `config_type` char(1) COLLATE utf8mb4_general_ci DEFAULT 'N' COMMENT '系统内置（Y是 N否）',
  `create_dept` bigint DEFAULT NULL COMMENT '创建部门',
  `create_by` bigint DEFAULT NULL COMMENT '创建者',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `update_by` bigint DEFAULT NULL COMMENT '更新者',
  `update_time` datetime DEFAULT NULL COMMENT '更新时间',
  `remark` varchar(500) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '备注',
  PRIMARY KEY (`config_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='参数配置表';
DROP TABLE IF EXISTS `sys_dept`;
CREATE TABLE IF NOT EXISTS `sys_dept` (
  `dept_id` bigint NOT NULL COMMENT '部门id',
  `parent_id` bigint DEFAULT '0' COMMENT '父部门id',
  `ancestors` varchar(500) COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '祖级列表',
  `dept_name` varchar(30) COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '部门名称',
  `dept_category` varchar(100) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '部门类别编码',
  `order_num` int DEFAULT '0' COMMENT '显示顺序',
  `leader` bigint DEFAULT NULL COMMENT '负责人',
  `phone` varchar(11) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '联系电话',
  `email` varchar(50) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '邮箱',
  `status` char(1) COLLATE utf8mb4_general_ci DEFAULT '0' COMMENT '部门状态（0正常 1停用）',
  `del_flag` char(1) COLLATE utf8mb4_general_ci DEFAULT '0' COMMENT '删除标志（0代表存在 1代表删除）',
  `create_dept` bigint DEFAULT NULL COMMENT '创建部门',
  `create_by` bigint DEFAULT NULL COMMENT '创建者',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `update_by` bigint DEFAULT NULL COMMENT '更新者',
  `update_time` datetime DEFAULT NULL COMMENT '更新时间',
  PRIMARY KEY (`dept_id`),
  KEY `idx_sys_dept_parent_id` (`parent_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='部门表';
DROP TABLE IF EXISTS `sys_dict_data`;
CREATE TABLE IF NOT EXISTS `sys_dict_data` (
  `dict_code` bigint NOT NULL COMMENT '字典编码',
  `dict_sort` int DEFAULT '0' COMMENT '字典排序',
  `dict_label` varchar(100) COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '字典标签',
  `dict_value` varchar(100) COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '字典键值',
  `dict_type` varchar(100) COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '字典类型',
  `css_class` varchar(100) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '样式属性（其他样式扩展）',
  `list_class` varchar(100) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '表格回显样式',
  `is_default` char(1) COLLATE utf8mb4_general_ci DEFAULT 'N' COMMENT '是否默认（Y是 N否）',
  `create_dept` bigint DEFAULT NULL COMMENT '创建部门',
  `create_by` bigint DEFAULT NULL COMMENT '创建者',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `update_by` bigint DEFAULT NULL COMMENT '更新者',
  `update_time` datetime DEFAULT NULL COMMENT '更新时间',
  `remark` varchar(500) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '备注',
  PRIMARY KEY (`dict_code`),
  KEY `idx_sys_dict_data_type` (`dict_type`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='字典数据表';
DROP TABLE IF EXISTS `sys_dict_type`;
CREATE TABLE IF NOT EXISTS `sys_dict_type` (
  `dict_id` bigint NOT NULL COMMENT '字典主键',
  `dict_name` varchar(100) COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '字典名称',
  `dict_type` varchar(100) COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '字典类型',
  `create_dept` bigint DEFAULT NULL COMMENT '创建部门',
  `create_by` bigint DEFAULT NULL COMMENT '创建者',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `update_by` bigint DEFAULT NULL COMMENT '更新者',
  `update_time` datetime DEFAULT NULL COMMENT '更新时间',
  `remark` varchar(500) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '备注',
  PRIMARY KEY (`dict_id`),
  UNIQUE KEY `dict_type` (`dict_type`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='字典类型表';
DROP TABLE IF EXISTS `sys_login_info`;
CREATE TABLE IF NOT EXISTS `sys_login_info` (
  `info_id` bigint NOT NULL COMMENT '访问ID',
  `user_name` varchar(50) COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '用户账号',
  `client_key` varchar(32) COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '客户端',
  `device_type` varchar(32) COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '设备类型',
  `ipaddr` varchar(128) COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '登录IP地址',
  `login_location` varchar(255) COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '登录地点',
  `browser` varchar(50) COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '浏览器类型',
  `os` varchar(50) COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '操作系统',
  `status` char(1) COLLATE utf8mb4_general_ci DEFAULT '0' COMMENT '登录状态（0正常 1异常）',
  `msg` varchar(255) COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '提示消息',
  `login_time` datetime DEFAULT NULL COMMENT '访问时间',
  PRIMARY KEY (`info_id`),
  KEY `idx_sys_login_info_s` (`status`),
  KEY `idx_sys_login_info_lt` (`login_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='系统访问记录';
DROP TABLE IF EXISTS `sys_menu`;
CREATE TABLE IF NOT EXISTS `sys_menu` (
  `menu_id` bigint NOT NULL COMMENT '菜单ID',
  `menu_name` varchar(50) COLLATE utf8mb4_general_ci NOT NULL COMMENT '菜单名称',
  `parent_id` bigint DEFAULT '0' COMMENT '父菜单ID',
  `order_num` int DEFAULT '0' COMMENT '显示顺序',
  `path` varchar(200) COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '路由地址',
  `component` varchar(255) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '组件路径',
  `query_param` varchar(255) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '路由参数',
  `is_frame` char(1) COLLATE utf8mb4_general_ci DEFAULT 'N' COMMENT '是否为外链（Y是 N否）',
  `is_cache` char(1) COLLATE utf8mb4_general_ci DEFAULT 'Y' COMMENT '是否缓存（Y缓存 N不缓存）',
  `menu_type` char(1) COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '菜单类型（M目录 C菜单 F按钮）',
  `visible` char(1) COLLATE utf8mb4_general_ci DEFAULT '0' COMMENT '显示状态（0显示 1隐藏）',
  `status` char(1) COLLATE utf8mb4_general_ci DEFAULT '0' COMMENT '菜单状态（0正常 1停用）',
  `perms` varchar(100) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '权限标识',
  `icon` varchar(100) COLLATE utf8mb4_general_ci DEFAULT '#' COMMENT '菜单图标',
  `active_menu` varchar(255) COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '激活菜单路径',
  `ext` varchar(2000) COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '扩展字段',
  `create_dept` bigint DEFAULT NULL COMMENT '创建部门',
  `create_by` bigint DEFAULT NULL COMMENT '创建者',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `update_by` bigint DEFAULT NULL COMMENT '更新者',
  `update_time` datetime DEFAULT NULL COMMENT '更新时间',
  `remark` varchar(500) COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '备注',
  PRIMARY KEY (`menu_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='菜单权限表';
DROP TABLE IF EXISTS `sys_message`;
CREATE TABLE IF NOT EXISTS `sys_message` (
  `message_id` bigint NOT NULL COMMENT '消息ID',
  `category` varchar(20) COLLATE utf8mb4_general_ci NOT NULL COMMENT '消息分组(system/notice/workflow)',
  `type` varchar(20) COLLATE utf8mb4_general_ci NOT NULL COMMENT '消息类型',
  `source` varchar(20) COLLATE utf8mb4_general_ci NOT NULL COMMENT '消息来源',
  `title` varchar(100) COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '标题',
  `message` varchar(500) COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '摘要消息',
  `content` longtext COLLATE utf8mb4_general_ci COMMENT '详细内容',
  `data_json` longtext COLLATE utf8mb4_general_ci COMMENT '扩展数据JSON',
  `path` varchar(500) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '前端跳转路径',
  `send_user_ids` varchar(2000) COLLATE utf8mb4_general_ci NOT NULL DEFAULT '0' COMMENT '目标用户ID串，0表示全局',
  `create_dept` bigint DEFAULT NULL COMMENT '创建部门',
  `create_by` bigint DEFAULT NULL COMMENT '创建者',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `update_by` bigint DEFAULT NULL COMMENT '更新者',
  `update_time` datetime DEFAULT NULL COMMENT '更新时间',
  PRIMARY KEY (`message_id`),
  KEY `idx_sys_message_category_time` (`category`,`create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='消息记录表';
DROP TABLE IF EXISTS `sys_notice`;
CREATE TABLE IF NOT EXISTS `sys_notice` (
  `notice_id` bigint NOT NULL COMMENT '公告ID',
  `notice_title` varchar(50) COLLATE utf8mb4_general_ci NOT NULL COMMENT '公告标题',
  `notice_type` char(1) COLLATE utf8mb4_general_ci NOT NULL COMMENT '公告类型（1通知 2公告）',
  `notice_content` longblob COMMENT '公告内容',
  `status` char(1) COLLATE utf8mb4_general_ci DEFAULT '0' COMMENT '公告状态（0正常 1关闭）',
  `create_dept` bigint DEFAULT NULL COMMENT '创建部门',
  `create_by` bigint DEFAULT NULL COMMENT '创建者',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `update_by` bigint DEFAULT NULL COMMENT '更新者',
  `update_time` datetime DEFAULT NULL COMMENT '更新时间',
  `remark` varchar(255) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '备注',
  PRIMARY KEY (`notice_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='通知公告表';
DROP TABLE IF EXISTS `sys_oper_log`;
CREATE TABLE IF NOT EXISTS `sys_oper_log` (
  `oper_id` bigint NOT NULL COMMENT '日志主键',
  `title` varchar(50) COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '模块标题',
  `business_type` int DEFAULT '0' COMMENT '业务类型（0其它 1新增 2修改 3删除）',
  `method` varchar(100) COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '方法名称',
  `request_method` varchar(10) COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '请求方式',
  `operator_type` int DEFAULT '0' COMMENT '操作类别（0其它 1后台用户 2手机端用户）',
  `oper_name` varchar(50) COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '操作人员',
  `user_id` bigint DEFAULT NULL COMMENT '操作用户ID',
  `dept_id` bigint DEFAULT NULL COMMENT '操作部门ID',
  `dept_name` varchar(50) COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '部门名称',
  `client_key` varchar(32) COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '客户端',
  `device_type` varchar(32) COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '设备类型',
  `browser` varchar(50) COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '浏览器类型',
  `os` varchar(50) COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '操作系统',
  `oper_url` varchar(255) COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '请求URL',
  `oper_ip` varchar(128) COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '主机地址',
  `oper_location` varchar(255) COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '操作地点',
  `oper_param` varchar(4000) COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '请求参数',
  `json_result` varchar(4000) COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '返回参数',
  `status` int DEFAULT '0' COMMENT '操作状态（0正常 1异常）',
  `error_msg` varchar(4000) COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '错误消息',
  `oper_time` datetime DEFAULT NULL COMMENT '操作时间',
  `cost_time` bigint DEFAULT '0' COMMENT '消耗时间',
  PRIMARY KEY (`oper_id`),
  KEY `idx_sys_oper_log_bt` (`business_type`),
  KEY `idx_sys_oper_log_uid` (`user_id`),
  KEY `idx_sys_oper_log_s` (`status`),
  KEY `idx_sys_oper_log_ot` (`oper_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='操作日志记录';
DROP TABLE IF EXISTS `sys_oss`;
CREATE TABLE IF NOT EXISTS `sys_oss` (
  `oss_id` bigint NOT NULL COMMENT '对象存储主键',
  `file_name` varchar(255) COLLATE utf8mb4_general_ci NOT NULL DEFAULT '' COMMENT '文件名',
  `original_name` varchar(255) COLLATE utf8mb4_general_ci NOT NULL DEFAULT '' COMMENT '原名',
  `file_suffix` varchar(10) COLLATE utf8mb4_general_ci NOT NULL DEFAULT '' COMMENT '文件后缀名',
  `url` varchar(500) COLLATE utf8mb4_general_ci NOT NULL COMMENT 'URL地址',
  `ext1` text COLLATE utf8mb4_general_ci COMMENT '扩展字段',
  `create_dept` bigint DEFAULT NULL COMMENT '创建部门',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `create_by` bigint DEFAULT NULL COMMENT '上传人',
  `update_time` datetime DEFAULT NULL COMMENT '更新时间',
  `update_by` bigint DEFAULT NULL COMMENT '更新人',
  `service` varchar(20) COLLATE utf8mb4_general_ci NOT NULL DEFAULT 'minio' COMMENT '服务商',
  PRIMARY KEY (`oss_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='OSS对象存储表';
DROP TABLE IF EXISTS `sys_oss_config`;
CREATE TABLE IF NOT EXISTS `sys_oss_config` (
  `oss_config_id` bigint NOT NULL COMMENT '主键',
  `config_key` varchar(20) COLLATE utf8mb4_general_ci NOT NULL DEFAULT '' COMMENT '配置key',
  `access_key` varchar(255) COLLATE utf8mb4_general_ci DEFAULT '' COMMENT 'accessKey',
  `secret_key` varchar(255) COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '秘钥',
  `bucket_name` varchar(255) COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '桶名称',
  `prefix` varchar(255) COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '前缀',
  `endpoint` varchar(255) COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '访问站点',
  `domain_url` varchar(255) COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '自定义域名',
  `is_https` char(1) COLLATE utf8mb4_general_ci DEFAULT 'N' COMMENT '是否https（Y=是,N=否）',
  `region` varchar(255) COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '域',
  `access_policy` char(1) COLLATE utf8mb4_general_ci NOT NULL DEFAULT '1' COMMENT '桶权限类型(0=private 1=public 2=custom)',
  `status` char(1) COLLATE utf8mb4_general_ci DEFAULT 'N' COMMENT '是否默认（Y=是,N=否）',
  `ext1` varchar(255) COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '扩展字段',
  `create_dept` bigint DEFAULT NULL COMMENT '创建部门',
  `create_by` bigint DEFAULT NULL COMMENT '创建者',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `update_by` bigint DEFAULT NULL COMMENT '更新者',
  `update_time` datetime DEFAULT NULL COMMENT '更新时间',
  `remark` varchar(500) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '备注',
  PRIMARY KEY (`oss_config_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='对象存储配置表';
DROP TABLE IF EXISTS `sys_post`;
CREATE TABLE IF NOT EXISTS `sys_post` (
  `post_id` bigint NOT NULL COMMENT '岗位ID',
  `dept_id` bigint NOT NULL COMMENT '部门id',
  `post_code` varchar(64) COLLATE utf8mb4_general_ci NOT NULL COMMENT '岗位编码',
  `post_category` varchar(100) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '岗位类别编码',
  `post_name` varchar(50) COLLATE utf8mb4_general_ci NOT NULL COMMENT '岗位名称',
  `post_sort` int NOT NULL COMMENT '显示顺序',
  `status` char(1) COLLATE utf8mb4_general_ci NOT NULL COMMENT '状态（0正常 1停用）',
  `del_flag` char(1) COLLATE utf8mb4_general_ci DEFAULT '0' COMMENT '删除标志（0代表存在 1代表删除）',
  `create_dept` bigint DEFAULT NULL COMMENT '创建部门',
  `create_by` bigint DEFAULT NULL COMMENT '创建者',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `update_by` bigint DEFAULT NULL COMMENT '更新者',
  `update_time` datetime DEFAULT NULL COMMENT '更新时间',
  `remark` varchar(500) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '备注',
  PRIMARY KEY (`post_id`),
  KEY `idx_sys_post_dept_id` (`dept_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='岗位信息表';
DROP TABLE IF EXISTS `sys_role`;
CREATE TABLE IF NOT EXISTS `sys_role` (
  `role_id` bigint NOT NULL COMMENT '角色ID',
  `role_name` varchar(30) COLLATE utf8mb4_general_ci NOT NULL COMMENT '角色名称',
  `role_key` varchar(100) COLLATE utf8mb4_general_ci NOT NULL COMMENT '角色权限字符串',
  `role_sort` int NOT NULL COMMENT '显示顺序',
  `data_scope` char(1) COLLATE utf8mb4_general_ci DEFAULT '1' COMMENT '数据范围（1：全部数据权限 2：自定数据权限 3：本部门数据权限 4：本部门及以下数据权限 5：仅本人数据权限 6：部门及以下或本人数据权限）',
  `menu_check_strictly` tinyint(1) DEFAULT '1' COMMENT '菜单树选择项是否关联显示',
  `dept_check_strictly` tinyint(1) DEFAULT '1' COMMENT '部门树选择项是否关联显示',
  `status` char(1) COLLATE utf8mb4_general_ci NOT NULL COMMENT '角色状态（0正常 1停用）',
  `del_flag` char(1) COLLATE utf8mb4_general_ci DEFAULT '0' COMMENT '删除标志（0代表存在 1代表删除）',
  `create_dept` bigint DEFAULT NULL COMMENT '创建部门',
  `create_by` bigint DEFAULT NULL COMMENT '创建者',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `update_by` bigint DEFAULT NULL COMMENT '更新者',
  `update_time` datetime DEFAULT NULL COMMENT '更新时间',
  `remark` varchar(500) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '备注',
  PRIMARY KEY (`role_id`),
  KEY `idx_sys_role_create_dept` (`create_dept`),
  KEY `idx_sys_role_create_by` (`create_by`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='角色信息表';
DROP TABLE IF EXISTS `sys_role_dept`;
CREATE TABLE IF NOT EXISTS `sys_role_dept` (
  `role_id` bigint NOT NULL COMMENT '角色ID',
  `dept_id` bigint NOT NULL COMMENT '部门ID',
  PRIMARY KEY (`role_id`,`dept_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='角色和部门关联表';
DROP TABLE IF EXISTS `sys_role_menu`;
CREATE TABLE IF NOT EXISTS `sys_role_menu` (
  `role_id` bigint NOT NULL COMMENT '角色ID',
  `menu_id` bigint NOT NULL COMMENT '菜单ID',
  PRIMARY KEY (`role_id`,`menu_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='角色和菜单关联表';
DROP TABLE IF EXISTS `sys_social`;
CREATE TABLE IF NOT EXISTS `sys_social` (
  `id` bigint NOT NULL COMMENT '主键',
  `user_id` bigint NOT NULL COMMENT '用户ID',
  `auth_id` varchar(255) COLLATE utf8mb4_general_ci NOT NULL COMMENT '平台+平台唯一id',
  `source` varchar(255) COLLATE utf8mb4_general_ci NOT NULL COMMENT '用户来源',
  `open_id` varchar(255) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '平台编号唯一id',
  `user_name` varchar(30) COLLATE utf8mb4_general_ci NOT NULL COMMENT '登录账号',
  `nick_name` varchar(30) COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '用户昵称',
  `email` varchar(255) COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '用户邮箱',
  `avatar` varchar(500) COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '头像地址',
  `access_token` varchar(2000) COLLATE utf8mb4_general_ci NOT NULL COMMENT '用户的授权令牌',
  `expire_in` int DEFAULT NULL COMMENT '用户的授权令牌的有效期，部分平台可能没有',
  `refresh_token` varchar(2000) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '刷新令牌，部分平台可能没有',
  `access_code` varchar(255) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '平台的授权信息，部分平台可能没有',
  `union_id` varchar(255) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '用户的 unionid',
  `scope` varchar(255) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '授予的权限，部分平台可能没有',
  `token_type` varchar(255) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '个别平台的授权信息，部分平台可能没有',
  `id_token` varchar(2000) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT 'id token，部分平台可能没有',
  `mac_algorithm` varchar(255) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '小米平台用户的附带属性，部分平台可能没有',
  `mac_key` varchar(255) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '小米平台用户的附带属性，部分平台可能没有',
  `code` varchar(255) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '用户的授权code，部分平台可能没有',
  `oauth_token` varchar(255) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT 'Twitter平台用户的附带属性，部分平台可能没有',
  `oauth_token_secret` varchar(255) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT 'Twitter平台用户的附带属性，部分平台可能没有',
  `create_dept` bigint DEFAULT NULL COMMENT '创建部门',
  `create_by` bigint DEFAULT NULL COMMENT '创建者',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `update_by` bigint DEFAULT NULL COMMENT '更新者',
  `update_time` datetime DEFAULT NULL COMMENT '更新时间',
  `del_flag` char(1) COLLATE utf8mb4_general_ci DEFAULT '0' COMMENT '删除标志（0代表存在 1代表删除）',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='社会化关系表';
DROP TABLE IF EXISTS `sys_user`;
CREATE TABLE IF NOT EXISTS `sys_user` (
  `user_id` bigint NOT NULL COMMENT '用户ID',
  `dept_id` bigint DEFAULT NULL COMMENT '部门ID',
  `user_name` varchar(30) COLLATE utf8mb4_general_ci NOT NULL COMMENT '用户账号',
  `nick_name` varchar(30) COLLATE utf8mb4_general_ci NOT NULL COMMENT '用户昵称',
  `user_type` varchar(10) COLLATE utf8mb4_general_ci DEFAULT 'sys_user' COMMENT '用户类型（sys_user系统用户）',
  `email` varchar(50) COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '用户邮箱',
  `phone_number` varchar(11) COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '手机号码',
  `gender` char(1) COLLATE utf8mb4_general_ci DEFAULT '0' COMMENT '用户性别（0男 1女 2未知）',
  `avatar` bigint DEFAULT NULL COMMENT '头像地址',
  `password` varchar(100) COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '密码',
  `status` char(1) COLLATE utf8mb4_general_ci DEFAULT '0' COMMENT '账号状态（0正常 1停用）',
  `del_flag` char(1) COLLATE utf8mb4_general_ci DEFAULT '0' COMMENT '删除标志（0代表存在 1代表删除）',
  `login_ip` varchar(128) COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '最后登录IP',
  `login_date` datetime DEFAULT NULL COMMENT '最后登录时间',
  `create_dept` bigint DEFAULT NULL COMMENT '创建部门',
  `create_by` bigint DEFAULT NULL COMMENT '创建者',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `update_by` bigint DEFAULT NULL COMMENT '更新者',
  `update_time` datetime DEFAULT NULL COMMENT '更新时间',
  `remark` varchar(500) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '备注',
  PRIMARY KEY (`user_id`),
  KEY `idx_sys_user_dept_id` (`dept_id`),
  KEY `idx_sys_user_create_by` (`create_by`),
  KEY `idx_sys_user_user_name` (`user_name`),
  KEY `idx_sys_user_phone` (`phone_number`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='用户信息表';
DROP TABLE IF EXISTS `sys_user_post`;
CREATE TABLE IF NOT EXISTS `sys_user_post` (
  `user_id` bigint NOT NULL COMMENT '用户ID',
  `post_id` bigint NOT NULL COMMENT '岗位ID',
  PRIMARY KEY (`user_id`,`post_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='用户与岗位关联表';
DROP TABLE IF EXISTS `sys_user_role`;
CREATE TABLE IF NOT EXISTS `sys_user_role` (
  `user_id` bigint NOT NULL COMMENT '用户ID',
  `role_id` bigint NOT NULL COMMENT '角色ID',
  PRIMARY KEY (`user_id`,`role_id`),
  KEY `idx_sys_user_role_rid` (`role_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='用户和角色关联表';
DROP TABLE IF EXISTS `test_demo`;
CREATE TABLE IF NOT EXISTS `test_demo` (
  `id` bigint NOT NULL COMMENT '主键',
  `dept_id` bigint DEFAULT NULL COMMENT '部门id',
  `user_id` bigint DEFAULT NULL COMMENT '用户id',
  `order_num` int DEFAULT '0' COMMENT '排序号',
  `test_key` varchar(255) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT 'key键',
  `value` varchar(255) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '值',
  `version` int DEFAULT '0' COMMENT '版本',
  `create_dept` bigint DEFAULT NULL COMMENT '创建部门',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `create_by` bigint DEFAULT NULL COMMENT '创建人',
  `update_time` datetime DEFAULT NULL COMMENT '更新时间',
  `update_by` bigint DEFAULT NULL COMMENT '更新人',
  `del_flag` int DEFAULT '0' COMMENT '删除标志',
  PRIMARY KEY (`id`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='测试单表';
DROP TABLE IF EXISTS `test_leave`;
CREATE TABLE IF NOT EXISTS `test_leave` (
  `id` bigint NOT NULL COMMENT 'id',
  `apply_code` varchar(50) COLLATE utf8mb4_general_ci NOT NULL COMMENT '申请编号',
  `leave_type` varchar(255) COLLATE utf8mb4_general_ci NOT NULL COMMENT '请假类型',
  `start_date` datetime NOT NULL COMMENT '开始时间',
  `end_date` datetime NOT NULL COMMENT '结束时间',
  `leave_days` int NOT NULL COMMENT '请假天数',
  `remark` varchar(255) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '请假原因',
  `status` varchar(255) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '状态',
  `create_dept` bigint DEFAULT NULL COMMENT '创建部门',
  `create_by` bigint DEFAULT NULL COMMENT '创建者',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `update_by` bigint DEFAULT NULL COMMENT '更新者',
  `update_time` datetime DEFAULT NULL COMMENT '更新时间',
  PRIMARY KEY (`id`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='请假申请表';
DROP TABLE IF EXISTS `test_tree`;
CREATE TABLE IF NOT EXISTS `test_tree` (
  `id` bigint NOT NULL COMMENT '主键',
  `parent_id` bigint DEFAULT '0' COMMENT '父id',
  `dept_id` bigint DEFAULT NULL COMMENT '部门id',
  `user_id` bigint DEFAULT NULL COMMENT '用户id',
  `tree_name` varchar(255) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '值',
  `version` int DEFAULT '0' COMMENT '版本',
  `create_dept` bigint DEFAULT NULL COMMENT '创建部门',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `create_by` bigint DEFAULT NULL COMMENT '创建人',
  `update_time` datetime DEFAULT NULL COMMENT '更新时间',
  `update_by` bigint DEFAULT NULL COMMENT '更新人',
  `del_flag` int DEFAULT '0' COMMENT '删除标志',
  PRIMARY KEY (`id`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='测试树表';

-- =====================================================================
-- Agent 平台 · 知识库检索评测集
-- 目标库: forge-vue
-- 说明: 为知识库维护评测问题集(期望命中文档/关键词),批量运行检索并
--       统计命中率与 MRR(平均倒数排名),让分块/阈值/重排调参有数据依据
-- =====================================================================

CREATE TABLE IF NOT EXISTS agent_kb_eval_case
(
    id                 bigint       not null auto_increment comment '主键',
    knowledge_id       bigint       not null comment '知识库ID',
    question           varchar(1000) not null comment '评测问题',
    expected_doc_name  varchar(200) default '' comment '期望命中的文档名(模糊包含,空=不校验)',
    expected_keywords  varchar(500) default '' comment '期望命中内容包含的关键词(逗号分隔,任一命中即可,空=不校验)',
    remark             varchar(500) default '' comment '备注',
    create_time        datetime     null,
  `evidences` mediumtext COMMENT '证据标注 JSON:[{"documentId":123,"span":"原文片段"}]',
  `status` varchar(20) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE/EVIDENCE_STALE/DEPRECATED',
  `source` varchar(20) NOT NULL DEFAULT 'MANUAL' COMMENT '来源:MANUAL/DEBUG_PAGE/ZERO_HIT',
  `source_id` bigint DEFAULT NULL COMMENT '来源记录ID',
  `dataset_revision` varchar(40) DEFAULT '' COMMENT '数据集修订号',
  `update_time` datetime DEFAULT NULL COMMENT '更新时间',
    primary key (id),
    key idx_eval_case_kb (knowledge_id)
) engine = innodb comment = '知识库检索评测用例';

CREATE TABLE IF NOT EXISTS agent_kb_eval_run
(
    id           bigint   not null auto_increment comment '主键',
    knowledge_id bigint   not null comment '知识库ID',
    top_k        int      not null default 5 comment '评测时的召回条数',
    total_cases  int      not null default 0 comment '用例总数',
    hit_cases    int      not null default 0 comment '命中用例数',
    mrr          double   default 0 comment '平均倒数排名(MRR,0~1)',
    avg_score    double   default 0 comment '命中用例的平均相关度分',
    duration_ms  bigint   default 0 comment '评测耗时(毫秒)',
    create_time  datetime null,
  `hit_rate` double DEFAULT NULL COMMENT 'HitRate@K',
  `recall_at_k` double DEFAULT NULL COMMENT 'Recall@K(平均证据覆盖度)',
  `precision_at_k` double DEFAULT NULL COMMENT 'Precision@K',
  `ndcg_at_k` double DEFAULT NULL COMMENT 'NDCG@K(二值相关,ideal=min(max(标注数,观测数),K))',
  `metrics_json` mediumtext COMMENT '五指标+分组+Wilson95%明细 JSON',
  `config_snapshot` mediumtext COMMENT '评测时配置快照 JSON',
  `corpus_fingerprint` char(64) DEFAULT NULL COMMENT '语料指纹 sha256',
  `dataset_revision` varchar(40) DEFAULT '' COMMENT '数据集修订号',
  `effective_cases` int NOT NULL DEFAULT 0 COMMENT '有效用例数',
  `stale_cases` int NOT NULL DEFAULT 0 COMMENT '证据过期用例数',
  `degraded_cases` int NOT NULL DEFAULT 0 COMMENT '检索降级用例数',
  `gate_state` varchar(20) DEFAULT NULL COMMENT '门禁判决 PASSED/BLOCKED/LOG_ONLY',
  `gate_reason` varchar(40) DEFAULT NULL COMMENT '门禁原因',
  `case_results_json` mediumtext COMMENT '逐用例判定明细 JSON(交集重算用)',
    primary key (id),
    key idx_eval_run_kb (knowledge_id)
) engine = innodb comment = '知识库检索评测运行记录';

CREATE TABLE IF NOT EXISTS agent_kb_quality_issue
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

CREATE TABLE IF NOT EXISTS agent_kb_doc_acl
(
    id          bigint   not null auto_increment comment '主键',
    document_id bigint   not null comment '文档ID',
    role_id     bigint   not null comment '平台角色ID(与登录用户 roleIds 求交)',
    create_time datetime null,
    primary key (id),
    unique key uq_acl_doc_role (document_id, role_id),
    key idx_acl_role (role_id)
) engine = innodb comment = '知识库文档角色授权(visibility=RESTRICTED 时生效)';

CREATE TABLE IF NOT EXISTS agent_kb_kg_entity
(
    id           bigint       not null auto_increment comment '主键',
    knowledge_id bigint       not null comment '知识库ID',
    document_id  bigint       null comment '文档ID',
    chunk_id     bigint       not null comment '分块ID',
    node_key     varchar(128) not null comment '实体归一化键',
    node_name    varchar(128) not null comment '实体展示名',
    create_time  datetime     null,
    primary key (id),
    unique key uq_kb_kg_chunk_node (chunk_id, node_key),
    key idx_kb_kg_node (knowledge_id, node_key)
) engine = innodb comment = '知识库分块-实体映射(图谱辅助检索回落分块用)';

CREATE TABLE IF NOT EXISTS agent_kb_ext_source
(
    id           bigint       not null auto_increment comment '主键',
    knowledge_id bigint       not null comment '知识库ID',
    source_type  varchar(20)  not null comment 'URL/S3/CONFLUENCE',
    name         varchar(200) not null comment '来源名称',
    config_json  text         null comment '连接配置 JSON',
    status       varchar(20)  not null default 'IDLE' comment 'IDLE/SYNCING/FAILED',
    last_sync_at datetime     null,
    last_error   varchar(500) null,
    create_time  datetime     null,
    primary key (id),
    key idx_ext_src_kb (knowledge_id)
) engine = innodb comment = '知识库外部源';

CREATE TABLE IF NOT EXISTS agent_kb_ext_item
(
    id           bigint       not null auto_increment comment '主键',
    source_id    bigint       not null comment '外部源ID',
    external_id  varchar(200) null comment '外部唯一标识',
    title        varchar(300) null comment '条目标题',
    document_id  bigint       null comment '入库后的文档ID',
    status       varchar(20)  not null default 'PENDING' comment 'PENDING/IMPORTED/FAILED/SKIPPED',
    last_error   varchar(500) null,
    create_time  datetime     null,
    primary key (id),
    unique key uq_ext_item (source_id, external_id),
    key idx_ext_item_doc (document_id)
) engine = innodb comment = '知识库外部源条目';

CREATE TABLE IF NOT EXISTS agent_kb_vlm_config
(
    id             bigint        not null auto_increment comment '主键',
    workspace_id   bigint        not null comment '工作空间ID;0=平台默认',
    source_type    varchar(20)   not null default 'PLATFORM' comment 'PLATFORM=平台模型 / CUSTOM=OpenAI 兼容',
    model_name     varchar(128)  null comment '模型标识',
    base_url       varchar(500)  null comment 'CUSTOM 基础地址',
    api_key_cipher varchar(1000) null comment 'API Key 密文(AES-GCM,ENC: 前缀)',
    enabled        tinyint(1)    not null default 1 comment '是否启用',
    update_by      bigint        null comment '最后修改人',
    create_time    datetime      null,
    update_time    datetime      null,
    primary key (id),
    unique key uq_vlm_workspace (workspace_id)
) engine = innodb comment = '知识库图片识别模型配置(按工作空间)';

-- ============================================================================
-- 业务域表补齐（2026-11-12）
-- 来源：backend/sql/agent_cs_*.sql / agent_cc_*.sql / agent_p*.sql /
--       agent_g_series.sql / forge_kg_core.sql 的 CREATE TABLE 定义。
-- 这些表在模块开关启用时必须存在；此前 01-schema.sql 只导出框架基座与
-- Agent 平台 / 知识库 RAG 的表，导致 Docker 新库启用客服 / 语音 / 图谱 /
-- 记忆治理 / 评测门禁时报表不存在。
-- ============================================================================

-- agent_cc_call  (source: backend/sql/agent_cc_c0.sql)
CREATE TABLE IF NOT EXISTS `agent_cc_call` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `call_id` VARCHAR(128) NOT NULL COMMENT 'AICC 呼叫标识,幂等键',
  `media_provider` VARCHAR(32) NOT NULL DEFAULT 'AICC_MOCK' COMMENT '供应商标识',
  `channel_code` VARCHAR(64) NOT NULL COMMENT '语音渠道编码,关联 agent_cc_channel_config',
  `workspace_id` BIGINT NOT NULL COMMENT '归属空间',
  `agent_id` BIGINT NOT NULL COMMENT '客服机器人 AgentID',
  `conversation_id` BIGINT NULL COMMENT '映射客服会话(V2 由 CallBusinessBinder 写入)',
  `ticket_id` BIGINT NULL COMMENT '映射工单(V2)',
  `direction` VARCHAR(16) NOT NULL DEFAULT 'inbound',
  `caller_number` VARCHAR(64) NULL COMMENT 'PII 脱敏后存储',
  `callee_number` VARCHAR(64) NULL,
  `ani` VARCHAR(64) NULL,
  `dnis` VARCHAR(64) NULL,
  `status` VARCHAR(24) NOT NULL DEFAULT 'RINGING' COMMENT 'RINGING/ANSWERED/BOT_SERVING/WAITING_AGENT/AGENT_SERVING/HOLD/WRAP_UP/ENDED',
  `bot_handled` CHAR(1) DEFAULT '0',
  `work_no` VARCHAR(64) NULL COMMENT 'AICC 坐席工号',
  `ring_at` DATETIME NULL,
  `answer_at` DATETIME NULL,
  `hangup_at` DATETIME NULL,
  `duration_sec` INT DEFAULT 0,
  `bill_sec` INT DEFAULT 0,
  `hangup_cause` VARCHAR(64) NULL,
  `hangup_by` VARCHAR(16) NULL,
  `sip_code` INT NULL,
  `recording_oss_key` VARCHAR(500) NULL COMMENT '本地 OSS 对象键,不存云侧 URL',
  `recording_id` VARCHAR(128) NULL,
  `recording_ready_at` DATETIME NULL,
  `recording_retain_until` DATETIME NULL,
  `ivr_path` VARCHAR(255) NULL,
  `dtmf_digits` VARCHAR(128) NULL,
  `transfer_from` VARCHAR(64) NULL COMMENT 'AICC 侧为技能组/坐席字符串',
  `transfer_to` VARCHAR(64) NULL,
  `create_time` DATETIME NULL,
  `update_time` DATETIME NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_cccall_provider` (`media_provider`, `call_id`),
  KEY `idx_cccall_conv` (`conversation_id`),
  KEY `idx_cccall_status` (`status`, `ring_at`),
  KEY `idx_cccall_retain` (`recording_retain_until`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='客服通话主记录';

-- agent_cc_call_event  (source: backend/sql/agent_cc_c0.sql)
CREATE TABLE IF NOT EXISTS `agent_cc_call_event` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `call_id` BIGINT NOT NULL COMMENT 'agent_cc_call.id',
  `event_seq` BIGINT NOT NULL COMMENT '供应商 eventGenTime(ms),乱序判定',
  `event_type` VARCHAR(32) NOT NULL COMMENT '归一化事件名',
  `from_status` VARCHAR(24) NULL,
  `to_status` VARCHAR(24) NULL,
  `actor_type` VARCHAR(16) NOT NULL DEFAULT 'PROVIDER',
  `payload` VARCHAR(2000) NULL COMMENT '厂商原始事件摘要,异常时存死信原因',
  `create_time` DATETIME NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_ccce_dedup` (`call_id`, `event_seq`, `event_type`),
  KEY `idx_ccce_call` (`call_id`, `id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='通话事件轨迹';

-- agent_cc_call_seat_state  (source: backend/sql/agent_cc_c0.sql)
CREATE TABLE IF NOT EXISTS `agent_cc_call_seat_state` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `seat_id` BIGINT NULL COMMENT 'V2 绑定 agent_cs_seat 后回填',
  `user_id` BIGINT NULL,
  `workspace_id` BIGINT NULL,
  `work_no` VARCHAR(64) NOT NULL COMMENT 'AICC 坐席工号,业务唯一键',
  `extension` VARCHAR(32) NULL,
  `ready_state` VARCHAR(16) DEFAULT 'OFFLINE' COMMENT 'OFFLINE/READY/PAUSE/TALKING/WRAP_UP',
  `pause_reason` VARCHAR(64) NULL,
  `login_at` DATETIME NULL,
  `logout_at` DATETIME NULL,
  `create_time` DATETIME NULL,
  `update_time` DATETIME NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_cccss_workno` (`work_no`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='电话坐席状态';

-- agent_cc_call_segment  (source: backend/sql/agent_cc_c0.sql)
CREATE TABLE IF NOT EXISTS `agent_cc_call_segment` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `call_id` BIGINT NOT NULL COMMENT 'agent_cc_call.id',
  `speaker` VARCHAR(16) NOT NULL COMMENT 'caller/bot/seat',
  `text` TEXT NULL COMMENT 'PII 脱敏后文本',
  `start_ms` BIGINT NULL,
  `end_ms` BIGINT NULL,
  `asr_confidence` DECIMAL(5,2) NULL,
  `is_final` CHAR(1) DEFAULT '0',
  `segment_kind` VARCHAR(24) NOT NULL DEFAULT 'TRANSCRIPT' COMMENT 'TRANSCRIPT/TRANSFER_RING/TRANSFER_CONNECT',
  `create_time` DATETIME NULL,
  PRIMARY KEY (`id`),
  KEY `idx_cccs_call` (`call_id`, `id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='通话转写分片';

-- agent_cc_channel_config  (source: backend/sql/agent_cc_c0.sql)
CREATE TABLE IF NOT EXISTS `agent_cc_channel_config` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `channel_code` VARCHAR(64) NOT NULL COMMENT '语音渠道端点编码,回调 URL /chat-voice/{provider}/{code}',
  `media_provider` VARCHAR(32) NOT NULL DEFAULT 'AICC_MOCK',
  `service_number` VARCHAR(64) NULL COMMENT '服务号码,V0~V2 允许为空,由回调事件带入',
  `workspace_id` BIGINT NOT NULL,
  `agent_id` BIGINT NOT NULL COMMENT '客服机器人 AgentID',
  `callback_secret` VARCHAR(200) NULL COMMENT '渠道级共享密钥,CsSecretCipher 加密存;空则用全局 agent.callcenter.callback-secret',
  `voice_config_json` TEXT NULL COMMENT '供应商参数/并发上限/转人工阈值/录音开关',
  `rate_limit` INT DEFAULT 60 COMMENT '每分钟回调上限',
  `status` CHAR(1) DEFAULT '0' COMMENT '0启用 1停用',
  `del_flag` BIGINT DEFAULT 0,
  `create_time` DATETIME NULL,
  `update_time` DATETIME NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_cccc_code` (`channel_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='语音渠道配置';

-- agent_cc_metric_daily  (source: backend/sql/agent_cc_c1.sql)
CREATE TABLE IF NOT EXISTS `agent_cc_metric_daily` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `stat_date` DATE NOT NULL,
  `workspace_id` BIGINT NOT NULL,
  `call_count` INT DEFAULT 0 COMMENT '通话总数',
  `answered_count` INT DEFAULT 0 COMMENT '接通数',
  `answer_rate` DECIMAL(5,2) DEFAULT 0 COMMENT '接通率 %',
  `avg_duration_sec` INT DEFAULT 0 COMMENT '平均通话时长(秒)',
  `bot_resolve_rate` DECIMAL(5,2) DEFAULT 0 COMMENT '机器人自助解决率 %(无工单占比)',
  `recording_coverage` DECIMAL(5,2) DEFAULT 0 COMMENT '录音覆盖率 %',
  `transfer_count` INT DEFAULT 0 COMMENT '转人工通话数',
  `transfer_rate` DECIMAL(5,2) DEFAULT 0 COMMENT '转人工率 %',
  `abandon_count` INT DEFAULT 0 COMMENT '呼损数(振铃未接通)',
  `abandon_rate` DECIMAL(5,2) DEFAULT 0 COMMENT '呼损率 %',
  `avg_wait_sec` INT DEFAULT 0 COMMENT '平均等待时长(振铃→接听,秒)',
  `negative_calls` INT DEFAULT 0 COMMENT '含负面情绪洞察的通话数',
  `negative_rate` DECIMAL(5,2) DEFAULT 0 COMMENT '负面情绪通话占比 %',
  `quality_avg_score` DECIMAL(5,2) DEFAULT 0 COMMENT '当日语音质检均分(0-100)',
  `create_time` DATETIME NULL,
  `update_time` DATETIME NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_ccmd_day` (`stat_date`, `workspace_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='通话日指标';

-- agent_cc_call_profile  (source: backend/sql/agent_cc_c8.sql)
CREATE TABLE IF NOT EXISTS `agent_cc_call_profile` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `call_id` BIGINT NOT NULL COMMENT 'agent_cc_call.id',
  `workspace_id` BIGINT NULL,
  `channel_code` VARCHAR(64) NULL,
  `caller_number` VARCHAR(32) NULL COMMENT '已 PII 脱敏',
  `intent_code` VARCHAR(64) NULL COMMENT '挂机全文意图',
  `sentiment_label` VARCHAR(16) NULL COMMENT '通话内最负面的一次情绪标签',
  `sentiment_score` DECIMAL(8,4) NULL,
  `quality_score` INT NULL COMMENT '语音质检分(无工单/无规则为空)',
  `duration_sec` INT NULL,
  `turn_count` INT DEFAULT 0 COMMENT '主叫轮次',
  `transfer_flag` CHAR(1) DEFAULT '0' COMMENT '是否转过人工',
  `resolved_flag` CHAR(1) DEFAULT '0' COMMENT '机器人自助解决(无工单)',
  `tags` VARCHAR(255) NULL COMMENT '逗号分隔标签:负面/转人工/未解决/高风险',
  `summary` VARCHAR(512) NULL COMMENT '一句话摘要(确定性拼装,不含杜撰内容)',
  `payload` TEXT NULL COMMENT 'JSON:意图/情绪分布/提取结果,只存脱敏内容',
  `create_time` DATETIME NULL,
  `update_time` DATETIME NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_cccp_call` (`call_id`),
  KEY `idx_cccp_ws` (`workspace_id`, `create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='通话画像快照(C8)';

-- agent_cc_callback_task  (source: backend/sql/agent_cc_c8.sql)
CREATE TABLE IF NOT EXISTS `agent_cc_callback_task` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `call_id` BIGINT NOT NULL COMMENT 'agent_cc_call.id',
  `workspace_id` BIGINT NULL,
  `channel_code` VARCHAR(64) NULL,
  `caller_number` VARCHAR(32) NULL COMMENT '已 PII 脱敏',
  `reason` VARCHAR(32) NOT NULL DEFAULT 'NO_ANSWER' COMMENT 'NO_ANSWER(振铃未接)/ABANDONED(排队放弃)',
  `status` VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/CALLED/CLOSED',
  `attempt_count` INT DEFAULT 0,
  `last_attempt_at` DATETIME NULL,
  `operator_id` BIGINT NULL,
  `remark` VARCHAR(255) NULL,
  `create_time` DATETIME NULL,
  `update_time` DATETIME NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_ccct_call` (`call_id`),
  KEY `idx_ccct_ws_status` (`workspace_id`, `status`, `create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='呼损回拨清单(C8)';

-- agent_cs_agent_route  (source: backend/sql/agent_cs_p3.sql)
CREATE TABLE IF NOT EXISTS `agent_cs_agent_route` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `workspace_id` BIGINT NOT NULL,
  `channel_code` VARCHAR(64) NOT NULL COMMENT '空则对该空间所有渠道生效',
  `intent_code` VARCHAR(64) NOT NULL COMMENT 'A3 意图编码,如 RETURN_REFUND',
  `agent_id` BIGINT NOT NULL COMMENT '命中后使用的 Agent',
  `priority` INT DEFAULT 0 COMMENT '同意图多渠道匹配时取大者',
  `enabled` CHAR(1) DEFAULT '1',
  `create_time` DATETIME NULL,
  `update_time` DATETIME NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_csroute_scope` (`workspace_id`, `channel_code`, `intent_code`),
  KEY `idx_csroute_enabled` (`workspace_id`, `enabled`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='客服多 Agent 路由';

-- agent_cs_assist_metric_daily  (source: backend/sql/agent_cs_a5.sql)
CREATE TABLE IF NOT EXISTS `agent_cs_assist_metric_daily` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `workspace_id` BIGINT NOT NULL,
  `stat_date` DATE NOT NULL,
  `suggestion_count` INT DEFAULT 0 COMMENT '推荐条数(口径基数)',
  `adopted_count` INT DEFAULT 0 COMMENT '采纳条数(含动作执行)',
  `action_count` INT DEFAULT 0 COMMENT '动作执行条数',
  `intent_count` INT DEFAULT 0 COMMENT '带意图标签条数',
  `recycled_count` INT DEFAULT 0 COMMENT '零采纳回流条数',
  `create_time` DATETIME NULL,
  `update_time` DATETIME NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_csassistmetric_ws_date` (`workspace_id`, `stat_date`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='坐席辅助日指标';

-- agent_cs_audit_log  (source: backend/sql/agent_cs_c4.sql)
CREATE TABLE IF NOT EXISTS `agent_cs_audit_log` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `workspace_id` BIGINT NULL,
  `channel_code` VARCHAR(64) NULL,
  `event_type` VARCHAR(32) NOT NULL,
  `actor_type` VARCHAR(16) NOT NULL COMMENT 'VISITOR/SEAT/SYSTEM',
  `actor_id` VARCHAR(64) NULL,
  `detail` VARCHAR(1000) NULL,
  `result_code` VARCHAR(32) NULL COMMENT '结果码(SUCCESS/ERROR/DENIED/INTERRUPTED/QUOTA_BLOCKED)',
  `duration_ms` INT NULL COMMENT '工具执行耗时(毫秒)',
  `create_time` DATETIME NULL,
  PRIMARY KEY (`id`),
  KEY `idx_csal_ws_time` (`workspace_id`, `create_time`),
  KEY `idx_csal_type` (`event_type`),
  KEY `idx_csal_result` (`result_code`, `create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='客服合规审计';

-- agent_cs_badcase  (source: backend/sql/agent_cs_p1.sql)
CREATE TABLE IF NOT EXISTS `agent_cs_badcase` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `workspace_id` BIGINT NOT NULL,
  `source` VARCHAR(24) NOT NULL COMMENT 'CSAT_LOW / SEAT_IGNORE / ZERO_HIT / SENSITIVE_HIT',
  `conversation_id` BIGINT NULL,
  `ticket_id` BIGINT NULL,
  `suggestion_id` BIGINT NULL,
  `question` VARCHAR(500) NULL,
  `answer` VARCHAR(1000) NULL,
  `status` VARCHAR(24) DEFAULT 'PENDING' COMMENT 'PENDING/ADOPTED_KB/ADOPTED_EVAL/IGNORED',
  `operator` VARCHAR(64) NULL,
  `note` VARCHAR(500) NULL,
  `exported` CHAR(1) DEFAULT '0' COMMENT '是否已导出为评测用例 JSON',
  `create_time` DATETIME NULL,
  `update_time` DATETIME NULL,
  PRIMARY KEY (`id`),
  KEY `idx_csbc_status` (`workspace_id`, `status`, `id`),
  KEY `idx_csbc_source` (`workspace_id`, `source`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='客服 badcase';

-- agent_cs_channel  (source: backend/sql/agent_cs_c0.sql)
CREATE TABLE IF NOT EXISTS `agent_cs_channel` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `channel_code` VARCHAR(64) NOT NULL COMMENT '端点编码,全局唯一',
  `channel_type` VARCHAR(32) NOT NULL DEFAULT 'widget' COMMENT 'widget/h5/wechat_mp/wecom/openapi',
  `workspace_id` BIGINT NOT NULL COMMENT '归属空间,免登录链路 callerWorkspaceId',
  `agent_id` BIGINT NOT NULL COMMENT '客服机器人 AgentID',
  `owner_user_id` BIGINT NOT NULL COMMENT '渠道服务账号,仅占位 agent_session.user_id',
  `api_key_id` BIGINT NULL COMMENT '关联 agent_api_key',
  `knowledge_ids` VARCHAR(500) NULL COMMENT '允许检索的知识库ID,逗号分隔',
  `rate_limit` INT DEFAULT 60 COMMENT '每分钟请求数',
  `cors_allowlist` VARCHAR(500) NULL COMMENT '嵌入域名白名单,逗号分隔',
  `session_mode` VARCHAR(16) DEFAULT 'continuous' COMMENT 'continuous/per_message',
  `fallback_reply` VARCHAR(500) NULL COMMENT '兜底话术,生产必填',
  `handoff_policy` VARCHAR(32) DEFAULT 'keyword' COMMENT 'keyword/low_confidence/zero_hit/manual',
  `locale` VARCHAR(16) DEFAULT 'zh-CN',
  `status` CHAR(1) DEFAULT '0' COMMENT '0启用 1停用',
  `del_flag` BIGINT DEFAULT 0,
  `create_time` DATETIME NULL,
  `update_time` DATETIME NULL,
  `channel_secret` VARCHAR(128) NULL COMMENT '渠道回调验签密钥',
  `fallback_reply_en` VARCHAR(500) NULL COMMENT '英文兜底话术',
  `handoff_keywords` VARCHAR(500) NULL COMMENT '转人工关键词(逗号分隔),空则用平台默认',
  `handoff_rules` TEXT NULL COMMENT '转人工规则链 JSON([{type,enabled,...}]),空用内置默认',
  `identity_binding` VARCHAR(32) NULL COMMENT '访客身份绑定方式,空=未配置(WRITE/HIGH_RISK 工具一律 deny)',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_csc_code` (`channel_code`),
  KEY `idx_csc_ws` (`workspace_id`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='客服渠道端点';

-- agent_cs_conversation  (source: backend/sql/agent_cs_c0.sql)
CREATE TABLE IF NOT EXISTS `agent_cs_conversation` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `agent_session_id` BIGINT NOT NULL COMMENT 'agent_session.id',
  `visitor_id` BIGINT NOT NULL,
  `channel_code` VARCHAR(64) NOT NULL,
  `workspace_id` BIGINT NOT NULL,
  `agent_id` BIGINT NOT NULL,
  `ticket_id` BIGINT NULL,
  `status` VARCHAR(24) DEFAULT 'ACTIVE' COMMENT 'ACTIVE/WAITING_AGENT/HUMAN_SERVING/ENDED',
  `last_visitor_at` DATETIME NULL,
  `last_seat_at` DATETIME NULL,
  `csat_invited_at` DATETIME NULL,
  `create_time` DATETIME NULL,
  `update_time` DATETIME NULL,
  `agent_routed` CHAR(1) DEFAULT '0' COMMENT '是否已确定接待 Agent(1 已确定,不再路由)',
  `last_intent_code` VARCHAR(64) NULL COMMENT '最近一轮意图编码(规则命中,未命中为 NULL)',
  `sentiment_streak` INT NOT NULL DEFAULT 0 COMMENT '访客负面情绪连续轮数(得分不超阈值递增,超阈值清零)',
  `last_sentiment_score` DECIMAL(5,4) NULL COMMENT '最近一轮访客情绪分([-1,1],负面为负)',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_csc_session` (`agent_session_id`),
  KEY `idx_csc_visitor` (`visitor_id`, `status`),
  KEY `idx_csc_ws_status` (`workspace_id`, `status`),
  KEY `idx_csc_visitor_time` (`visitor_id`, `create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='客服会话';

-- agent_cs_cost_daily  (source: backend/sql/agent_cs_p2.sql)
CREATE TABLE IF NOT EXISTS `agent_cs_cost_daily` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `workspace_id` BIGINT NOT NULL,
  `stat_date` DATE NOT NULL,
  `conversation_count` INT DEFAULT 0 COMMENT '当日有模型调用的客服会话数',
  `call_count` INT DEFAULT 0 COMMENT '模型调用次数',
  `prompt_tokens` BIGINT DEFAULT 0,
  `completion_tokens` BIGINT DEFAULT 0,
  `total_tokens` BIGINT DEFAULT 0,
  `estimated_count` INT DEFAULT 0 COMMENT '估算 token 的调用数(非实测)',
  `cost_amount` DECIMAL(18,6) NULL COMMENT '参考金额,未配单价时为空',
  `currency` VARCHAR(8) NULL,
  `resolved_count` INT DEFAULT 0 COMMENT '当日结案工单数(算每次解决成本用)',
  `create_time` DATETIME NULL,
  `update_time` DATETIME NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_cscost_ws_date` (`workspace_id`, `stat_date`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='客服会话日成本';

-- agent_cs_csat  (source: backend/sql/agent_cs_c0.sql)
CREATE TABLE IF NOT EXISTS `agent_cs_csat` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `conversation_id` BIGINT NOT NULL,
  `workspace_id` BIGINT NOT NULL,
  `channel_code` VARCHAR(64) NULL,
  `score` INT NULL,
  `comment` VARCHAR(1000) NULL,
  `tags` VARCHAR(255) NULL,
  `invited_at` DATETIME NULL,
  `submitted_at` DATETIME NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_cssat_conv` (`conversation_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='客服会话满意度';

-- agent_cs_dead_letter  (source: backend/sql/agent_cs_p2b.sql)
CREATE TABLE IF NOT EXISTS `agent_cs_dead_letter` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `workspace_id` BIGINT NOT NULL,
  `channel_code` VARCHAR(64) NULL,
  `event_type` VARCHAR(32) NOT NULL COMMENT 'HANDOFF_SUMMARY / ATTACHMENT_UPLOAD / SEAT_ASSIST',
  `conversation_id` BIGINT NULL,
  `ticket_id` BIGINT NULL,
  `payload` TEXT NULL COMMENT '重投所需参数(JSON)',
  `error` VARCHAR(500) NULL COMMENT '失败原因(截断)',
  `status` VARCHAR(16) DEFAULT 'PENDING' COMMENT 'PENDING/REPLAYED/IGNORED/UNREPLAYABLE',
  `retry_count` INT DEFAULT 0,
  `last_retry_at` DATETIME NULL,
  `create_time` DATETIME NULL,
  `update_time` DATETIME NULL,
  PRIMARY KEY (`id`),
  KEY `idx_csdl_status` (`workspace_id`, `status`, `id`),
  KEY `idx_csdl_type` (`workspace_id`, `event_type`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='客服死信(可重投的业务失败)';

-- agent_cs_handoff_summary  (source: backend/sql/agent_cs_p0.sql)
CREATE TABLE IF NOT EXISTS `agent_cs_handoff_summary` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `workspace_id` BIGINT NOT NULL,
  `conversation_id` BIGINT NOT NULL,
  `ticket_id` BIGINT NULL,
  `summary_json` TEXT NULL COMMENT '结构化摘要(oneLine/intent/emotion/tried/pending/nextStep/opening)',
  `source_version` INT DEFAULT 0 COMMENT '基于第几条消息生成的摘要,客户发新消息后 +1 触发重算',
  `degraded` CHAR(1) DEFAULT '0' COMMENT '是否降级为规则摘要 1/0',
  `create_time` DATETIME NULL,
  `update_time` DATETIME NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_cshs_conv` (`conversation_id`),
  KEY `idx_cshs_ticket` (`ticket_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='坐席交接摘要';

-- agent_cs_intent  (source: backend/sql/agent_cs_a3.sql)
CREATE TABLE IF NOT EXISTS `agent_cs_intent` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `workspace_id` BIGINT NULL COMMENT '归属空间(NULL=平台级,对所有空间生效)',
  `intent_code` VARCHAR(64) NOT NULL COMMENT '意图编码(落推荐记录,运营聚合键)',
  `intent_name` VARCHAR(64) NOT NULL COMMENT '意图名称(坐席卡片展示)',
  `keywords` TEXT NULL COMMENT '关键词(逗号分隔,任一命中即打标)',
  `rule_type` VARCHAR(16) DEFAULT 'KEYWORD' COMMENT 'KEYWORD / REGEX',
  `kb_ids` VARCHAR(255) NULL COMMENT '关联知识库ID(逗号分隔),命中后并入检索范围',
  `priority` INT DEFAULT 100 COMMENT '匹配优先级(小者优先)',
  `enabled` CHAR(1) DEFAULT '1' COMMENT '启用 1/0',
  `create_time` DATETIME NULL,
  `update_time` DATETIME NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_csintent_ws_code` (`workspace_id`, `intent_code`),
  KEY `idx_csintent_enabled` (`enabled`, `priority`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='坐席辅助意图标签';

-- agent_cs_kg_expand_metric  (source: backend/sql/agent_p3_m4_memory_governance.sql)
CREATE TABLE IF NOT EXISTS `agent_cs_kg_expand_metric`
(
    `id`                BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键',
    `workspace_id`      BIGINT      NOT NULL COMMENT '工作空间ID',
    `stat_date`         DATE        NOT NULL COMMENT '统计日期',
    `total_searches`    BIGINT      NOT NULL DEFAULT 0 COMMENT '知识检索总次数',
    `expanded_searches` BIGINT      NOT NULL DEFAULT 0 COMMENT '带图谱扩展词的检索次数',
    `zero_hit_total`    BIGINT      NOT NULL DEFAULT 0 COMMENT '零命中总次数',
    `zero_hit_expanded` BIGINT      NOT NULL DEFAULT 0 COMMENT '扩展后仍零命中次数',
    `update_time`       DATETIME     DEFAULT NULL COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_kg_metric` (`workspace_id`, `stat_date`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='图谱辅助检索效果日聚合(P3/M4,对比开/关的零命中率)';

-- agent_cs_knowledge_answer  (source: backend/sql/agent_cs_c0.sql)
CREATE TABLE IF NOT EXISTS `agent_cs_knowledge_answer` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `workspace_id` BIGINT NOT NULL,
  `knowledge_id` BIGINT NULL,
  `question` VARCHAR(500) NOT NULL,
  `question_hash` CHAR(64) NOT NULL,
  `answer` TEXT NOT NULL,
  `source` VARCHAR(32) DEFAULT 'manual',
  `source_ref_id` BIGINT NULL,
  `status` VARCHAR(16) DEFAULT 'PENDING' COMMENT 'PENDING/ACTIVE/REJECTED/EXPIRED',
  `effective_from` DATETIME NULL,
  `effective_to` DATETIME NULL,
  `audit_by` BIGINT NULL,
  `audit_note` VARCHAR(500) NULL,
  `create_time` DATETIME NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_cska_hash` (`workspace_id`, `question_hash`),
  KEY `idx_cska_status` (`status`, `effective_from`, `effective_to`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='FAQ与补录答案';

-- agent_cs_knowledge_gap  (source: backend/sql/agent_cs_p1.sql)
CREATE TABLE IF NOT EXISTS `agent_cs_knowledge_gap` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `workspace_id` BIGINT NOT NULL,
  `question_hash` CHAR(64) NOT NULL,
  `question` VARCHAR(500) NOT NULL,
  `category` VARCHAR(64) NULL COMMENT '分类(取自意图编码,可空)',
  `priority` VARCHAR(16) DEFAULT 'P2' COMMENT 'P0/P1/P2,按命中次数自动升级',
  `hit_count` INT DEFAULT 1,
  `status` VARCHAR(16) DEFAULT 'OPEN' COMMENT 'OPEN/FILLED/IGNORED',
  `answer_id` BIGINT NULL COMMENT '补齐后关联的 FAQ id',
  `last_hit_at` DATETIME NULL,
  `create_time` DATETIME NULL,
  `update_time` DATETIME NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_cskg_ws_hash` (`workspace_id`, `question_hash`),
  KEY `idx_cskg_status_hits` (`status`, `hit_count`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='知识缺口';

-- agent_cs_message_receipt  (source: backend/sql/agent_cs_c0.sql)
CREATE TABLE IF NOT EXISTS `agent_cs_message_receipt` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `session_id` BIGINT NOT NULL,
  `client_msg_id` VARCHAR(64) NOT NULL,
  `message_id` BIGINT NULL,
  `acceptance` VARCHAR(16) NOT NULL COMMENT 'ACCEPTED/REJECTED/UNKNOWN',
  `run_id` VARCHAR(64) NULL,
  `reply_content` TEXT NULL COMMENT '本轮回复快照,重发时原样返回',
  `create_time` DATETIME NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_csr_dedup` (`session_id`, `client_msg_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='访客消息幂等回执';

-- agent_cs_metric_daily  (source: backend/sql/agent_cs_c0.sql)
CREATE TABLE IF NOT EXISTS `agent_cs_metric_daily` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `stat_date` DATE NOT NULL,
  `workspace_id` BIGINT NOT NULL,
  `channel_code` VARCHAR(64) NULL,
  `bot_sessions` INT DEFAULT 0,
  `handoff_count` INT DEFAULT 0,
  `first_response_p95_ms` BIGINT DEFAULT 0,
  `resolve_rate` DECIMAL(6,2) DEFAULT 0,
  `handoff_rate` DECIMAL(6,2) DEFAULT 0,
  `csat_avg` DECIMAL(4,2) DEFAULT 0,
  `csat_response_rate` DECIMAL(6,2) DEFAULT 0,
  `sla_pass_rate` DECIMAL(6,2) DEFAULT 0,
  `repeat_contact_rate` DECIMAL(5,2) NULL COMMENT '重复进线率(%):当日会话中同访客24h内已有过进线的占比',
  `containment_rate` DECIMAL(5,2) NULL COMMENT 'AI 自动解决率(%):未产生转人工工单即终结的会话占比',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_csmd` (`stat_date`, `workspace_id`, `channel_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='客服日指标';
CREATE TABLE IF NOT EXISTS `agent_cs_action_log` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `conversation_id` bigint NOT NULL COMMENT '客服会话ID',
  `visitor_id` bigint DEFAULT NULL,
  `workspace_id` bigint NOT NULL,
  `channel_code` varchar(64) DEFAULT NULL,
  `tool_name` varchar(128) NOT NULL,
  `action_key` varchar(128) NOT NULL COMMENT '幂等业务键(模板按入参展开)',
  `risk_level` varchar(16) NOT NULL DEFAULT 'WRITE',
  `status` varchar(16) NOT NULL DEFAULT 'RUNNING' COMMENT 'RUNNING/DONE/FAILED/BLOCKED',
  `result_summary` varchar(500) DEFAULT NULL COMMENT '执行结果摘要(供重复请求回放)',
  `create_time` datetime DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_csal_key` (`conversation_id`, `action_key`),
  KEY `idx_csal_ws` (`workspace_id`, `create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='客服动作幂等日志(C3)';

-- agent_cs_quality_check  (source: backend/sql/agent_cs_c0.sql)
CREATE TABLE IF NOT EXISTS `agent_cs_quality_check` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `message_id` BIGINT NULL,
  `ticket_id` BIGINT NULL,
  `workspace_id` BIGINT NULL,
  `score` INT DEFAULT 100,
  `hit_rules` VARCHAR(1000) NULL,
  `source` VARCHAR(16) DEFAULT 'AUTO',
  `review_status` VARCHAR(16) DEFAULT 'PENDING',
  `create_time` DATETIME NULL,
  PRIMARY KEY (`id`),
  KEY `idx_csqc_ticket` (`ticket_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='客服质检结果';

-- agent_cs_quality_rule  (source: backend/sql/agent_cs_c0.sql)
CREATE TABLE IF NOT EXISTS `agent_cs_quality_rule` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `workspace_id` BIGINT NULL,
  `rule_type` VARCHAR(16) DEFAULT 'KEYWORD' COMMENT 'KEYWORD/REGEX/LLM',
  `pattern` VARCHAR(500) NOT NULL,
  `score_delta` INT DEFAULT -10,
  `severity` VARCHAR(16) DEFAULT 'WARN',
  `enabled` CHAR(1) DEFAULT '0',
  `create_time` DATETIME NULL,
  PRIMARY KEY (`id`),
  KEY `idx_csqr_ws_enabled` (`workspace_id`, `enabled`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='客服质检规则';

-- agent_cs_queue  (source: backend/sql/agent_cs_c0.sql)
CREATE TABLE IF NOT EXISTS `agent_cs_queue` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `ticket_id` BIGINT NOT NULL,
  `workspace_id` BIGINT NOT NULL,
  `required_skill` VARCHAR(64) NULL,
  `priority` VARCHAR(16) DEFAULT 'NORMAL',
  `enqueued_at` DATETIME NULL,
  `deadline_at` DATETIME NULL,
  `escalate_count` INT DEFAULT 0,
  `state` VARCHAR(16) DEFAULT 'WAITING' COMMENT 'WAITING/ASSIGNED/TIMEOUT',
  `create_time` DATETIME NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_csq_ticket` (`ticket_id`),
  KEY `idx_csq_wait` (`state`, `enqueued_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='客服排队';

-- agent_cs_seat  (source: backend/sql/agent_cs_c0.sql)
CREATE TABLE IF NOT EXISTS `agent_cs_seat` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `user_id` BIGINT NOT NULL COMMENT 'sys_user.userId',
  `workspace_id` BIGINT NOT NULL,
  `seat_group` VARCHAR(64) DEFAULT 'default',
  `skills` VARCHAR(500) NULL COMMENT '逗号分隔技能标签',
  `max_load` INT DEFAULT 5,
  `current_load` INT DEFAULT 0,
  `online` CHAR(1) DEFAULT '0',
  `role` VARCHAR(16) DEFAULT 'AGENT' COMMENT 'AGENT/LEAD/ADMIN',
  `create_time` DATETIME NULL,
  `update_time` DATETIME NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_css_user` (`user_id`),
  KEY `idx_css_ws_group` (`workspace_id`, `seat_group`, `online`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='客服坐席';

-- agent_cs_seat_suggestion  (source: backend/sql/agent_cs_c5.sql)
CREATE TABLE IF NOT EXISTS `agent_cs_seat_suggestion` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `conversation_id` BIGINT NOT NULL COMMENT '关联会话',
  `ticket_id` BIGINT NULL COMMENT '关联工单(已转人工时)',
  `message_id` BIGINT NULL COMMENT '触发推荐的消息(文本场景为空,机器人回复id)',
  `intent` VARCHAR(64) NULL COMMENT '意图标签(A3 起,A1 为空)',
  `items` TEXT NULL COMMENT '候选话术 JSON 快照(type/title/content/score/sourceId)',
  `actions` TEXT NULL COMMENT '建议操作 JSON 快照(A4 起,A1 为空数组)',
  `top_score` DECIMAL(5,2) NULL COMMENT '最高匹配分',
  `adopted` CHAR(1) DEFAULT '0' COMMENT '是否采纳 0/1',
  `adopt_action` VARCHAR(32) NULL COMMENT '采纳方式 send/copy/ignore',
  `create_time` DATETIME NULL,
  `call_id` BIGINT NULL COMMENT '语音来源的通话主键(A2)，文本为空',
  `recycled` CHAR(1) DEFAULT '0' COMMENT '是否已零采纳回流 1/0',
  `source` VARCHAR(16) NOT NULL DEFAULT 'text' COMMENT '触发来源 text/voice',
  `trigger_text` VARCHAR(500) NULL COMMENT '触发原文(文本消息/转写分片)，回流时作为 FAQ 问题',
  PRIMARY KEY (`id`),
  KEY `idx_csssg_conv` (`conversation_id`, `id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='坐席辅助推荐记录';

-- agent_cs_semantic_cache  (source: backend/sql/agent_cs_p1.sql)
CREATE TABLE IF NOT EXISTS `agent_cs_semantic_cache` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `workspace_id` BIGINT NOT NULL,
  `channel_code` VARCHAR(64) NOT NULL COMMENT '分区键:同空间同渠道才可能命中',
  `question_hash` CHAR(64) NOT NULL COMMENT '归一化问题的 SHA-256(精确匹配用)',
  `question` VARCHAR(500) NOT NULL COMMENT '归一化后的问题原文(近似匹配与运营查看)',
  `answer` TEXT NOT NULL COMMENT '出站过滤后的答案',
  `intent` VARCHAR(64) NULL COMMENT '命中时的意图编码(白名单校验用)',
  `hits` INT DEFAULT 0 COMMENT '命中次数',
  `create_time` DATETIME NULL,
  `update_time` DATETIME NULL,
  `last_hit_at` DATETIME NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_cssc_scope_hash` (`workspace_id`, `channel_code`, `question_hash`),
  KEY `idx_cssc_scope_time` (`workspace_id`, `channel_code`, `update_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='客服语义缓存';

-- agent_cs_sensitive_word  (source: backend/sql/agent_cs_p0.sql)
CREATE TABLE IF NOT EXISTS `agent_cs_sensitive_word` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `workspace_id` BIGINT NULL COMMENT '归属空间(NULL=平台级)',
  `word` VARCHAR(100) NOT NULL COMMENT '词条',
  `severity` VARCHAR(16) DEFAULT 'BLOCK' COMMENT 'BLOCK 拦截 / MASK 打码 / WARN 仅告警',
  `remark` VARCHAR(255) NULL,
  `enabled` CHAR(1) DEFAULT '1',
  `create_time` DATETIME NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_cssw_ws_word` (`workspace_id`, `word`),
  KEY `idx_cssw_enabled` (`enabled`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='客服敏感词库';

-- agent_cs_slot_progress  (source: backend/sql/agent_cs_p1.sql)
CREATE TABLE IF NOT EXISTS `agent_cs_slot_progress` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `conversation_id` BIGINT NOT NULL,
  `form_name` VARCHAR(64) NOT NULL COMMENT '表单名,对应配置里的 slot-forms key',
  `collected_json` TEXT NULL COMMENT '已收集槽位 {name: value}',
  `asking` VARCHAR(64) NULL COMMENT '当前正在追问的槽位,NULL 表示未开始或已填满',
  `create_time` DATETIME NULL,
  `update_time` DATETIME NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_cssp_conv_form` (`conversation_id`, `form_name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='多轮槽位收集进度';

-- agent_cs_ticket  (source: backend/sql/agent_cs_c0.sql)
CREATE TABLE IF NOT EXISTS `agent_cs_ticket` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `ticket_no` VARCHAR(32) NOT NULL,
  `conversation_id` BIGINT NOT NULL,
  `session_id` BIGINT NOT NULL,
  `visitor_id` BIGINT NOT NULL,
  `channel_code` VARCHAR(64) NOT NULL,
  `workspace_id` BIGINT NOT NULL,
  `agent_id` BIGINT NOT NULL,
  `title` VARCHAR(200) NULL,
  `category` VARCHAR(32) DEFAULT 'CONSULT',
  `priority` VARCHAR(16) DEFAULT 'NORMAL',
  `status` VARCHAR(24) NOT NULL DEFAULT 'AI_SERVING',
  `assignee_id` BIGINT NULL,
  `seat_group` VARCHAR(64) NULL,
  `handoff_reason` VARCHAR(200) NULL,
  `resolve_note` VARCHAR(1000) NULL,
  `reopen_count` INT DEFAULT 0,
  `first_response_ms` BIGINT NULL COMMENT '坐席首响毫秒',
  `bot_first_token_ms` BIGINT NULL COMMENT '机器人首 token 毫秒',
  `waiting_since` DATETIME NULL,
  `resolved_at` DATETIME NULL,
  `create_time` DATETIME NULL,
  `update_time` DATETIME NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_cst_no` (`ticket_no`),
  KEY `idx_cst_conv_active` (`conversation_id`, `status`),
  KEY `idx_cst_status_wait` (`status`, `waiting_since`),
  KEY `idx_csticket_sla` (`status`, `update_time`),
  KEY `idx_cst_assignee` (`assignee_id`, `status`),
  KEY `idx_cst_ws_time` (`workspace_id`, `create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='客服工单';

-- agent_cs_ticket_event  (source: backend/sql/agent_cs_c0.sql)
CREATE TABLE IF NOT EXISTS `agent_cs_ticket_event` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `ticket_id` BIGINT NOT NULL,
  `event_type` VARCHAR(32) NOT NULL,
  `from_status` VARCHAR(24) NULL,
  `to_status` VARCHAR(24) NULL,
  `actor_type` VARCHAR(16) NOT NULL COMMENT 'VISITOR/SEAT/BOT/SYSTEM',
  `actor_id` VARCHAR(64) NULL,
  `note` VARCHAR(1000) NULL,
  `trace_id` VARCHAR(64) NULL,
  `create_time` DATETIME NULL,
  PRIMARY KEY (`id`),
  KEY `idx_cste_ticket` (`ticket_id`, `id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='工单事件轨迹';

-- agent_cs_visitor  (source: backend/sql/agent_cs_c0.sql)
CREATE TABLE IF NOT EXISTS `agent_cs_visitor` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `channel_code` VARCHAR(64) NOT NULL,
  `external_user_id` VARCHAR(128) NOT NULL COMMENT '渠道内唯一标识',
  `nickname` VARCHAR(128) NULL,
  `consent_memory` CHAR(1) DEFAULT '0',
  `consent_export` CHAR(1) DEFAULT '0',
  `last_active_at` DATETIME NULL,
  `erased_at` DATETIME NULL COMMENT '隐私擦除时间,软标记',
  `create_time` DATETIME NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_csv_ext` (`channel_code`, `external_user_id`),
  KEY `idx_csv_active` (`channel_code`, `last_active_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='客服访客';

-- agent_eval_baseline  (source: backend/sql/agent_p1_governance.sql)
CREATE TABLE IF NOT EXISTS `agent_eval_baseline`
(
    `id`           BIGINT      NOT NULL AUTO_INCREMENT,
    `agent_id`     BIGINT      NOT NULL,
    `dataset_id`   BIGINT      NOT NULL,
    `run_id`       BIGINT      NOT NULL,
    `metrics_json` TEXT        NOT NULL,
    `active`       CHAR(1)     NOT NULL DEFAULT '1',
    `create_by`    BIGINT      NULL,
    `create_time`  DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_eval_baseline_agent` (`agent_id`, `active`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '黄金评测基线';

-- agent_eval_case  (source: backend/sql/agent_p1_governance.sql)
CREATE TABLE IF NOT EXISTS `agent_eval_case`
(
  `id`                BIGINT   NOT NULL AUTO_INCREMENT,
  `dataset_id`        BIGINT   NOT NULL,
  `question`          TEXT     NOT NULL,
  `expected_keywords` VARCHAR(1000) NULL COMMENT '规则评分关键词(逗号分隔)',
  `reference_answer`  TEXT     NULL COMMENT '参考答案(Judge 用)',
  `create_time`       DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `expected_refusal` TINYINT(1) NOT NULL DEFAULT 0 COMMENT '期望拒答用例(R4-4 五维裁判:refusal_correctness 只在此类用例上有效)',
  `source` VARCHAR(32) NULL COMMENT '样本来源(cs_badcase / cs_sampled;NULL=手工录入)',
  `source_ref_id` BIGINT NULL COMMENT '来源单据ID(badcase id / 会话 id)',
  `review_status` CHAR(1) NOT NULL DEFAULT '1' COMMENT '审核态(0待确认 1已通过 2已驳回)',
    PRIMARY KEY (`id`),
    KEY `idx_eval_case_dataset` (`dataset_id`),
    UNIQUE KEY `uk_eval_case_source` (`source`,`source_ref_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '评测样本';

-- agent_eval_case_result  (source: backend/sql/agent_p1_governance.sql)
CREATE TABLE IF NOT EXISTS `agent_eval_case_result`
(
    `id`            BIGINT        NOT NULL AUTO_INCREMENT,
    `run_id`        BIGINT        NOT NULL,
    `case_id`       BIGINT        NOT NULL,
    `question`      TEXT          NULL,
    `answer`        MEDIUMTEXT    NULL,
    `score`         DECIMAL(6, 2) NULL COMMENT '规则分',
    `judge_score`   DECIMAL(6, 2) NULL COMMENT 'LLM-as-Judge 分',
    `judge_reason`  VARCHAR(1000) NULL,
    `latency_ms`    BIGINT        NULL,
    `total_tokens`  INT           NULL,
    `error_message` VARCHAR(1000) NULL,
    `create_time`   DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_eval_result_run` (`run_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '评测样本结果';

-- agent_eval_dataset  (source: backend/sql/agent_p1_governance.sql)
CREATE TABLE IF NOT EXISTS `agent_eval_dataset`
(
    `id`           BIGINT       NOT NULL AUTO_INCREMENT,
    `workspace_id` BIGINT       NOT NULL,
    `agent_id`     BIGINT       NULL COMMENT '建议绑定的 Agent(可空)',
    `name`         VARCHAR(100) NOT NULL,
    `description`  VARCHAR(500) NULL,
    `create_by`    BIGINT       NULL,
    `create_time`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `del_flag`     BIGINT       NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    KEY `idx_eval_dataset_ws` (`workspace_id`, `create_time`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = 'Agent 评测集';

-- agent_eval_run  (source: backend/sql/agent_p1_governance.sql)
CREATE TABLE IF NOT EXISTS `agent_eval_run`
(
    `id`                BIGINT        NOT NULL AUTO_INCREMENT,
    `dataset_id`        BIGINT        NOT NULL,
    `agent_id`          BIGINT        NOT NULL,
    `workspace_id`      BIGINT        NULL,
    `status`            VARCHAR(16)   NOT NULL DEFAULT 'running' COMMENT 'running/success/failed',
    `total_cases`       INT           NOT NULL DEFAULT 0,
    `finished_cases`    INT           NOT NULL DEFAULT 0,
    `avg_score`         DECIMAL(6, 2) NULL,
    `tool_success_rate` DECIMAL(6, 2) NULL,
    `refusal_rate`      DECIMAL(6, 2) NULL,
    `p95_latency_ms`    BIGINT        NULL,
    `total_tokens`      INT           NULL,
    `judge_enabled`     CHAR(1)       NOT NULL DEFAULT '0',
    `config_hash`       VARCHAR(64)   NULL COMMENT '评测时的 Agent config_hash(判断评测是否覆盖当前配置)',
    `error_message`     VARCHAR(1000) NULL,
    `create_by`         BIGINT        NULL,
    `create_time`       DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `finish_time`       DATETIME      NULL,
    PRIMARY KEY (`id`),
    KEY `idx_eval_run_agent` (`agent_id`, `create_time`),
    KEY `idx_eval_run_dataset` (`dataset_id`, `create_time`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '评测任务';

-- agent_memory_archive  (source: backend/sql/agent_p3_m4_memory_governance.sql)
CREATE TABLE IF NOT EXISTS `agent_memory_archive`
(
    `id`               BIGINT      NOT NULL COMMENT '原 agent_memory.id',
    `agent_id`         BIGINT      NOT NULL COMMENT 'AgentID',
    `workspace_id`     BIGINT       DEFAULT NULL COMMENT '工作空间ID',
    `user_id`          BIGINT       DEFAULT NULL COMMENT '用户ID,NULL=平台级',
    `content`          VARCHAR(1000) DEFAULT NULL COMMENT '记忆内容',
    `fact_key`         VARCHAR(128) DEFAULT NULL COMMENT '事实键',
    `importance`       INT          DEFAULT NULL COMMENT '重要性',
    `hit_count`        INT          DEFAULT NULL COMMENT '命中次数',
    `source_session_id` BIGINT      DEFAULT NULL COMMENT '来源会话',
    `archive_reason`   VARCHAR(32)  DEFAULT NULL COMMENT '归档原因 TTL/QUOTA/FORGET',
    `archive_time`     DATETIME     DEFAULT NULL COMMENT '归档时间',
    `create_time`      DATETIME     DEFAULT NULL COMMENT '原创建时间',
    PRIMARY KEY (`id`),
    KEY `idx_mem_arch_scope` (`agent_id`, `user_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='长期记忆归档(P3/M4,TTL过期/配额淘汰/遗忘)';

-- agent_message_archive  (source: backend/sql/agent_p3_m4_memory_governance.sql)
CREATE TABLE IF NOT EXISTS `agent_message_archive`
(
    `id`              BIGINT      NOT NULL COMMENT '原 agent_message.id',
    `session_id`      BIGINT      NOT NULL COMMENT '会话ID',
    `role`            VARCHAR(10)  DEFAULT NULL COMMENT '角色(user/assistant)',
    `content`         TEXT         DEFAULT NULL COMMENT '消息内容',
    `ext_json`        MEDIUMTEXT   DEFAULT NULL COMMENT '扩展信息',
    `feedback`        VARCHAR(10)  DEFAULT NULL COMMENT '用户反馈',
    `feedback_comment` VARCHAR(500) DEFAULT NULL COMMENT '反馈备注',
    `feedback_tags`   VARCHAR(500) DEFAULT NULL COMMENT '反馈标签',
    `feedback_time`   DATETIME     DEFAULT NULL COMMENT '反馈时间',
    `citations`       TEXT         DEFAULT NULL COMMENT '引用溯源',
    `create_dept`     BIGINT       DEFAULT NULL COMMENT '创建部门',
    `create_by`       BIGINT       DEFAULT NULL COMMENT '创建者',
    `create_time`     DATETIME     DEFAULT NULL COMMENT '创建时间',
    `update_by`       BIGINT       DEFAULT NULL COMMENT '更新者',
    `update_time`     DATETIME     DEFAULT NULL COMMENT '更新时间',
    `attachments`     TEXT         DEFAULT NULL COMMENT '图片附件',
    `client_msg_id`   VARCHAR(64)  DEFAULT NULL COMMENT '客户端幂等键',
    `sender_type`     VARCHAR(16)  DEFAULT NULL COMMENT '发送者类型',
    `ticket_id`       BIGINT       DEFAULT NULL COMMENT '关联工单ID',
    `owner_user_id`   BIGINT       DEFAULT NULL COMMENT '会话归属用户(遗忘级联用,归档时冗余)',
    `archive_reason`  VARCHAR(32)  DEFAULT NULL COMMENT '归档原因 RETENTION',
    `archive_time`    DATETIME     DEFAULT NULL COMMENT '归档时间',
    PRIMARY KEY (`id`),
    KEY `idx_msg_arch_session` (`session_id`),
    KEY `idx_msg_arch_user` (`owner_user_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='会话消息归档(P3/M4 L4,主库减重,摘要与抽取不回溯)';

-- agent_prompt_template  (source: backend/sql/agent_p1_governance.sql)
CREATE TABLE IF NOT EXISTS `agent_prompt_template`
(
    `id`                BIGINT       NOT NULL AUTO_INCREMENT,
    `workspace_id`      BIGINT       NOT NULL,
    `name`              VARCHAR(100) NOT NULL,
    `description`       VARCHAR(500) NULL,
    `category`          VARCHAR(32)  NOT NULL DEFAULT 'system' COMMENT 'system/user/rag/tool/evaluation/workflow',
    `content`           TEXT         NOT NULL,
    `variable_schema`   TEXT         NULL COMMENT '强类型变量契约 JSON',
    `stable_version_id` BIGINT       NULL COMMENT '稳定版版本ID(FOLLOW_STABLE 指向)',
    `status`            VARCHAR(16)  NOT NULL DEFAULT 'draft',
    `create_by`         BIGINT       NULL,
    `create_time`       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time`       DATETIME     NULL,
    `del_flag`          BIGINT       NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_prompt_ws_name` (`workspace_id`, `name`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = 'Prompt 模板';

-- agent_prompt_version  (source: backend/sql/agent_p1_governance.sql)
CREATE TABLE IF NOT EXISTS `agent_prompt_version`
(
    `id`              BIGINT      NOT NULL AUTO_INCREMENT,
    `template_id`     BIGINT      NOT NULL,
    `version_no`      INT         NOT NULL,
    `content`         TEXT        NOT NULL,
    `variable_schema` TEXT        NULL,
    `content_hash`    VARCHAR(64) NOT NULL COMMENT '渲染内容 SHA-256',
    `remark`          VARCHAR(500) NULL,
    `create_by`       BIGINT      NULL,
    `create_time`     DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_prompt_version_no` (`template_id`, `version_no`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = 'Prompt 模板版本';

-- agent_run  (source: backend/sql/agent_p0_run_trace.sql)
CREATE TABLE IF NOT EXISTS agent_run
(
  run_id                 varchar(64)   NOT NULL COMMENT '运行ID(轮次 roundId,UUID)',
  workspace_id           bigint        NULL COMMENT '所属工作空间',
  agent_id               bigint        NULL COMMENT 'Agent ID',
  session_id             bigint        NULL COMMENT '会话 ID',
  user_id                bigint        NULL COMMENT '发起用户 ID(重放鉴权用)',
  model_name             varchar(128)  NULL COMMENT '执行时模型标识',
  run_type               varchar(24)   NOT NULL DEFAULT 'chat' COMMENT 'chat/debug/regenerate/blocking',
  status                 varchar(16)   NOT NULL DEFAULT 'running' COMMENT 'running/success/failed/cancelled',
  prompt_tokens          int           NULL DEFAULT 0,
  completion_tokens      int           NULL DEFAULT 0,
  total_tokens           int           NULL DEFAULT 0,
  latency_ms             bigint        NULL COMMENT '本轮总耗时',
  first_token_latency_ms bigint        NULL COMMENT '首个增量片段延迟',
  error_message          varchar(1000) NULL COMMENT '失败原因(脱敏后)',
  create_time            datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP,
  finish_time            datetime      NULL,
  `channel_code` VARCHAR(64) NULL COMMENT '客服渠道编码',
  `cs_ticket_id` BIGINT NULL COMMENT '客服工单ID',
  `visitor_id` BIGINT NULL COMMENT '客服访客ID',
    PRIMARY KEY (run_id),
    KEY idx_agent_run_session (session_id, create_time),
    KEY idx_agent_run_agent (agent_id, create_time),
    KEY idx_agent_run_status (status, create_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = 'Agent 运行主记录';

-- agent_run_step  (source: backend/sql/agent_p0_run_trace.sql)
CREATE TABLE IF NOT EXISTS agent_run_step
(
    id           bigint       NOT NULL AUTO_INCREMENT,
    run_id       varchar(64)  NOT NULL COMMENT '运行 ID',
    step_no      int          NOT NULL DEFAULT 0 COMMENT '步骤序号',
    step_type    varchar(32)  NOT NULL COMMENT 'ROUTER/CONTEXT/RETRIEVAL/MEMORY/SEMANTIC/BUDGET/LLM/TEAM/WORKFLOW/ERROR(常量见 TraceStepType)',
    step_name    varchar(128) NULL COMMENT '步骤名称',
    status       varchar(16)  NOT NULL DEFAULT 'success' COMMENT 'success/failed/skipped',
    latency_ms   bigint       NULL COMMENT '步骤耗时',
    token_usage  int          NULL COMMENT '该步骤 Token 用量',
    payload      longtext     NULL COMMENT '阶段载荷 JSON(截断后)',
    create_time  datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_agent_run_step_run (run_id, step_no)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = 'Agent 运行步骤明细';

-- agent_version  (source: backend/sql/agent_p1_governance.sql)
CREATE TABLE IF NOT EXISTS `agent_version`
(
    `id`             BIGINT       NOT NULL AUTO_INCREMENT,
    `agent_id`       BIGINT       NOT NULL COMMENT 'AgentID',
    `workspace_id`   BIGINT       NULL COMMENT '工作空间ID',
    `version_no`     INT          NOT NULL COMMENT '版本号(按 Agent 递增)',
    `snapshot_json`  MEDIUMTEXT   NOT NULL COMMENT '完整配置快照 JSON',
    `config_hash`    VARCHAR(64)  NULL COMMENT '快照 SHA-256',
    `remark`         VARCHAR(500) NULL COMMENT '版本说明',
    `source`         VARCHAR(24)  NOT NULL DEFAULT 'publish' COMMENT 'publish/rollback/manual',
    `create_by`      BIGINT       NULL,
    `create_time`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_agent_version_no` (`agent_id`, `version_no`),
    KEY `idx_agent_version_time` (`agent_id`, `create_time`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = 'Agent 配置版本快照';

-- agent_workflow_endpoint  (source: backend/sql/agent_g_series.sql)
CREATE TABLE IF NOT EXISTS `agent_workflow_endpoint` (
    `id`             BIGINT        NOT NULL                COMMENT '主键',
    `agent_id`       BIGINT        NOT NULL                COMMENT 'AgentID(工作流归属)',
    `workspace_id`   BIGINT        NULL                    COMMENT '工作空间ID',
    `endpoint_code`  VARCHAR(64)   NOT NULL                COMMENT '端点编码(路径段,全局唯一)',
    `name`           VARCHAR(100)  NULL                    COMMENT '端点名称',
    `api_key_id`     BIGINT        NULL                    COMMENT '绑定的 API 密钥ID(空为仅空间内调试)',
    `rate_limit`     INT           NOT NULL DEFAULT 60     COMMENT '每分钟调用上限',
    `timeout_seconds` INT          NOT NULL DEFAULT 60     COMMENT '同步执行超时秒数(1-300)',
    `status`         CHAR(1)       NOT NULL DEFAULT '0'    COMMENT '状态(0启用 1停用)',
    `create_dept`    BIGINT        NULL                    COMMENT '创建部门',
    `create_by`      BIGINT        NULL                    COMMENT '创建者',
    `create_time`    DATETIME      NULL                    COMMENT '创建时间',
    `update_by`      BIGINT        NULL                    COMMENT '更新者',
    `update_time`    DATETIME      NULL                    COMMENT '更新时间',
    `del_flag`       BIGINT        NOT NULL DEFAULT 0      COMMENT '删除标志(0存在 其他删除)',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_endpoint_code` (`endpoint_code`),
    KEY `idx_wfe_agent` (`agent_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='工作流对外 API 端点';

-- kg_edge  (source: backend/sql/forge_kg_core.sql)
CREATE TABLE IF NOT EXISTS `kg_edge`
(
    `id`           BIGINT         NOT NULL AUTO_INCREMENT COMMENT '主键',
    `scope_type`   VARCHAR(32)    NOT NULL COMMENT '范围类型',
    `scope_id`     BIGINT         NOT NULL COMMENT '范围ID',
    `user_id`      BIGINT         NOT NULL DEFAULT 0 COMMENT '用户ID,0=范围共享级',
    `source_key`   VARCHAR(128)   NOT NULL COMMENT '主语实体归一化键',
    `relation`     VARCHAR(64)    NOT NULL COMMENT '关系(归一化动词短语)',
    `target_key`   VARCHAR(128)   NOT NULL COMMENT '宾语实体归一化键',
    `weight`       DECIMAL(10, 2) NOT NULL DEFAULT 1.00 COMMENT '关系权重(重复出现累加)',
    `evidence_ref` BIGINT                  DEFAULT NULL COMMENT '通用溯源引用(记忆/工单/FAQ ID)',
    `last_seen`    DATETIME                DEFAULT NULL COMMENT '最近一次出现时间',
    `create_time`  DATETIME                DEFAULT NULL COMMENT '创建时间',
    `update_time`  DATETIME                DEFAULT NULL COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_kg_edge` (`scope_type`, `scope_id`, `user_id`, `source_key`, `relation`, `target_key`),
    KEY `idx_kg_edge_source` (`scope_type`, `scope_id`, `user_id`, `source_key`, `weight`),
    KEY `idx_kg_edge_target` (`scope_type`, `scope_id`, `user_id`, `target_key`, `weight`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='知识图谱-关系边(forge-kg)';

-- kg_node  (source: backend/sql/forge_kg_core.sql)
CREATE TABLE IF NOT EXISTS `kg_node`
(
    `id`          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `scope_type`  VARCHAR(32)  NOT NULL COMMENT '范围类型 agent/workspace/channel',
    `scope_id`    BIGINT       NOT NULL COMMENT '范围ID',
    `user_id`     BIGINT       NOT NULL DEFAULT 0 COMMENT '用户ID,0=范围共享级',
    `name_key`    VARCHAR(128) NOT NULL COMMENT '实体归一化键(小写去标点)',
    `name`        VARCHAR(128) NOT NULL COMMENT '实体展示名',
    `node_type`   VARCHAR(32)  NOT NULL DEFAULT 'entity' COMMENT '实体类型',
    `salience`    DECIMAL(10, 2) NOT NULL DEFAULT 1.00 COMMENT '实体显著度(出现次数加权)',
    `last_seen`   DATETIME              DEFAULT NULL COMMENT '最近一次出现时间',
    `create_time` DATETIME              DEFAULT NULL COMMENT '创建时间',
    `update_time` DATETIME              DEFAULT NULL COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_kg_node` (`scope_type`, `scope_id`, `user_id`, `name_key`),
    KEY `idx_kg_node_scan` (`scope_type`, `scope_id`, `user_id`, `salience`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='知识图谱-实体节点(forge-kg)';

-- release_gate_execution  (source: backend/sql/agent_p1_governance.sql)
CREATE TABLE IF NOT EXISTS `release_gate_execution`
(
    `id`            BIGINT       NOT NULL AUTO_INCREMENT,
    `agent_id`      BIGINT       NOT NULL,
    `target_version` INT         NULL,
    `status`        VARCHAR(16)  NOT NULL COMMENT 'passed/blocked/preview',
    `checks_json`   TEXT         NULL,
    `reason`        VARCHAR(1000) NULL,
    `waived`        CHAR(1)      NOT NULL DEFAULT '0',
    `requested_by`  BIGINT       NULL,
    `create_time`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_gate_exec_agent` (`agent_id`, `create_time`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '门禁执行明细';

-- release_gate_policy  (source: backend/sql/agent_p1_governance.sql)
CREATE TABLE IF NOT EXISTS `release_gate_policy`
(
    `id`                    BIGINT        NOT NULL AUTO_INCREMENT,
    `workspace_id`          BIGINT        NULL COMMENT 'NULL = 平台默认策略',
    `resource_type`         VARCHAR(24)   NOT NULL DEFAULT 'agent',
    `enabled`               CHAR(1)       NOT NULL DEFAULT '1',
    `min_eval_score`        DECIMAL(6, 2) NOT NULL DEFAULT 60.00,
    `max_failure_rate`      DECIMAL(6, 2) NOT NULL DEFAULT 20.00 COMMENT '百分比',
    `max_p95_latency_ms`    BIGINT        NOT NULL DEFAULT 60000,
    `require_baseline`      CHAR(1)       NOT NULL DEFAULT '0',
    `max_metric_regression` DECIMAL(6, 2) NOT NULL DEFAULT 10.00 COMMENT '允许退化的百分点',
    `require_security_pass` CHAR(1)       NOT NULL DEFAULT '0',
    `require_cost_budget`   CHAR(1)       NOT NULL DEFAULT '0',
    `create_time`           DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_gate_policy_ws` (`workspace_id`, `resource_type`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '发布门禁策略';

-- release_gate_waiver  (source: backend/sql/agent_p1_governance.sql)
CREATE TABLE IF NOT EXISTS `release_gate_waiver`
(
    `id`           BIGINT       NOT NULL AUTO_INCREMENT,
    `agent_id`     BIGINT       NOT NULL,
    `reason`       VARCHAR(500) NOT NULL,
    `status`       VARCHAR(16)  NOT NULL DEFAULT 'pending' COMMENT 'pending/approved/rejected',
    `requested_by` BIGINT       NULL,
    `approved_by`  BIGINT       NULL COMMENT '审批人须与申请人不同',
    `expires_at`   DATETIME     NULL,
    `create_time`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_gate_waiver_agent` (`agent_id`, `status`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '门禁豁免申请';

-- ============================================================================
-- 与开发库对齐的补充结构（2026-11-12）
-- 来源：forge-vue 开发库 information_schema / SHOW CREATE TABLE（只读比对）。
-- ============================================================================

-- flow_event_wait (source: dev DB forge-vue, warm-flow 事件等待表)
DROP TABLE IF EXISTS `flow_event_wait`;
CREATE TABLE IF NOT EXISTS `flow_event_wait` (
  `id` bigint NOT NULL COMMENT '主键id',
  `tenant_id` varchar(40) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '租户id',
  `del_flag` char(1) COLLATE utf8mb4_unicode_ci DEFAULT '0' COMMENT '删除标志',
  `definition_id` bigint DEFAULT NULL COMMENT '流程定义id（定时启动事件必须有）',
  `instance_id` bigint DEFAULT NULL COMMENT '流程实例id（实例等待事件必须有）',
  `node_code` varchar(100) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '事件节点编码（定位沿用 definition_id + node_code）',
  `event_type` varchar(40) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '事件类型（MESSAGE_START TIMER_START TIMER_CATCH MESSAGE_CATCH MESSAGE_THROW TIMER_BOUNDARY MESSAGE_BOUNDARY ERROR_CATCH ERROR_BOUNDARY TERMINATE_END MESSAGE_END）',
  `event_name` varchar(100) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '事件名称（消息事件使用）',
  `attached_task_id` bigint DEFAULT NULL COMMENT '边界事件关联的待办任务id',
  `trigger_type` varchar(40) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '触发策略类型（AFTER FIXED POINT_IN_TIME）',
  `trigger_interval` varchar(100) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '触发策略原始配置',
  `next_trigger_at` datetime DEFAULT NULL COMMENT '下次触发时间',
  `timezone` varchar(40) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '定时触发使用的时区',
  `repeat_count` int DEFAULT NULL COMMENT '已触发次数',
  `max_repeat_count` int DEFAULT NULL COMMENT '最大触发次数',
  `status` varchar(20) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '等待状态（WAITING RUNNING CANCELLED RETRY DEAD）',
  `retry_count` int DEFAULT NULL COMMENT '已重试次数',
  `max_retry_count` int DEFAULT NULL COMMENT '最大重试次数',
  `retry_at` datetime DEFAULT NULL COMMENT '下次重试时间',
  `lock_owner` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '抢占持有者标识',
  `lock_until` datetime DEFAULT NULL COMMENT '抢占租约到期时间',
  `version` int DEFAULT NULL COMMENT '乐观锁版本号',
  `payload` text COLLATE utf8mb4_unicode_ci COMMENT '消息事件接收的业务载荷',
  `last_error` varchar(500) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '最近一次失败原因摘要',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `update_time` datetime DEFAULT NULL COMMENT '更新时间',
  PRIMARY KEY (`id`) USING BTREE,
  KEY `event_wait_status_next` (`status`,`next_trigger_at`,`id`) USING BTREE,
  KEY `event_wait_status_retry` (`status`,`retry_at`,`id`) USING BTREE,
  KEY `event_wait_tenant_status_next` (`tenant_id`,`status`,`next_trigger_at`,`id`) USING BTREE,
  KEY `event_wait_tenant_status_retry` (`tenant_id`,`status`,`retry_at`,`id`) USING BTREE,
  KEY `event_wait_instance_task` (`instance_id`,`attached_task_id`) USING BTREE,
  KEY `event_wait_name_instance_status` (`event_name`,`instance_id`,`status`) USING BTREE,
  KEY `event_wait_def_node_status` (`definition_id`,`node_code`,`status`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='事件等待表';

-- agent_skill  (source: backend/sql/agent_skill.sql, 08方案 / A1)
DROP TABLE IF EXISTS `agent_skill`;
CREATE TABLE IF NOT EXISTS `agent_skill` (
  `id`              BIGINT        NOT NULL COMMENT '主键',
  `name`            VARCHAR(50)   NOT NULL COMMENT '技能名称(同一空间内唯一)',
  `description`     VARCHAR(100)  DEFAULT '' COMMENT '一行摘要(懒加载目录展示用)',
  `instructions`    MEDIUMTEXT    NULL COMMENT '操作手册(Markdown,模型按需加载)',
  `tool_ids`        VARCHAR(500)  DEFAULT '' COMMENT '引用平台工具ID列表(逗号分隔)',
  `mcp_ids`         VARCHAR(500)  DEFAULT '' COMMENT '引用MCP服务器ID列表(逗号分隔)',
  `knowledge_ids`   VARCHAR(500)  DEFAULT '' COMMENT '引用知识库ID列表(逗号分隔)',
  `prompt_fragment` VARCHAR(2000) DEFAULT '' COMMENT '加载后追加的提示词片段',
  `version`         INT           DEFAULT 1 COMMENT '技能版本(内容变更自增)',
  `status`          CHAR(1)       DEFAULT '0' COMMENT '状态(0正常 1停用)',
  `workspace_id`    BIGINT        NULL COMMENT '归属工作空间(NULL=平台共享)',
  `remark`          VARCHAR(200)  DEFAULT '' COMMENT '备注',
  `create_dept`     BIGINT        NULL COMMENT '创建部门',
  `create_by`       BIGINT        NULL COMMENT '创建者',
  `create_time`     DATETIME      NULL COMMENT '创建时间',
  `update_by`       BIGINT        NULL COMMENT '更新者',
  `update_time`     DATETIME      NULL COMMENT '更新时间',
  `del_flag`        BIGINT        DEFAULT 0 COMMENT '删除标志(0存在 其他删除)',
  PRIMARY KEY (`id`),
  KEY `idx_skill_ws` (`workspace_id`,`del_flag`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Agent 声明式技能包';
-- agent_skill_install  (source: backend/sql/agent_skill.sql, 08方案 / A1)
DROP TABLE IF EXISTS `agent_skill_install`;
CREATE TABLE IF NOT EXISTS `agent_skill_install` (
  `id`           BIGINT   NOT NULL COMMENT '主键',
  `skill_id`     BIGINT   NOT NULL COMMENT '技能ID',
  `agent_id`     BIGINT   NOT NULL COMMENT 'AgentID',
  `workspace_id` BIGINT   NULL COMMENT '安装时技能归属空间(审计)',
  `pinned_version` INT   NULL COMMENT '钉住的技能版本(NULL=跟随最新)',
  `create_dept`  BIGINT   NULL COMMENT '创建部门',
  `create_by`    BIGINT   NULL COMMENT '安装人',
  `create_time`  DATETIME NULL COMMENT '安装时间',
  `update_by`    BIGINT   NULL COMMENT '更新者',
  `update_time`  DATETIME NULL COMMENT '更新时间',
  `del_flag`     BIGINT   DEFAULT 0 COMMENT '删除标志(0存在 其他删除)',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_skill_agent` (`skill_id`,`agent_id`),
  KEY `idx_skill_agent_agent` (`agent_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Agent 技能安装绑定';

-- agent_skill_version  (source: backend/sql/agent_skill_version.sql, 08方案 P1-B)
DROP TABLE IF EXISTS `agent_skill_version`;
CREATE TABLE IF NOT EXISTS `agent_skill_version` (
  `id`              BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
  `skill_id`        BIGINT        NOT NULL COMMENT '技能ID',
  `version_no`      INT           NOT NULL COMMENT '技能版本号(与 agent_skill.version 对齐)',
  `name`            VARCHAR(50)   NULL COMMENT '该版本名称(改名也需可追溯)',
  `description`     VARCHAR(100)  DEFAULT '' COMMENT '该版本一行摘要',
  `instructions`    MEDIUMTEXT    NULL COMMENT '该版本操作手册(Markdown)',
  `tool_ids`        VARCHAR(500)  DEFAULT '' COMMENT '该版本引用平台工具ID列表',
  `mcp_ids`         VARCHAR(500)  DEFAULT '' COMMENT '该版本引用MCP服务器ID列表',
  `knowledge_ids`   VARCHAR(500)  DEFAULT '' COMMENT '该版本引用知识库ID列表',
  `prompt_fragment` VARCHAR(2000) DEFAULT '' COMMENT '该版本提示词片段',
  `content_hash`    VARCHAR(64)   NULL COMMENT '内容 SHA-256(参与 Agent config_hash)',
  `create_by`       BIGINT        NULL COMMENT '归档人',
  `create_time`     DATETIME      NULL COMMENT '归档时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_skill_version` (`skill_id`, `version_no`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='技能内容版本快照';

-- agent_mcp_client  (source: backend/sql/agent_mcp_client.sql, 08方案 A5 增强)
DROP TABLE IF EXISTS `agent_mcp_client`;
CREATE TABLE IF NOT EXISTS `agent_mcp_client` (
  `id`             BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
  `name`           VARCHAR(64)  NOT NULL COMMENT '客户端名称(如 DSH / Claude Code)',
  `token_hash`     CHAR(64)     NOT NULL COMMENT '令牌 SHA-256(不存明文)',
  `token_prefix`   VARCHAR(16)  NOT NULL COMMENT '令牌前缀(便于识别,不足以还原)',
  `workspace_id`   BIGINT       NULL COMMENT '绑定工作空间(NULL=平台全量只读)',
  `scopes`         VARCHAR(200) DEFAULT '' COMMENT '授权域(knowledge,skill;空=全部)',
  `enabled`        CHAR(1)      DEFAULT '0' COMMENT '状态(0启用 1停用)',
  `expire_time`    DATETIME     NULL COMMENT '过期时间(NULL=长期)',
  `last_used_time` DATETIME     NULL COMMENT '最近调用时间',
  `remark`         VARCHAR(200) DEFAULT '' COMMENT '备注',
  `create_dept`    BIGINT       NULL COMMENT '创建部门',
  `create_by`      BIGINT       NULL COMMENT '创建者',
  `create_time`    DATETIME     NULL COMMENT '创建时间',
  `update_by`      BIGINT       NULL COMMENT '更新者',
  `update_time`    DATETIME     NULL COMMENT '更新时间',
  `del_flag`       BIGINT       DEFAULT 0 COMMENT '删除标志(0存在 其他删除)',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_mcp_client_token` (`token_hash`),
  KEY `idx_mcp_client_ws` (`workspace_id`, `del_flag`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='对外 MCP 客户端';

-- ------------------------------------------------------------------
-- P0 运行轨迹  (source: backend/sql/agent_p0_run_trace.sql)
-- ------------------------------------------------------------------
DROP TABLE IF EXISTS `agent_run`;
CREATE TABLE IF NOT EXISTS `agent_run`
(
    run_id                 varchar(64)   NOT NULL COMMENT '运行ID(轮次 roundId,UUID)',
    workspace_id           bigint        NULL COMMENT '所属工作空间',
    agent_id               bigint        NULL COMMENT 'Agent ID',
    session_id             bigint        NULL COMMENT '会话 ID',
    user_id                bigint        NULL COMMENT '发起用户 ID(重放鉴权用)',
    model_name             varchar(128)  NULL COMMENT '执行时模型标识',
    run_type               varchar(24)   NOT NULL DEFAULT 'chat' COMMENT 'chat/debug/regenerate/blocking',
    status                 varchar(16)   NOT NULL DEFAULT 'running' COMMENT 'running/success/failed/cancelled',
    prompt_tokens          int           NULL DEFAULT 0,
    completion_tokens      int           NULL DEFAULT 0,
    total_tokens           int           NULL DEFAULT 0,
    latency_ms             bigint        NULL COMMENT '本轮总耗时',
    first_token_latency_ms bigint        NULL COMMENT '首个增量片段延迟',
    error_message          varchar(1000) NULL COMMENT '失败原因(脱敏后)',
    channel_code           varchar(64)   NULL COMMENT '客服渠道编码',
    visitor_id             bigint        NULL COMMENT '客服访客ID',
    cs_ticket_id           bigint        NULL COMMENT '客服工单ID',
    create_time            datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    finish_time            datetime      NULL,
    PRIMARY KEY (run_id),
    KEY idx_agent_run_session (session_id, create_time),
    KEY idx_agent_run_agent (agent_id, create_time),
    KEY idx_agent_run_status (status, create_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = 'Agent 运行主记录';

DROP TABLE IF EXISTS `agent_run_step`;
CREATE TABLE IF NOT EXISTS `agent_run_step`
(
    id           bigint       NOT NULL AUTO_INCREMENT,
    run_id       varchar(64)  NOT NULL COMMENT '运行 ID',
    step_no      int          NOT NULL DEFAULT 0 COMMENT '步骤序号',
    step_type    varchar(32)  NOT NULL COMMENT 'ROUTER/CONTEXT/RETRIEVAL/MEMORY/SEMANTIC/BUDGET/LLM/TEAM/WORKFLOW/ERROR(常量见 TraceStepType)',
    step_name    varchar(128) NULL COMMENT '步骤名称',
    status       varchar(16)  NOT NULL DEFAULT 'success' COMMENT 'success/failed/skipped',
    latency_ms   bigint       NULL COMMENT '步骤耗时',
    token_usage  int          NULL COMMENT '该步骤 Token 用量',
    payload      longtext     NULL COMMENT '阶段载荷 JSON(截断后)',
    create_time  datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_agent_run_step_run (run_id, step_no)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = 'Agent 运行步骤明细';

-- ------------------------------------------------------------------
-- R5 文档 ACL  (source: backend/sql/agent_kb_r5_acl.sql)
-- ------------------------------------------------------------------
DROP TABLE IF EXISTS `agent_kb_doc_acl`;
CREATE TABLE IF NOT EXISTS `agent_kb_doc_acl`
(
    id          bigint   not null auto_increment comment '主键',
    document_id bigint   not null comment '文档ID',
    role_id     bigint   not null comment '平台角色ID(与登录用户 roleIds 求交)',
    create_time datetime null,
    primary key (id),
    unique key uq_acl_doc_role (document_id, role_id),
    key idx_acl_role (role_id)
) engine = innodb comment = '知识库文档角色授权(visibility=RESTRICTED 时生效)';

-- ------------------------------------------------------------------
-- R5 检索评测  (source: backend/sql/agent_kb_eval.sql)
-- ------------------------------------------------------------------
DROP TABLE IF EXISTS `agent_kb_eval_case`;
CREATE TABLE IF NOT EXISTS `agent_kb_eval_case`
(
    id                 bigint       not null auto_increment comment '主键',
    knowledge_id       bigint       not null comment '知识库ID',
    question           varchar(1000) not null comment '评测问题',
    expected_doc_name  varchar(200) default '' comment '期望命中的文档名(模糊包含,空=不校验)',
    expected_keywords  varchar(500) default '' comment '期望命中内容包含的关键词(逗号分隔,任一命中即可,空=不校验)',
    evidences          mediumtext   null comment '证据标注 JSON:[{"documentId":123,"span":"原文片段"}],空=legacy 口径(文档名+关键词)',
    status             varchar(20)  not null default 'ACTIVE' comment 'ACTIVE/EVIDENCE_STALE/DEPRECATED',
    source             varchar(20)  not null default 'MANUAL' comment '来源:MANUAL/DEBUG_PAGE/ZERO_HIT',
    source_id          bigint       null comment '来源记录ID(如查询日志ID/分块ID)',
    dataset_revision   varchar(40)  default '' comment '数据集修订号(变更标注时递增)',
    remark             varchar(500) default '' comment '备注',
    create_time        datetime     null,
    update_time        datetime     null comment '更新时间',
    primary key (id),
    key idx_eval_case_kb (knowledge_id)
) engine = innodb comment = '知识库检索评测用例';

DROP TABLE IF EXISTS `agent_kb_eval_run`;
CREATE TABLE IF NOT EXISTS `agent_kb_eval_run`
(
    id           bigint   not null auto_increment comment '主键',
    knowledge_id bigint   not null comment '知识库ID',
    top_k        int      not null default 5 comment '评测时的召回条数',
    total_cases  int      not null default 0 comment '用例总数',
    hit_cases    int      not null default 0 comment '命中用例数',
    mrr          double   default 0 comment '平均倒数排名(MRR,0~1)',
    avg_score    double   default 0 comment '命中用例的平均相关度分',
    duration_ms  bigint   default 0 comment '评测耗时(毫秒)',
    hit_rate          double      null comment 'HitRate@K(命中用例/有效用例)',
    recall_at_k       double      null comment 'Recall@K(平均证据覆盖度)',
    precision_at_k    double      null comment 'Precision@K(相关条数/K)',
    ndcg_at_k         double      null comment 'NDCG@K(二值相关,ideal=min(max(标注数,观测数),K))',
    metrics_json      mediumtext  null comment '五指标+分组+Wilson95%置信区间明细 JSON',
    config_snapshot   mediumtext  null comment '评测时配置快照 JSON(嵌入模型/分块参数/阈值)',
    corpus_fingerprint char(64)   null comment '语料指纹 sha256',
    dataset_revision  varchar(40) default '' comment '数据集修订号',
    effective_cases   int         not null default 0 comment '有效用例数(ACTIVE)',
    stale_cases       int         not null default 0 comment '证据过期用例数(EVIDENCE_STALE)',
    degraded_cases    int         not null default 0 comment '检索降级用例数',
    gate_state        varchar(20) null comment '门禁判决 PASSED/BLOCKED/LOG_ONLY',
    gate_reason       varchar(40) null comment '门禁原因 METRICS_REGRESSED/WITHIN_TOLERANCE/BASELINE_RECORDED/...',
    case_results_json mediumtext  null comment '逐用例判定明细 JSON(交集重算与门禁对比用)',
    create_time  datetime null,
    primary key (id),
    key idx_eval_run_kb (knowledge_id)
) engine = innodb comment = '知识库检索评测运行记录';

-- ------------------------------------------------------------------
-- R5 规模化扩展  (source: backend/sql/agent_kb_r5_scale.sql)
-- ------------------------------------------------------------------
DROP TABLE IF EXISTS `agent_kb_ext_item`;
CREATE TABLE IF NOT EXISTS `agent_kb_ext_item`
(
    id           bigint       not null auto_increment comment '主键',
    source_id    bigint       not null comment '外部源ID',
    external_id  varchar(200) null comment '外部唯一标识(URL 或对象键)',
    title        varchar(300) null comment '条目标题',
    document_id  bigint       null comment '入库后的文档ID',
    status       varchar(20)  not null default 'PENDING' comment 'PENDING/IMPORTED/FAILED/SKIPPED',
    last_error   varchar(500) null,
    create_time  datetime     null,
    primary key (id),
    unique key uq_ext_item (source_id, external_id),
    key idx_ext_item_doc (document_id)
) engine = innodb comment = '知识库外部源条目(去重与增量同步依据)';

DROP TABLE IF EXISTS `agent_kb_ext_source`;
CREATE TABLE IF NOT EXISTS `agent_kb_ext_source`
(
    id           bigint       not null auto_increment comment '主键',
    knowledge_id bigint       not null comment '知识库ID',
    source_type  varchar(20)  not null comment 'URL/S3/CONFLUENCE',
    name         varchar(200) not null comment '来源名称',
    config_json  text         null comment '连接配置 JSON(URL 为 {url,selector};凭据不落明文)',
    status       varchar(20)  not null default 'IDLE' comment 'IDLE/SYNCING/FAILED',
    last_sync_at datetime     null,
    last_error   varchar(500) null,
    create_time  datetime     null,
    primary key (id),
    key idx_ext_src_kb (knowledge_id)
) engine = innodb comment = '知识库外部源';

DROP TABLE IF EXISTS `agent_kb_kg_entity`;
CREATE TABLE IF NOT EXISTS `agent_kb_kg_entity`
(
    id           bigint      not null auto_increment comment '主键',
    knowledge_id bigint      not null comment '知识库ID',
    document_id  bigint      null comment '文档ID',
    chunk_id     bigint      not null comment '分块ID',
    node_key     varchar(128) not null comment '实体归一化键(与 kg_node.name_key 同口径)',
    node_name    varchar(128) not null comment '实体展示名',
    create_time  datetime    null,
    primary key (id),
    unique key uq_kb_kg_chunk_node (chunk_id, node_key),
    key idx_kb_kg_node (knowledge_id, node_key)
) engine = innodb comment = '知识库分块-实体映射(图谱辅助检索回落到分块用)';

-- ------------------------------------------------------------------
-- R3 质量治理  (source: backend/sql/agent_kb_r3_quality.sql)
-- ------------------------------------------------------------------
DROP TABLE IF EXISTS `agent_kb_quality_issue`;
CREATE TABLE IF NOT EXISTS `agent_kb_quality_issue`
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

-- ------------------------------------------------------------------
-- VLM 配置  (source: backend/sql/agent_kb_vlm_config.sql)
-- ------------------------------------------------------------------
DROP TABLE IF EXISTS `agent_kb_vlm_config`;
CREATE TABLE IF NOT EXISTS `agent_kb_vlm_config`
(
    id             bigint        not null auto_increment comment '主键',
    workspace_id   bigint        not null comment '工作空间ID;0=平台默认(所有空间生效)',
    source_type    varchar(20)   not null default 'PLATFORM' comment 'PLATFORM=平台已配置模型(agent_model) / CUSTOM=OpenAI 兼容自定义',
    model_name     varchar(128)  null comment '模型标识(PLATFORM 为 agent_model.model_name)',
    base_url       varchar(500)  null comment 'CUSTOM 模式的基础地址(如 https://api.xxx.com/v1)',
    api_key_cipher varchar(1000) null comment 'API Key 密文(AES-GCM,ENC: 前缀),任何接口不回显明文',
    enabled        tinyint(1)    not null default 1 comment '是否启用',
    update_by      bigint        null comment '最后修改人',
    create_time    datetime      null,
    update_time    datetime      null,
    primary key (id),
    unique key uq_vlm_workspace (workspace_id)
) engine = innodb comment = '知识库图片识别模型配置(按工作空间)';

-- ------------------------------------------------------------------
-- 呼叫中心 C4 洞察  (source: backend/sql/agent_cc_c4.sql)
-- ------------------------------------------------------------------
DROP TABLE IF EXISTS `agent_cc_call_insight`;
CREATE TABLE IF NOT EXISTS `agent_cc_call_insight` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `call_id` BIGINT NOT NULL COMMENT 'agent_cc_call.id',
  `workspace_id` BIGINT NULL,
  `kind` VARCHAR(16) NOT NULL COMMENT 'SENTIMENT/INTENT/EXTRACT/QA_REPORT',
  `item_key` VARCHAR(64) NOT NULL DEFAULT '' COMMENT 'kind 内多实例键:情绪轮次 turn-<seq>/意图 final/提取字段名/报告类型',
  `segment_until_seq` BIGINT NOT NULL DEFAULT 0 COMMENT '增量游标:已消费的最大 segment.id,单调不减',
  `label` VARCHAR(64) NULL COMMENT '情绪标签 NEGATIVE/NEUTRAL/POSITIVE、意图码、ERROR',
  `score` DECIMAL(8,4) NULL COMMENT '情绪分 [-1,1] 或分类置信度',
  `payload` TEXT NULL COMMENT 'JSON,只存脱敏内容:引擎标识/命中规则/统计摘要',
  `create_time` DATETIME NULL,
  `update_time` DATETIME NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_ccci_call_kind_key` (`call_id`, `kind`, `item_key`),
  KEY `idx_ccci_call` (`call_id`, `kind`),
  KEY `idx_ccci_ws_kind` (`workspace_id`, `kind`, `create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='通话洞察(情绪/意图/提取/质检报告)';

-- ------------------------------------------------------------------
-- 呼叫中心 C5 路由  (source: backend/sql/agent_cc_c5.sql)
-- ------------------------------------------------------------------
DROP TABLE IF EXISTS `agent_cc_route_rule`;
CREATE TABLE IF NOT EXISTS `agent_cc_route_rule` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `workspace_id` BIGINT NOT NULL DEFAULT 1 COMMENT '归属空间',
  `rule_code` VARCHAR(64) NOT NULL COMMENT '规则编码,日志与排障用',
  `priority` INT NOT NULL DEFAULT 100 COMMENT '优先级,数值越小越先匹配',
  `match_type` VARCHAR(16) NOT NULL DEFAULT 'REGEX' COMMENT 'REGEX / LLM_ONLY',
  `pattern` VARCHAR(512) NULL COMMENT 'REGEX 正则;LLM_ONLY 留空',
  `intent_code` VARCHAR(64) NOT NULL COMMENT '命中后产出的意图码',
  `action` VARCHAR(32) NOT NULL DEFAULT 'MARK' COMMENT 'TRANSFER(转队列)/ANSWER(直接应答)/MARK(仅记录)',
  `action_arg` VARCHAR(128) NULL COMMENT 'TRANSFER=队列名;ANSWER=应答键',
  `phrase_whitelist` VARCHAR(512) NULL COMMENT '词组白名单,英文逗号分隔;非空时文本须至少出现其一',
  `max_match_length` INT NOT NULL DEFAULT 40 COMMENT '量词防护:命中片段上限(字符),超出视为过匹配',
  `enabled` CHAR(1) NOT NULL DEFAULT '0' COMMENT '0=启用 1=停用',
  `del_flag` BIGINT NOT NULL DEFAULT 0,
  `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP,
  `update_time` DATETIME NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_ccrr_code` (`workspace_id`, `rule_code`),
  KEY `idx_ccrr_prio` (`workspace_id`, `enabled`, `priority`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='分流规则(双引擎:正则优先,LLM 兜底)';

-- ------------------------------------------------------------------
-- 呼叫中心 C2/C3 表  (source: backend/sql/agent_cc_c2.sql, agent_cc_c3.sql)
-- 注: 这两张表此前只在增量脚本里,从未折进基线;2026-11-19 补齐
-- ------------------------------------------------------------------
DROP TABLE IF EXISTS `agent_cc_voice_no_interrupt`;
CREATE TABLE IF NOT EXISTS `agent_cc_voice_no_interrupt` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `word` VARCHAR(64) NOT NULL COMMENT '词组(如 嗯/哦/好的)',
  `match_mode` VARCHAR(16) NOT NULL DEFAULT 'exact' COMMENT 'exact=partial 全等 / fuzzy=partial 包含',
  `enabled` CHAR(1) NOT NULL DEFAULT '1',
  `workspace_id` BIGINT NOT NULL DEFAULT 1 COMMENT '归属空间,0=全局',
  `del_flag` BIGINT NOT NULL DEFAULT 0,
  `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP,
  `update_time` DATETIME NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_cc_noint_word` (`workspace_id`, `word`, `match_mode`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='不允许打断词组(C2 barge-in 豁免)';

DROP TABLE IF EXISTS `agent_cc_ivr_flow`;
CREATE TABLE IF NOT EXISTS `agent_cc_ivr_flow` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `route_code` VARCHAR(64) NOT NULL COMMENT '流程编码,渠道按此引用',
  `route_name` VARCHAR(128) NOT NULL COMMENT '流程名称',
  `workspace_id` BIGINT NOT NULL DEFAULT 1 COMMENT '归属空间',
  `process` TINYINT NOT NULL DEFAULT 1 COMMENT '1=呼入 2=呼出(对齐 SmartCall ivr_call_route)',
  `flow` LONGTEXT NOT NULL COMMENT '节点 JSON 数组,首节点 type=start;节点鉴别器 type',
  `status` CHAR(1) NOT NULL DEFAULT '0' COMMENT '0=启用 1=停用',
  `del_flag` BIGINT NOT NULL DEFAULT 0,
  `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP,
  `update_time` DATETIME NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_ivrflow_route` (`workspace_id`, `route_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='IVR 流程定义(C3 节点引擎)';
