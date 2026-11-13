package com.forgeagent.agent.service.runner;

import com.zaxxer.hikari.HikariDataSource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.util.TablesNamesFinder;
import com.forgeagent.agent.config.AgentRuntimeProperties;
import com.forgeagent.agent.domain.AgentDatasource;
import com.forgeagent.common.core.exception.ServiceException;
import com.forgeagent.common.core.utils.StringUtils;
import com.forgeagent.common.json.utils.JsonUtils;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 问数 SQL 安全校验与只读执行器
 *
 * <p>安全约束:仅放行单条 SELECT/WITH 只读查询,禁止注释与危险关键字,
 * 强制行数上限(缺 LIMIT 或超限时包一层派生表截断),语句级超时,
 * 连接强制 read-only,走数据源注册表的独立连接池。</p>
 *
 * @author ForgeAgent
 */
@Slf4j
@RequiredArgsConstructor
@Component
public class SqlQueryService {

    /**
     * 单次查询执行结果
     */
    public record SqlResult(List<String> columns, List<List<Object>> rows,
                            int rowCount, boolean truncated, long elapsedMs, String normalizedSql) {
    }

    /**
     * 单表结构明细(供 describe 工具按需取用)
     */
    public record TableSchema(String name, String comment, String columnsText) {
    }

    /**
     * 表结构快照产物:文本快照(提示词注入用) + 逐表明细(describe 工具用)
     */
    public record SchemaData(String snapshotText, List<TableSchema> tables) {
    }

    /**
     * 危险关键字黑名单(词边界匹配,created_at 等字段名不受影响)
     */
    private static final Pattern FORBIDDEN = Pattern.compile(
        "(?i)\\b(insert|update|delete|merge|replace|drop|alter|create|truncate|rename|grant|revoke"
            + "|call|exec|execute|prepare|deallocate|set|use|lock|unlock|kill|shutdown|load"
            + "|outfile|dumpfile|handler|sleep|benchmark|analyse|procedure"
            + "|for\\s+update|lock\\s+in\\s+share\\s+mode)\\b");

    /**
     * 顶层尾部 LIMIT 识别(LIMIT n / LIMIT o,n / LIMIT n OFFSET o)
     */
    private static final Pattern LIMIT_TAIL = Pattern.compile(
        "(?i)\\blimit\\s+(\\d+)\\s*(?:,\\s*(\\d+)\\s*|offset\\s+(\\d+)\\s*)?$");

    private static final int MAX_TABLES = 200;
    private static final int MAX_COLUMNS_PER_TABLE = 100;
    private static final int MAX_SNAPSHOT_CHARS = 150_000;

    private final AgentDataSourceRegistry registry;
    private final AgentRuntimeProperties runtimeProperties;

    /**
     * 校验并规范化只读 SQL:超限/缺失 LIMIT 时包一层派生表强制截断
     */
    public String normalize(String raw, int maxRows) {
        String sql = raw == null ? "" : raw.trim();
        if (sql.isEmpty()) {
            throw new ServiceException("SQL 不能为空");
        }
        if (sql.endsWith(";")) {
            sql = sql.substring(0, sql.length() - 1).trim();
        }
        if (sql.contains(";")) {
            throw new ServiceException("仅允许执行单条查询语句");
        }
        if (sql.contains("--") || sql.contains("#") || sql.contains("/*")) {
            throw new ServiceException("SQL 中不允许包含注释");
        }
        String head = sql.toLowerCase();
        if (!head.startsWith("select") && !head.startsWith("with")) {
            throw new ServiceException("仅允许 SELECT 只读查询");
        }
        if (FORBIDDEN.matcher(sql).find()) {
            throw new ServiceException("SQL 包含被禁止的关键字,仅允许只读 SELECT 查询");
        }
        Matcher m = LIMIT_TAIL.matcher(sql);
        if (m.find()) {
            String countStr = m.group(2) != null ? m.group(2) : m.group(1);
            int count;
            try {
                count = Integer.parseInt(countStr);
            } catch (NumberFormatException e) {
                count = Integer.MAX_VALUE;
            }
            if (count <= maxRows) {
                return sql;
            }
        }
        return "SELECT * FROM (" + sql + ") _sql_guard LIMIT " + maxRows;
    }

