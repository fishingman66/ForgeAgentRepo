-- ForgeAgent 演示环境种子数据
-- 包含: 演示账号(demo/Demo@123456)、演示空间、示例 Agent、自指式只读演示数据源
-- 仅首次初始化执行; 如不需要演示数据可删除本文件后重建 mysql 数据卷
SET NAMES utf8mb4;

-- 智能问数演示专用只读账号(仅允许 SELECT 平台自身业务表)
CREATE USER IF NOT EXISTS 'forge_demo'@'%' IDENTIFIED BY 'ForgeDemo@2026';
-- 库名按当前库动态取:compose 的 MYSQL_DATABASE 改过也不会漏授权(原先硬编码 `forge-vue`)
SET @demo_db = DATABASE();
SET @demo_grant = CONCAT('GRANT SELECT ON `', @demo_db, '`.* TO ''forge_demo''@''%''');
PREPARE demo_stmt FROM @demo_grant;
EXECUTE demo_stmt;
DEALLOCATE PREPARE demo_stmt;
FLUSH PRIVILEGES;

-- 幂等清理(供定时重置脚本复用)
DELETE FROM sys_user_role WHERE user_id = 1900000000000000001;
DELETE FROM sys_user WHERE user_id = 1900000000000000001;
DELETE FROM agent_workspace_member WHERE workspace_id = 1900000000000000101;
DELETE FROM agent_workspace WHERE id = 1900000000000000101;
DELETE FROM agent_info WHERE id IN (1900000000000001001, 1900000000000001002, 1900000000000001003, 1900000000000001004);
DELETE FROM agent_datasource_example WHERE datasource_id = 1900000000000002001;
DELETE FROM agent_datasource WHERE id = 1900000000000002001;

-- 演示账号: demo / Demo@123456 (角色: 普通用户)
INSERT INTO `sys_user` (`user_id`, `dept_id`, `user_name`, `nick_name`, `user_type`, `email`, `phone_number`, `gender`, `avatar`, `password`, `status`, `del_flag`, `create_dept`, `create_by`, `create_time`, `remark`)
VALUES (1900000000000000001, 1761000000000000103, 'demo', '演示用户', 'sys_user', '', '', '2', NULL, '$2a$10$oHWoCMYVjtYxuCLcBf0gweFOt1WBmETuUnrTxxuFSe0uE0t5NZ2QO', '0', '0', 1761000000000000103, 1761100000000000001, NOW(), '在线演示账号');
INSERT INTO `sys_user_role` (`user_id`, `role_id`) VALUES (1900000000000000001, 2111749363962757122);

-- 演示空间
INSERT INTO `agent_workspace` (`id`, `name`, `description`, `status`, `owner_id`, `dept_id`, `create_dept`, `create_by`, `create_time`)
VALUES (1900000000000000101, '演示空间', '在线演示环境预置空间，每日定时重置', '0', 1900000000000000001, 1761000000000000103, 1761000000000000103, 1900000000000000001, NOW());
INSERT INTO `agent_workspace_member` (`id`, `workspace_id`, `user_id`, `role`, `create_by`, `create_time`)
VALUES (1900000000000000102, 1900000000000000101, 1900000000000000001, 'admin', 1900000000000000001, NOW());

-- 示例 Agent 1: 通用助手
INSERT INTO `agent_info` (`id`, `workspace_id`, `name`, `description`, `system_prompt`, `welcome_msg`, `suggested_questions`, `model_id`, `temperature`, `max_tokens`, `team_mode`, `memory_enabled`, `status`, `create_dept`, `create_by`, `create_time`)
VALUES (1900000000000001001, 1900000000000000101, '通用助手', '全功能对话助手，体验流式输出与 Markdown 渲染', '你是 ForgeAgent 演示助手，回答简洁清晰，善用 Markdown 格式（标题、列表、代码块）组织内容。', '你好！我是 ForgeAgent 演示助手，点击下方推荐问题或直接输入开始体验。', '["用 Markdown 表格对比 RAG 和微调的区别", "写一段 Java 快速排序并解释", "介绍一下企业级智能体平台的核心能力"]', NULL, 0.70, 4096, 'none', '1', '0', 1761000000000000103, 1900000000000000001, NOW());

