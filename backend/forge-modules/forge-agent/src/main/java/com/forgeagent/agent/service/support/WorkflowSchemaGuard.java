package com.forgeagent.agent.service.support;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 工作流表结构启动自检(W4)。
 *
 * <p>工作流链路强依赖若干增量 SQL(版本快照表 / 运行实例表 / 审批表字段放开),
 * 漏执行时保存与试运行会在运行期才报隐晦的 SQL 异常,极易被当成「功能坏了」。
 * 启动时主动检查并给出脚本路径指引,只打 WARN 不阻断应用启动
 * (工作流不可用时对话等其他能力不受影响)。</p>
 *
 * @author ForgeAgent
 */
@RequiredArgsConstructor
@Component
public class WorkflowSchemaGuard implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(WorkflowSchemaGuard.class);

    private final JdbcTemplate jdbcTemplate;

    @Override
    public void run(ApplicationArguments args) {
        try {
            checkTable("agent_workflow_version", "backend/sql/agent_workflow_version.sql",
                "工作流保存(版本快照)将直接失败");
            checkTable("agent_workflow_run", "backend/sql/agent_step5.sql",
                "工作流试运行/触发器执行将直接失败");
            checkColumnNullable("agent_approval", "session_id", "backend/sql/agent_step5.sql",
                "含人工审批节点的工作流执行将失败(审批单写入被非空约束拒绝)");
        } catch (Exception e) {
            log.warn("[工作流自检] 表结构检查未完成(不影响应用启动): {}", e.getMessage());
        }
    }

    private void checkTable(String table, String script, String impact) {
        Long count = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = ?",
            Long.class, table);
        if (count == null || count == 0) {
            log.warn("[工作流自检] 缺少表 {},请先执行增量脚本 {}。影响: {}", table, script, impact);
        }
    }

    private void checkColumnNullable(String table, String column, String script, String impact) {
        Long count = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM information_schema.columns "
                + "WHERE table_schema = DATABASE() AND table_name = ? AND column_name = ? AND is_nullable = 'NO'",
            Long.class, table, column);
        if (count != null && count > 0) {
            log.warn("[工作流自检] 表 {}.{} 仍为 NOT NULL,请执行增量脚本 {} 放开非空约束。影响: {}",
                table, column, script, impact);
        }
    }
}
