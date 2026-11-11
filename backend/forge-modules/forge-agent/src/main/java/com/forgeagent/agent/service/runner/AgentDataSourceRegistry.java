package com.forgeagent.agent.service.runner;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import lombok.extern.slf4j.Slf4j;
import com.forgeagent.agent.domain.AgentDatasource;
import com.forgeagent.common.core.utils.StringUtils;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 问数数据源连接池注册表
 *
 * <p>按数据源ID缓存独立的 HikariCP 连接池(小池化:单池 2 连接),
 * 数据源配置变更/删除时调用 {@link #evict} 重建;连接串强制携带只读安全参数,
 * 用户自定义参数不允许覆盖。</p>
 *
 * @author ForgeAgent
 */
@Slf4j
@Component
public class AgentDataSourceRegistry {

    /**
     * 安全关键 JDBC 参数,禁止用户自定义覆盖
     */
    private static final Set<String> PROTECTED_PARAMS = Set.of(
        "allowmultiqueries", "useunicode", "characterencoding", "usessl",
        "servertimezone", "connecttimeout", "sockettimeout", "databaseprefix");

    private final Map<Long, HikariDataSource> pools = new ConcurrentHashMap<>();

    private final DatasourceCipher cipher;

    public AgentDataSourceRegistry(DatasourceCipher cipher) {
        this.cipher = cipher;
    }

    /**
     * 获取(或创建)数据源连接池
     */
    public HikariDataSource get(AgentDatasource ds) {
        return pools.computeIfAbsent(ds.getId(), id -> build(ds));
    }

    /**
     * 关闭并移除数据源连接池(配置变更/删除时调用)
     */
    public void evict(Long id) {
        if (id == null) {
            return;
        }
        HikariDataSource old = pools.remove(id);
        if (old != null) {
            log.info("[DataSourceRegistry] 数据源 {} 连接池已关闭重建", id);
            old.close();
        }
    }

    /**
     * 构建数据源 JDBC 连接串(强制只读安全参数)
     */
    public static String buildJdbcUrl(AgentDatasource ds) {
        int timeout = ds.getTimeoutSeconds() == null || ds.getTimeoutSeconds() <= 0 ? 15 : ds.getTimeoutSeconds();
        StringBuilder sb = new StringBuilder("jdbc:mysql://")
            .append(ds.getHost()).append(':').append(ds.getPort()).append('/').append(ds.getDbName())
            .append("?useUnicode=true&characterEncoding=utf8&useSSL=false&serverTimezone=Asia/Shanghai")
            .append("&connectTimeout=5000&socketTimeout=").append(timeout * 1000 + 5000)
            .append("&allowMultiQueries=false&allowPublicKeyRetrieval=true&nullCatalogMeansCurrent=true");
        if (StringUtils.isNotBlank(ds.getParams())) {
            for (String pair : ds.getParams().split("&")) {
                if (StringUtils.isBlank(pair) || !pair.contains("=")) {
                    continue;
                }
                String key = pair.substring(0, pair.indexOf('=')).trim().toLowerCase();
                if (PROTECTED_PARAMS.contains(key)) {
                    continue;
                }
                sb.append('&').append(pair.trim());
            }
        }
        return sb.toString();
    }

    private HikariDataSource build(AgentDatasource ds) {
        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl(buildJdbcUrl(ds));
        cfg.setUsername(ds.getUsername());
        // 库内存的是密文,建池前解密(历史明文兼容)
        cfg.setPassword(cipher.decrypt(ds.getPassword()));
        cfg.setMaximumPoolSize(2);
        cfg.setMinimumIdle(0);
        cfg.setPoolName("agent-ds-" + ds.getId());
        cfg.setConnectionTimeout(10_000);
        cfg.setIdleTimeout(60_000);
        cfg.setMaxLifetime(300_000);
        return new HikariDataSource(cfg);
    }
}
