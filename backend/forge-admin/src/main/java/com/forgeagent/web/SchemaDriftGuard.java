package com.forgeagent.web;

import com.forgeagent.agent.mapper.SchemaIntrospectMapper;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 基线结构漂移启动自检：代码强依赖的列缺失时 fail-fast，避免跑到一半才抛隐晦的
 * SQLSyntaxErrorException（2026-11-23 实测：exec_state 缺失导致登录 403、
 * encoding_aes_key 缺失导致帮助中心全端点 500）。
 *
 * <p>检查项只列「缺了必炸」的列，均有运行时异常堆栈证据；其余漂移交给
 * scripts/sql/schema_drift_audit.py（只读审计）与 schema_drift_repair.py（幂等修复）覆盖。
 * 全量列/索引差异由 scripts/sql/schema_drift_audit.py 覆盖（flow_node.version 已于 2026-11-24 补齐）。</p>
 *
 * @author ForgeAgent
 */
@RequiredArgsConstructor
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class SchemaDriftGuard implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SchemaDriftGuard.class);

    private static final String BASELINE = "deploy/docker/mysql/init/01-schema.sql";
    private static final String REPAIR = "scripts/sql/schema_drift_repair.py";

    /** 强依赖列：表、列、缺失影响（均有 2026-11-23 实测异常堆栈证据） */
    private static final String[][] REQUIRED_COLUMNS = {
        {"agent_approval", "exec_state", "登录鉴权失败(实测 code:403)"},
        {"agent_cs_channel", "encoding_aes_key", "帮助中心全端点 500(实测)"},
        {"agent_workflow_run", "pending_deadline", "工作流 wait 节点超时判定异常(实测日志)"},
    };

    private final SchemaIntrospectMapper schemaMapper;

    @Override
    public void run(ApplicationArguments args) {
        StringBuilder missing = new StringBuilder();
        for (String[] item : REQUIRED_COLUMNS) {
            long count;
            try {
                count = schemaMapper.countColumn(item[0], item[1]);
            } catch (Exception e) {
                log.warn("[结构自检] 无法读取 information_schema，跳过漂移检查: {}", e.getMessage());
                return;
            }
            if (count == 0) {
                missing.append(String.format("  - %s.%s（%s）%n", item[0], item[1], item[2]));
            }
        }
        if (missing.length() == 0) {
            log.info("[结构自检] 强依赖列齐备（{} 项）", REQUIRED_COLUMNS.length);
            return;
        }
        String detail = missing.toString();
        log.error("[结构自检] 检测到基线结构漂移，以下列缺失且代码强依赖:\n{}"
                + "请按基线 {} 补齐，或执行修复脚本 {} --apply（先 --dry-run 核对）。",
            detail, BASELINE, REPAIR);
        throw new IllegalStateException(
            "[结构自检] 基线结构漂移，缺失代码强依赖列，拒绝启动。详见上方日志。");
    }
}