    /**
     * 在指定数据源上执行只读查询(白名单校验 + EXPLAIN 预检 + 语句超时 + 行数上限多重熔断)
     */
    public SqlResult execute(AgentDatasource ds, String rawSql) {
        int maxRows = ds.getMaxRows() == null || ds.getMaxRows() <= 0 ? 500 : ds.getMaxRows();
        int timeout = ds.getTimeoutSeconds() == null || ds.getTimeoutSeconds() <= 0 ? 15 : ds.getTimeoutSeconds();
        String sql = normalize(rawSql, maxRows);
        checkWhitelist(ds, sql);
        long start = System.currentTimeMillis();
        HikariDataSource pool = registry.get(ds);
        try (Connection conn = pool.getConnection()) {
            conn.setReadOnly(true);
            explainPrecheck(conn, sql, runtimeProperties.getSqlExplainMaxScanRows());
            try (Statement st = conn.createStatement()) {
                st.setQueryTimeout(timeout);
                st.setMaxRows(maxRows + 1);
                try (ResultSet rs = st.executeQuery(sql)) {
                    ResultSetMetaData md = rs.getMetaData();
                    int colCount = md.getColumnCount();
                    List<String> columns = new ArrayList<>(colCount);
                    for (int i = 1; i <= colCount; i++) {
                        columns.add(md.getColumnLabel(i));
                    }
                    List<List<Object>> rows = new ArrayList<>();
                    boolean truncated = false;
                    while (rs.next()) {
                        if (rows.size() >= maxRows) {
                            truncated = true;
                            break;
                        }
                        List<Object> row = new ArrayList<>(colCount);
                        for (int i = 1; i <= colCount; i++) {
                            row.add(normalizeValue(rs.getObject(i)));
                        }
                        rows.add(row);
                    }
                    return new SqlResult(columns, rows, rows.size(), truncated,
                        System.currentTimeMillis() - start, sql);
                }
            }
        } catch (ServiceException e) {
            throw e;
        } catch (Exception e) {
            throw new ServiceException("SQL 执行失败: " + rootMessage(e));
        }
    }

