package com.forgeagent.common.mcp.config;

import io.modelcontextprotocol.client.McpSyncClient;
import com.forgeagent.common.mcp.core.McpClientTemplate;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

import java.util.List;

/**
 * MCP 公共模块自动配置。
 *
 * @author Lion Li; 改造: 智造 ForgeAgent 2026-11
 */
@AutoConfiguration
public class McpAutoConfiguration {

    /**
     * MCP Client 通用操作模板。
     */
    @Bean
    @ConditionalOnBean(McpSyncClient.class)
    @ConditionalOnMissingBean
    public McpClientTemplate mcpClientTemplate(List<McpSyncClient> mcpSyncClients) {
        return new McpClientTemplate(mcpSyncClients);
    }
}
