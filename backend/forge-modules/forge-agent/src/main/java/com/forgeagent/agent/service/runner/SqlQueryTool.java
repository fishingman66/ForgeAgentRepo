package com.forgeagent.agent.service.runner;

import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.message.ToolResultBlock;
import lombok.extern.slf4j.Slf4j;
import com.forgeagent.agent.domain.AgentDatasource;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 问数 SQL 工具的 AgentScope 适配器
 *
 * <p>把绑定数据源的 sql 类型工具包装为内核 AgentTool:模型传入自然语言问题生成的
 * SELECT 语句,经 {@link SqlQueryService} 安全校验后只读执行,结果集以 JSON 返回给
 * 模型组织回答;执行明细(含行列数据)同步写入轮级收集器,随助手消息 extJson
 * 落库供前端渲染表格与图表。</p>
 *
 * @author ForgeAgent
 */
@Slf4j
record SqlQueryTool(ToolAssembler.ToolDef def, AgentDatasource datasource,
                    SqlQueryService sqlQueryService, List<Map<String, Object>> collector) implements AgentTool {

    /**
     * 前端展示用的行数上限(完整数据仍返回给模型)
     */
    private static final int UI_MAX_ROWS = 100;

    /**
     * 给模型的 Markdown 表格行数上限:控制上下文占用,模型可按需分页查询
     */
    private static final int MODEL_MAX_ROWS = 100;

    /**
     * 展示单元格字符上限:避免单个大文本字段把消息 ext_json 撑到过大
     */
    private static final int UI_MAX_CELL = 300;

    @Override
    public String getName() {
        return def.name();
    }

    @Override
    public String getDescription() {
        String desc = def.description() == null ? "" : def.description();
        return desc + " (对数据源[" + datasource.getName() + "]执行只读 SQL 查询,仅允许 SELECT;"
            + "生成 SQL 前请先用 " + def.name() + "_schema 工具确认所用表的字段)";
    }

    @Override
    public Map<String, Object> getParameters() {
        Map<String, Object> sqlProp = new LinkedHashMap<>();
        sqlProp.put("type", "string");
        sqlProp.put("description", "要执行的只读 SQL 查询语句(仅 SELECT,系统会自动强制行数上限)。返回结果会以 Markdown 表格形式给出,请直接把表格呈现给用户,可在表格前后补充必要的解读。回答必须仅基于查询结果,不要推算、估算或编造未查询到的数据。");
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", Map.of("sql", sqlProp));
        schema.put("required", List.of("sql"));
        return schema;
    }

    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        Map<String, Object> input = param.getInput() == null ? Map.of() : param.getInput();
        return Mono.fromCallable(() -> execute(input)).subscribeOn(Schedulers.boundedElastic());
    }

    private ToolResultBlock execute(Map<String, Object> input) {
        Object raw = input.get("sql");
        String sql = raw == null ? "" : String.valueOf(raw);
        try {
            SqlQueryService.SqlResult result = sqlQueryService.execute(datasource, sql);
            recordSuccess(result);
            // 给模型返回 Markdown 表格,便于直接在回复中友好展示;结构化数据已写入收集器供前端图表渲染
            return ToolResultBlock.text(toMarkdownTable(result));
        } catch (Exception e) {
            String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            recordFailure(sql, message);
            log.warn("[SqlQueryTool] {} 执行失败: {}", def.name(), message);
            // 区分常见失败类型给出具体修正指引,引导模型先调 describe 再重试
            String hint = buildFailureHint(message);
            return ToolResultBlock.error("SQL 执行失败: " + message + hint);
        }
    }

    /**
     * 把查询结果格式化为 Markdown 表格(含表头、分隔行、数据行,过长数据截断)
     */
    private String toMarkdownTable(SqlQueryService.SqlResult result) {
        List<String> columns = result.columns();
        StringBuilder sb = new StringBuilder();
        sb.append("数据源[").append(datasource.getName()).append("]查询成功,共 ")
            .append(result.rowCount()).append(" 行,耗时 ").append(result.elapsedMs()).append("ms:\n\n");
        if (columns.isEmpty() || result.rows().isEmpty()) {
            sb.append("查询结果为空。注意:这不代表\"真的没有数据\",也可能是 WHERE 取值与库中实际存储不一致")
                .append("(如\"北京市\"与\"北京\"),或时间范围有偏差。\n");
            sb.append("请勿直接断言没有数据;先用 SELECT DISTINCT 核对相关列的实际取值,")
                .append("修正过滤条件后重新查询,再如实向用户说明结论。");
            return sb.toString();
        }
        // 表头
        sb.append('|').append(String.join(" | ", columns)).append(" |\n");
        sb.append('|');
        for (int i = 0; i < columns.size(); i++) {
            sb.append(" --- |");
        }
        sb.append('\n');
        int shown = Math.min(result.rows().size(), MODEL_MAX_ROWS);
        for (int r = 0; r < shown; r++) {
            sb.append('|');
            List<Object> row = result.rows().get(r);
            for (int c = 0; c < columns.size(); c++) {
                sb.append(' ').append(escapeCell(c < row.size() ? row.get(c) : null)).append(" |");
            }
            sb.append('\n');
        }
        if (result.rowCount() > MODEL_MAX_ROWS) {
            sb.append("\n以上仅展示前 ").append(MODEL_MAX_ROWS).append(" 行,共 ").append(result.rowCount())
                .append(" 行。如需更多数据请使用 LIMIT/OFFSET 分页查询。");
        }
        return sb.toString();
    }

    /**
     * Markdown 单元格转义:把 | 和换行替换掉,长字符串截断
     */
    private static String escapeCell(Object value) {
        if (value == null) {
            return "";
        }
        String s = String.valueOf(value);
        if (s.length() > UI_MAX_CELL) {
            s = s.substring(0, UI_MAX_CELL) + "…";
        }
        return s.replace("|", "\\|").replace("\r", " ").replace("\n", "<br>");
    }

    /**
     * 执行明细写入轮级收集器(含前端渲染所需的列名与截断后的行数据)
     */
    private void recordSuccess(SqlQueryService.SqlResult result) {
        if (collector == null) {
            return;
        }
        Map<String, Object> entry = baseEntry(result.normalizedSql());
        entry.put("columns", result.columns());
        List<List<Object>> uiRows = result.rows().size() > UI_MAX_ROWS
            ? result.rows().subList(0, UI_MAX_ROWS) : result.rows();
        entry.put("rows", uiRows.stream().map(SqlQueryTool::capRow).toList());
        entry.put("rowCount", result.rowCount());
        entry.put("rowCapped", result.rows().size() > UI_MAX_ROWS);
        entry.put("truncated", result.truncated());
        entry.put("elapsedMs", result.elapsedMs());
        entry.put("success", true);
        collector.add(entry);
    }

    private void recordFailure(String sql, String error) {
        if (collector == null) {
            return;
        }
        Map<String, Object> entry = baseEntry(sql);
        entry.put("rowCount", 0);
        entry.put("elapsedMs", 0);
        entry.put("success", false);
        entry.put("error", error);
        collector.add(entry);
    }

    private Map<String, Object> baseEntry(String sql) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("tool", def.name());
        entry.put("datasourceId", def.datasourceId());
        entry.put("datasource", datasource.getName());
        entry.put("sql", sql != null && sql.length() > 2000 ? sql.substring(0, 2000) : sql);
        return entry;
    }

    /**
     * 根据错误消息给出具体修正指引,让模型更有效地自我纠错
     */
    private String buildFailureHint(String msg) {
        if (msg == null) {
            return "。请重新调用 _schema 工具确认表/字段名后再试,注意仅允许只读 SELECT 查询。";
        }
        String m = msg.toLowerCase();
        if (m.contains("doesn't exist") || m.contains("不存在") || m.contains("unknown column") || m.contains("unknown table")) {
            return "。表名或字段名可能拼错,请先调用 " + def.name() + "_schema 工具查看相关表的真实字段,再重试。";
        }
        if (m.contains("不在可查询范围内") || m.contains("whitelist") || m.contains("forbidden")) {
            return "。该表不在可查询白名单内,请改用已存在的表。";
        }
        if (m.contains("被禁止的关键字") || m.contains("仅允许")) {
            return "。注意只写单条 SELECT/WITH 查询,禁止写注释、DDL/DML 关键字。";
        }
        if (m.contains("预估扫描") || m.contains("超过安全上限")) {
            return "。预估扫描行数过多,请增加 WHERE 条件缩小范围或改用聚合查询。";
        }
        if (m.contains("timeout") || m.contains("超时")) {
            return "。查询超时,请优化 SQL 或增加过滤条件。";
        }
        return "。请重新调用 " + def.name() + "_schema 工具确认表/字段名后再试,注意仅允许只读 SELECT 查询。";
    }

    /**
     * 展示行:截断过长的字符串单元格
     */
    private static List<Object> capRow(List<Object> row) {
        return row.stream().map(cell -> {
            if (cell instanceof String s && s.length() > UI_MAX_CELL) {
                return s.substring(0, UI_MAX_CELL) + "…";
            }
            return cell;
        }).toList();
    }
}