    /**
     * 采样指定列的 distinct 值(用于快速构建枚举值字典),最多 limit 条
     */
    public List<String> sampleDistinctValues(AgentDatasource ds, String tableName, String columnName, int limit) {
        // 表名/列名字符级校验:仅允许字母数字下划线,阻断反引号/空白/括号注入
        if (!isIdentifierSafe(tableName) || !isIdentifierSafe(columnName)) {
            throw new ServiceException("表名或列名不合法");
        }
        int cap = Math.max(1, Math.min(limit, 500));
        HikariDataSource pool = registry.get(ds);
        try (Connection conn = pool.getConnection()) {
            conn.setReadOnly(true);
            // 表名过白名单 + information_schema 实存校验:仅允许拼接真实存在的列,
            // 杜绝任意合法标识符拼接(白名单为空时实存校验仍是最后防线)
            checkWhitelist(ds, "SELECT 1 FROM " + tableName);
            validateColumnExists(conn, ds.getDbName(), tableName, columnName);
            String sql = "SELECT DISTINCT " + columnName + " FROM " + tableName
                + " WHERE " + columnName + " IS NOT NULL ORDER BY " + columnName + " LIMIT " + cap;
            List<String> values = new ArrayList<>();
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery(sql)) {
                while (rs.next()) {
                    Object v = rs.getObject(1);
                    if (v != null) {
                        values.add(String.valueOf(v));
                    }
                }
            }
            return values;
        } catch (ServiceException e) {
            throw e;
        } catch (Exception e) {
            throw new ServiceException("采样列值失败: " + rootMessage(e));
        }
    }

    /**
     * 校验标识符是否安全(仅允许字母数字下划线)
     */
    private boolean isIdentifierSafe(String name) {
        if (name == null || name.isEmpty()) {
            return false;
        }
        return name.matches("^[A-Za-z0-9_]+$");
    }

    /**
     * information_schema 列存在性校验:表必须存在于指定库且列属于该表,
     * 作为标识符拼接 SQL 的最终白名单(列名无法参数化,只能靠实存校验兜底)
     */
    private void validateColumnExists(Connection conn, String dbName, String tableName, String columnName) {
        try (PreparedStatement ps = conn.prepareStatement(
            "SELECT 1 FROM information_schema.columns "
                + "WHERE table_schema = ? AND table_name = ? AND column_name = ? LIMIT 1")) {
            ps.setString(1, dbName);
            ps.setString(2, tableName);
            ps.setString(3, columnName);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new ServiceException("表 " + tableName + " 不存在或不含列 " + columnName
                        + ",请确认表列名或重新同步结构");
                }
            }
        } catch (ServiceException e) {
            throw e;
        } catch (SQLException e) {
            throw new ServiceException("表列校验失败: " + rootMessage(e));
        }
    }

    /**
     * 测试数据源连通性,返回耗时毫秒
     */
    public long testConnection(AgentDatasource ds) {
        long start = System.currentTimeMillis();
        try (Connection conn = registry.get(ds).getConnection();
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT 1")) {
            rs.next();
            return System.currentTimeMillis() - start;
        } catch (Exception e) {
            throw new ServiceException("连接失败: " + rootMessage(e));
        }
    }

    /**
     * 内省 information_schema 生成表结构快照(表注释/列类型/列注释),
     * 供问数提示词注入与 describe 工具按需取用;按表白名单过滤,
     * 上限 200 表、每表 100 列、总量 150K 字符
     */
    public SchemaData buildSchemaData(AgentDatasource ds) {
        Set<String> whitelist = parseWhitelist(ds.getTableWhitelist());
        try (Connection conn = registry.get(ds).getConnection()) {
            List<String> tables = new ArrayList<>();
            Map<String, String> tableComments = new LinkedHashMap<>();
            try (PreparedStatement ps = conn.prepareStatement(
                "SELECT table_name, table_comment FROM information_schema.tables "
                    + "WHERE table_schema = ? AND table_type = 'BASE TABLE' ORDER BY table_name")) {
                ps.setString(1, ds.getDbName());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next() && tables.size() < MAX_TABLES) {
                        String table = rs.getString(1);
                        // 白名单非空时仅采集名单内表
                        if (!whitelist.isEmpty() && !whitelist.contains(table.toLowerCase())) {
                            continue;
                        }
                        tables.add(table);
                        tableComments.put(table, rs.getString(2));
                    }
                }
            }
            if (tables.isEmpty()) {
                throw new ServiceException("未读取到任何表,请检查白名单配置、账号权限与库名");
            }
            Map<String, StringBuilder> columnsByTable = new LinkedHashMap<>();
            try (PreparedStatement ps = conn.prepareStatement(
                "SELECT table_name, column_name, column_type, is_nullable, column_key, column_comment "
                    + "FROM information_schema.columns WHERE table_schema = ? ORDER BY table_name, ordinal_position")) {
                ps.setString(1, ds.getDbName());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        String table = rs.getString(1);
                        if (!tableComments.containsKey(table)) {
                            continue;
                        }
                        StringBuilder sb = columnsByTable.computeIfAbsent(table, k -> new StringBuilder());
                        if (sb.length() > 0) {
                            sb.append('\n');
                        }
                        if (sb.chars().filter(c -> c == '\n').count() >= MAX_COLUMNS_PER_TABLE) {
                            continue;
                        }
                        sb.append("  ").append(rs.getString(2)).append(' ').append(rs.getString(3))
                            .append("YES".equalsIgnoreCase(rs.getString(4)) ? " NULL" : " NOT NULL");
                        String key = rs.getString(5);
                        if ("PRI".equalsIgnoreCase(key)) {
                            sb.append(" PK");
                        }
                        String comment = rs.getString(6);
                        if (comment != null && !comment.isBlank()) {
                            sb.append(" -- ").append(comment.replace('\n', ' '));
                        }
                    }
                }
            }
            StringBuilder snapshot = new StringBuilder();
            List<TableSchema> tableSchemas = new ArrayList<>(tables.size());
            for (String table : tables) {
                StringBuilder head = new StringBuilder("表 ").append(table);
                String comment = tableComments.get(table);
                if (comment != null && !comment.isBlank()) {
                    head.append(" -- ").append(comment.replace('\n', ' '));
                }
                snapshot.append(head).append('\n');
                StringBuilder cols = columnsByTable.get(table);
                String columnsText = cols == null ? "" : cols.toString();
                if (!columnsText.isEmpty()) {
                    snapshot.append(columnsText).append('\n');
                }
                tableSchemas.add(new TableSchema(table, comment == null ? "" : comment, columnsText));
                if (snapshot.length() > MAX_SNAPSHOT_CHARS) {
                    snapshot.append("...(表结构过多已截断,请缩小库范围)\n");
                    break;
                }
            }
            return new SchemaData(snapshot.toString().trim(), tableSchemas);
        } catch (ServiceException e) {
            throw e;
        } catch (SQLException e) {
            throw new ServiceException("读取表结构失败: " + e.getMessage());
        }
    }

    /**
     * 按需返回指定表的字段明细(从已同步的 schemaTables JSON 读取,不实时查库);
     * 不存在的表逐个标注,输出按表拼接,最多 10 表、8000 字符
     */
    public String describeTables(AgentDatasource ds, List<String> names) {
        if (StringUtils.isBlank(ds.getSchemaTables())) {
            throw new ServiceException("该数据源尚未同步表结构,请先在数据源管理中执行「同步结构」");
        }
        List<TableSchema> tables = JsonUtils.parseArray(ds.getSchemaTables(), TableSchema.class);
        if (tables == null) {
            tables = List.of();
        }
        Set<String> whitelist = parseWhitelist(ds.getTableWhitelist());
        Map<String, TableSchema> byName = new LinkedHashMap<>();
        for (TableSchema t : tables) {
            byName.put(t.name().toLowerCase(), t);
        }
        StringBuilder sb = new StringBuilder();
        sb.append("数据源[").append(ds.getName()).append("]表结构明细:\n");
        int limit = Math.min(names.size(), 10);
        for (int i = 0; i < limit; i++) {
            String name = names.get(i);
            if (name == null || name.isBlank()) {
                continue;
            }
            String key = name.trim().replace("`", "").toLowerCase();
            // 取最后一个 '.' 后的部分,兼容 db.table 写法
            int dot = key.lastIndexOf('.');
            if (dot >= 0) {
                key = key.substring(dot + 1);
            }
            sb.append('\n');
            if (!whitelist.isEmpty() && !whitelist.contains(key)) {
                sb.append("表 ").append(key).append(" 不在可查询范围内,无权查看字段明细\n");
                continue;
            }
            TableSchema schema = byName.get(key);
            if (schema == null) {
                sb.append("表 ").append(key).append(" 不存在或尚未同步,请确认表名或重新同步结构\n");
                continue;
            }
            sb.append("表 ").append(schema.name());
            if (StringUtils.isNotBlank(schema.comment())) {
                sb.append(" -- ").append(schema.comment());
            }
            sb.append('\n');
            if (StringUtils.isNotBlank(schema.columnsText())) {
                sb.append(schema.columnsText()).append('\n');
            }
            if (sb.length() > 8000) {
                sb.append("...(输出过长已截断)");
                break;
            }
        }
        return sb.toString();
    }

    /**
     * 表级白名单校验:JSqlParser 提取 SQL 中所有表名,任一不在名单内即拒绝
     */
    private void checkWhitelist(AgentDatasource ds, String sql) {
        Set<String> whitelist = parseWhitelist(ds.getTableWhitelist());
        if (whitelist.isEmpty()) {
            return;
        }
        Set<String> usedTables;
        try {
            // java.sql.Statement 同名冲突,此处用全限定名
            net.sf.jsqlparser.statement.Statement stmt = CCJSqlParserUtil.parse(sql);
            usedTables = new TablesNamesFinder().getTables(stmt);
        } catch (Exception e) {
            throw new ServiceException("SQL 解析失败,请检查语法");
        }
        List<String> denied = new ArrayList<>();
        if (usedTables != null) {
            for (String table : usedTables) {
                String key = table.replace("`", "");
                int dot = key.lastIndexOf('.');
                if (dot >= 0) {
                    key = key.substring(dot + 1);
                }
                if (!whitelist.contains(key.toLowerCase())) {
                    denied.add(table);
                }
            }
        }
        if (!denied.isEmpty()) {
            throw new ServiceException("以下表不在可查询范围内: " + String.join(", ", denied));
        }
    }

    /**
     * EXPLAIN 预检:累加预估扫描行数,超阈值熔断;
     * EXPLAIN 失败说明表/列不存在等错误,提前暴露给模型重试
     */
    private void explainPrecheck(Connection conn, String sql, long maxScanRows) {
        if (maxScanRows <= 0) {
            return;
        }
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("EXPLAIN " + sql)) {
            ResultSetMetaData md = rs.getMetaData();
            int rowsIdx = -1;
            for (int i = 1; i <= md.getColumnCount(); i++) {
                if ("rows".equalsIgnoreCase(md.getColumnLabel(i))) {
                    rowsIdx = i;
                    break;
                }
            }
            if (rowsIdx < 0) {
                return;
            }
            long total = 0;
            while (rs.next()) {
                long rows = rs.getLong(rowsIdx);
                if (!rs.wasNull()) {
                    total += rows;
                }
            }
            if (total > maxScanRows) {
                throw new ServiceException("预估扫描行数约 " + total + ",超过安全上限 " + maxScanRows
                    + ",请增加过滤条件或改用聚合查询");
            }
        } catch (ServiceException e) {
            throw e;
        } catch (Exception e) {
            throw new ServiceException("SQL 预检失败: " + rootMessage(e));
        }
    }

    /**
     * 解析表白名单:逗号分隔,转小写集合;空串返回空集合表示不限制
     */
    private Set<String> parseWhitelist(String whitelist) {
        Set<String> set = new LinkedHashSet<>();
        if (StringUtils.isBlank(whitelist)) {
            return set;
        }
        for (String item : whitelist.split(",")) {
            String name = item.trim().replace("`", "").toLowerCase();
            if (!name.isEmpty()) {
                int dot = name.lastIndexOf('.');
                set.add(dot >= 0 ? name.substring(dot + 1) : name);
            }
        }
        return set;
    }

    /**
     * 结果值归一化:二进制转占位说明,避免 JSON 序列化异常
     */
    private Object normalizeValue(Object value) {
        if (value instanceof byte[] bytes) {
            return "(binary " + bytes.length + " bytes)";
        }
        return value;
    }

    private String rootMessage(Throwable e) {
        Throwable cur = e;
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        return cur.getMessage() == null ? cur.getClass().getSimpleName() : cur.getMessage();
    }
}