-- 示例 Agent 2: 平台运营问数(绑定自指演示数据源)
INSERT INTO `agent_info` (`id`, `workspace_id`, `name`, `description`, `system_prompt`, `welcome_msg`, `suggested_questions`, `model_id`, `temperature`, `max_tokens`, `team_mode`, `memory_enabled`, `status`, `create_dept`, `create_by`, `create_time`)
VALUES (1900000000000001002, 1900000000000000101, '平台运营问数', '自然语言查询平台运营数据(NL2SQL 演示)', '你是数据分析助手，通过 SQL 工具查询平台运营数据并回答。回答必须仅基于查询结果，不要编造数据。', '我可以查询平台自身的会话、消息、Token 用量等运营数据，试试下面的问题。', '["统计每天的会话数量", "各 Agent 的消息量排行", "最近一段时间的 Token 用量趋势"]', NULL, 0.10, 4096, 'none', '0', '0', 1761000000000000103, 1900000000000000001, NOW());

-- 示例 Agent 3/4: 团队接力(文案 → 审校 → 定稿)
INSERT INTO `agent_info` (`id`, `workspace_id`, `name`, `description`, `system_prompt`, `welcome_msg`, `suggested_questions`, `model_id`, `temperature`, `max_tokens`, `team_mode`, `memory_enabled`, `status`, `create_dept`, `create_by`, `create_time`)
VALUES (1900000000000001003, 1900000000000000101, '文案写手', '团队接力成员：负责初稿撰写', '你是资深文案，根据主题撰写有感染力的初稿，结构清晰、语言生动。', '', NULL, NULL, 0.80, 4096, 'none', '0', '0', 1761000000000000103, 1900000000000000001, NOW());
INSERT INTO `agent_info` (`id`, `workspace_id`, `name`, `description`, `system_prompt`, `welcome_msg`, `suggested_questions`, `model_id`, `temperature`, `max_tokens`, `team_mode`, `memory_enabled`, `status`, `create_dept`, `create_by`, `create_time`)
VALUES (1900000000000001004, 1900000000000000101, '团队接力·内容创作', '写手初稿 → 主编审校 → 综合定稿，逐棒流式输出', '你是内容主编，作为团队接力的主 Agent：先由文案写手产出初稿，你再审校润色并综合定稿，输出最终成品。', '我是内容创作团队：文案写手先出初稿，我作为主编审校定稿。给我一个创作主题试试。', '["写一段 ForgeAgent 开源项目的公众号推文", "为一款智能水杯写电商详情页文案", "起草一封项目延期上线的用户致歉信"]', NULL, 0.70, 4096, 'sequential', '0', '0', 1761000000000000103, 1900000000000000001, NOW());
UPDATE `agent_info` SET `team_ids` = '1900000000000001003' WHERE id = 1900000000000001004;

-- 演示数据源: 指向平台自身 MySQL(只读账号 + 表白名单双重护栏)
-- 密码为 ForgeDemo@2026 经 agent.datasource.secret(默认 forgeDsSecret2026)AES 加密(ENC: 前缀)
-- 注意:该密文由出厂密钥加密;若你改了 agent.datasource.secret,
--      需在「数据源」页重新保存一次密码,否则智能问数演示会解密失败。
INSERT INTO `agent_datasource` (`id`, `name`, `description`, `db_type`, `host`, `port`, `db_name`, `username`, `password`, `params`, `max_rows`, `timeout_seconds`, `table_whitelist`, `semantic_enabled`, `semantic_version`, `status`, `create_dept`, `create_by`, `create_time`)
VALUES (1900000000000002001, '平台运营库(演示)', '平台自身业务库，体验智能问数', 'mysql', 'mysql', 3306, 'forge-vue', 'forge_demo', 'ENC:MSO066JPRzqo7cNPcEo8Bw==', 'useSSL=false&serverTimezone=GMT%2B8', 100, 15, 'agent_session,agent_message,agent_token_usage,agent_token_usage_daily,agent_info,agent_workspace', '0', 0, '0', 1761000000000000103, 1900000000000000001, NOW());

-- 问数 few-shot 示例
INSERT INTO `agent_datasource_example` (`id`, `datasource_id`, `question`, `sql_content`, `sort`, `status`, `create_dept`, `create_by`, `create_time`)
VALUES
(1900000000000002002, 1900000000000002001, '统计每天的会话数量', 'SELECT DATE(create_time) AS dt, COUNT(*) AS cnt FROM agent_session WHERE del_flag = 0 GROUP BY DATE(create_time) ORDER BY dt DESC LIMIT 30', 1, '0', 1761000000000000103, 1900000000000000001, NOW()),
(1900000000000002003, 1900000000000002001, '各 Agent 的消息量排行', 'SELECT a.name AS agent_name, COUNT(m.id) AS msg_cnt FROM agent_message m JOIN agent_session s ON m.session_id = s.id JOIN agent_info a ON s.agent_id = a.id WHERE m.del_flag = 0 GROUP BY a.name ORDER BY msg_cnt DESC LIMIT 10', 2, '0', 1761000000000000103, 1900000000000000001, NOW());
