package com.forgeagent.rag.mcp;

import java.util.Set;

/**
 * 08方案 / A5 增强:对外 MCP 调用方身份。
 *
 * <p>由 {@link McpClientAuthenticator} 解析出来后贯穿整次 tools/call,用于把
 * 「单 token 平台全量只读」收窄成「某客户端 + 某工作空间 + 某授权域」。
 * {@code workspaceId == null} 表示平台全量(与旧 {@code rag.knowledge.mcp-token} 等义)。</p>
 *
 * @param clientName  客户端显示名(仅用于日志与审计)
 * @param workspaceId 绑定工作空间,NULL=平台全量
 * @param scopes      授权域;空集=全部
 * @author ForgeAgent
 */
public record McpCaller(String clientName, Long workspaceId, Set<String> scopes) {

    /** 知识检索域 */
    public static final String SCOPE_KNOWLEDGE = "knowledge";
    /** 技能读取域 */
    public static final String SCOPE_SKILL = "skill";

    /** 旧单 token 等义的平台全量只读身份 */
    public static McpCaller platformWide(String clientName) {
        return new McpCaller(clientName, null, Set.of());
    }

    /** 是否被授予某个授权域(空集视为全部) */
    public boolean allows(String scope) {
        return scopes == null || scopes.isEmpty() || scopes.contains(scope);
    }

    /**
     * 能否读取目标工作空间的资源。
     * 平台共享资源({@code targetWorkspaceId == null})对所有调用方可见。
     */
    public boolean canReadWorkspace(Long targetWorkspaceId) {
        return workspaceId == null || targetWorkspaceId == null || workspaceId.equals(targetWorkspaceId);
    }
}
