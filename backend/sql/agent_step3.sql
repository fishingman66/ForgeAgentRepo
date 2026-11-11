-- ----------------------------
-- AgentScope 集成第三步:Token 预算中间件 + Docker 沙箱工具
-- 目标库:forge-vue
-- ----------------------------

-- 1. Agent 级 Token 累计预算(总 tokens 上限,空为不限)
ALTER TABLE `agent_info`
    ADD COLUMN `token_budget` bigint NULL COMMENT 'Token累计预算(总tokens上限,空为不限)' AFTER `max_tokens`;

-- 2. 工具表扩展 Docker 沙箱执行配置(仅 tool_type=sandbox 时生效)
ALTER TABLE `agent_tool`
    ADD COLUMN `sandbox_image` varchar(200) DEFAULT 'python:3.11-slim' COMMENT '沙箱镜像' AFTER `http_params`,
    ADD COLUMN `sandbox_timeout` int DEFAULT 30 COMMENT '沙箱执行超时(秒)' AFTER `sandbox_image`,
    ADD COLUMN `sandbox_memory` varchar(20) DEFAULT '128m' COMMENT '沙箱内存限制' AFTER `sandbox_timeout`,
    ADD COLUMN `sandbox_cpus` varchar(10) DEFAULT '0.5' COMMENT '沙箱CPU限制' AFTER `sandbox_memory`;

-- 3. 工具类型校验放宽(http/builtin/sandbox 由应用层控制,此处仅备注)
-- 4. 内建 Python 沙箱工具种子数据
INSERT INTO `agent_tool` (`name`, `description`, `tool_type`, `status`, `approval_mode`, `approval_timeout`, `remark`, `create_time`, `update_time`)
VALUES ('python_sandbox', '在隔离 Docker 容器中执行 Python 代码(无网络、内存/CPU受限、超时保护、输出截断),参数 code 为要执行的 Python 源码',
        'sandbox', '0', 'auto', 60, '内建沙箱工具:镜像 python:3.11-slim,无网络,默认内存128m/CPU0.5/超时30秒', now(), now());
