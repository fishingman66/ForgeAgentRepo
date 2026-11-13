package com.forgeagent.agent.service.runner;

import com.forgeagent.agent.config.AgentRuntimeProperties;
import com.forgeagent.agent.domain.AgentDatasource;
import com.forgeagent.common.core.exception.ServiceException;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SqlQueryService 标识符安全单测:锁定 sampleDistinctValues 的三重防线
 * (字符级校验 -> 表白名单 -> information_schema 列实存校验),
 * 防止列名/表名拼接 SQL 的注入面回退。
 */
@Tag("dev")
@ExtendWith(MockitoExtension.class)
class SqlQueryServiceIdentifierTest {

    @Mock
    private AgentDataSourceRegistry registry;

    private SqlQueryService service;

    @BeforeEach
    void setUp() {
        service = new SqlQueryService(registry, new AgentRuntimeProperties());
    }

    private AgentDatasource ds(String whitelist) {
        AgentDatasource ds = new AgentDatasource();
        ds.setDbName("demo_db");
        ds.setMaxRows(100);
        ds.setTimeoutSeconds(10);
        ds.setTableWhitelist(whitelist);
        return ds;
    }

    private Connection mockConnection() throws Exception {
        HikariDataSource pool = mock(HikariDataSource.class);
        Connection conn = mock(Connection.class);
        when(pool.getConnection()).thenReturn(conn);
        when(registry.get(org.mockito.ArgumentMatchers.any())).thenReturn(pool);
        return conn;
    }

    @Test
    void 非法标识符直接拒绝且不触库() {
        AgentDatasource ds = ds("");
        // 反引号/空白/括号均在字符级防线被拒
        assertThrows(ServiceException.class,
            () -> service.sampleDistinctValues(ds, "orders; DROP TABLE x", "id", 10));
        assertThrows(ServiceException.class,
            () -> service.sampleDistinctValues(ds, "orders", "id) UNION SELECT 1", 10));
        assertThrows(ServiceException.class,
            () -> service.sampleDistinctValues(ds, "`orders`", "id", 10));
        assertThrows(ServiceException.class,
            () -> service.sampleDistinctValues(ds, "orders", null, 10));
        verify(registry, never()).get(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void 表不在白名单拒绝() throws Exception {
        Connection conn = mockConnection();
        AgentDatasource ds = ds("agent_info,sys_user");
        ServiceException ex = assertThrows(ServiceException.class,
            () -> service.sampleDistinctValues(ds, "secret_table", "id", 10));
        assertTrue(ex.getMessage().contains("不在可查询范围内"));
        // 白名单在校验阶段即拒绝,不走到 information_schema 实存查询
        verify(conn, never()).prepareStatement(contains("information_schema"));
    }

    @Test
    void 列不存在时拒绝拼接执行() throws Exception {
        Connection conn = mockConnection();
        PreparedStatement ps = mock(PreparedStatement.class);
        ResultSet rs = mock(ResultSet.class);
        when(conn.prepareStatement(contains("information_schema.columns"))).thenReturn(ps);
        when(ps.executeQuery()).thenReturn(rs);
        when(rs.next()).thenReturn(false);

        ServiceException ex = assertThrows(ServiceException.class,
            () -> service.sampleDistinctValues(ds(""), "agent_info", "not_a_column", 10));
        assertTrue(ex.getMessage().contains("不存在或不含列"));
        // 确认没有把不存在的列拼进采样 SQL 执行
        verify(conn, never()).createStatement();
    }

    @Test
    void 列存在时正常采样() throws Exception {
        Connection conn = mockConnection();
        PreparedStatement ps = mock(PreparedStatement.class);
        ResultSet checkRs = mock(ResultSet.class);
        when(conn.prepareStatement(contains("information_schema.columns"))).thenReturn(ps);
        when(ps.executeQuery()).thenReturn(checkRs);
        when(checkRs.next()).thenReturn(true);

        Statement st = mock(Statement.class);
        ResultSet dataRs = mock(ResultSet.class);
        when(conn.createStatement()).thenReturn(st);
        when(st.executeQuery(anyString())).thenReturn(dataRs);
        when(dataRs.next()).thenReturn(true, true, false);
        when(dataRs.getObject(1)).thenReturn("A", "B");

        List<String> values = service.sampleDistinctValues(ds(""), "agent_info", "status", 10);
        assertEquals(List.of("A", "B"), values);
        // 拼接 SQL 中 LIMIT 上限为数值常量,不可被外部输入污染
        verify(st).executeQuery(contains("SELECT DISTINCT status FROM agent_info"));
    }
}
