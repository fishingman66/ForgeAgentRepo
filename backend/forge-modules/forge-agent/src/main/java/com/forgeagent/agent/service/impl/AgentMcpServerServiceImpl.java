package com.forgeagent.agent.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.agentscope.core.tool.mcp.McpClientBuilder;
import io.agentscope.core.tool.mcp.McpClientWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import com.forgeagent.agent.domain.AgentMcpServer;
import com.forgeagent.agent.domain.bo.AgentMcpServerBo;
import com.forgeagent.agent.domain.vo.AgentMcpServerVo;
import com.forgeagent.agent.mapper.AgentMcpServerMapper;
import com.forgeagent.agent.service.IAgentMcpServerService;
import com.forgeagent.agentcore.service.ResourceAccessGuard;
import com.forgeagent.common.core.domain.PageResult;
import com.forgeagent.common.core.exception.ServiceException;
import com.forgeagent.common.core.utils.MapstructUtils;
import com.forgeagent.common.core.utils.StringUtils;
import com.forgeagent.common.json.utils.JsonUtils;
import com.forgeagent.common.mybatis.core.page.PageQuery;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * MCP 服务器注册 Service 实现
 *
 * @author ForgeAgent
 */
@Slf4j
@RequiredArgsConstructor
@Service
public class AgentMcpServerServiceImpl implements IAgentMcpServerService {

    private static final Set<String> SERVER_TYPES = Set.of("sse", "streamable_http");

    private final AgentMcpServerMapper serverMapper;
    private final ResourceAccessGuard resourceGuard;

    @Override
    public PageResult<AgentMcpServerVo> queryPageList(AgentMcpServerBo bo, PageQuery pageQuery) {
        LambdaQueryWrapper<AgentMcpServer> wrapper = new LambdaQueryWrapper<AgentMcpServer>()
            .like(StringUtils.isNotBlank(bo.getKeyword()), AgentMcpServer::getName, bo.getKeyword())
            .eq(StringUtils.isNotBlank(bo.getStatus()), AgentMcpServer::getStatus, bo.getStatus())
            .orderByDesc(AgentMcpServer::getId);
        resourceGuard.applyVisible(wrapper, AgentMcpServer::getWorkspaceId);
        Page<AgentMcpServerVo> page = serverMapper.selectVoPage(pageQuery.build(), wrapper);
        return PageResult.build(page.getRecords(), page.getTotal());
    }

    @Override
    public AgentMcpServerVo queryById(Long id) {
        AgentMcpServer entity = serverMapper.selectById(id);
        if (entity != null) {
            resourceGuard.assertReadable(entity.getWorkspaceId());
        }
        AgentMcpServerVo vo = MapstructUtils.convert(entity, AgentMcpServerVo.class);
        if (vo == null) {
            throw new ServiceException("MCP 服务器不存在");
        }
        return vo;
    }

    @Override
    public Boolean insertByBo(AgentMcpServerBo bo) {
        Long scope = resourceGuard.resolveCreateWorkspace(bo.getWorkspaceId(), Boolean.TRUE.equals(bo.getShared()));
        checkNameUnique(bo.getName(), null, scope);
        validateConfig(bo);
        AgentMcpServer entity = MapstructUtils.convert(bo, AgentMcpServer.class);
        entity.setWorkspaceId(scope);
        entity.setStatus("0");
        entity.setToolCount(0);
        return serverMapper.insert(entity) > 0;
    }

    @Override
    public Boolean updateByBo(AgentMcpServerBo bo) {
        AgentMcpServer exists = serverMapper.selectById(bo.getId());
        if (exists == null) {
            throw new ServiceException("MCP 服务器不存在");
        }
        resourceGuard.assertWritable(exists.getWorkspaceId());
        checkNameUnique(bo.getName(), bo.getId(), exists.getWorkspaceId());
        validateConfig(bo);
        AgentMcpServer update = MapstructUtils.convert(bo, AgentMcpServer.class);
        update.setStatus(null);
        update.setToolCount(null);
        update.setWorkspaceId(null); // 归属空间一经创建不可变
        return serverMapper.updateById(update) > 0;
    }

    @Override
    public Boolean changeStatus(Long id, String status) {
        if (!"0".equals(status) && !"1".equals(status)) {
            throw new ServiceException("非法的服务器状态");
        }
        AgentMcpServer exists = serverMapper.selectById(id);
        if (exists != null) {
            resourceGuard.assertWritable(exists.getWorkspaceId());
        }
        AgentMcpServer update = new AgentMcpServer();
        update.setId(id);
        update.setStatus(status);
        return serverMapper.updateById(update) > 0;
    }

    @Override
    public Boolean deleteByIds(List<Long> ids) {
        for (Long id : ids) {
            AgentMcpServer exists = serverMapper.selectById(id);
            if (exists != null) {
                resourceGuard.assertWritable(exists.getWorkspaceId());
            }
        }
        return serverMapper.deleteByIds(ids) > 0;
    }

    @Override
    public List<String> testConnection(Long id) {
        AgentMcpServer server = serverMapper.selectById(id);
        if (server == null) {
            throw new ServiceException("MCP 服务器不存在");
        }
        resourceGuard.assertReadable(server.getWorkspaceId());
        List<String> toolNames;
        McpClientWrapper wrapper = null;
        try {
            wrapper = buildWrapper(server);
            wrapper.initialize().block(Duration.ofSeconds(30));
            List<io.modelcontextprotocol.spec.McpSchema.Tool> tools =
                wrapper.listTools().block(Duration.ofSeconds(30));
            toolNames = tools == null ? List.of() : tools.stream()
                .map(io.modelcontextprotocol.spec.McpSchema.Tool::name).toList();
        } catch (Exception e) {
            throw new ServiceException("MCP 连接失败: " + rootMessage(e));
        } finally {
            closeQuietly(wrapper);
        }
        AgentMcpServer update = new AgentMcpServer();
        update.setId(id);
        update.setToolCount(toolNames.size());
        serverMapper.updateById(update);
        return toolNames;
    }

    /**
     * 按传输类型构建 AgentScope MCP 客户端(sse / streamable_http)
     */
    public static McpClientWrapper buildWrapper(AgentMcpServer server) {
        McpClientBuilder builder = McpClientBuilder.create(server.getName())
            .timeout(Duration.ofSeconds(server.getTimeoutSeconds() == null ? 30 : server.getTimeoutSeconds()))
            .initializationTimeout(Duration.ofSeconds(30));
        if ("sse".equals(server.getServerType())) {
            builder.sseTransport(server.getUrl());
        } else {
            builder.streamableHttpTransport(server.getUrl());
        }
        if (StringUtils.isNotBlank(server.getHeaders())) {
            Map<String, Object> headers = JsonUtils.parseMap(server.getHeaders());
            if (headers != null) {
                headers.forEach((k, v) -> builder.header(k, String.valueOf(v)));
            }
        }
        return builder.buildSync();
    }

    private void closeQuietly(McpClientWrapper wrapper) {
        if (wrapper != null) {
            try {
                wrapper.close();
            } catch (Exception e) {
                log.warn("[McpServer] 关闭连接失败: {}", e.getMessage());
            }
        }
    }

    /**
     * 名称在同一归属范围内唯一:共享资源之间互斥,私有资源仅与本空间内互斥。
     */
    private void checkNameUnique(String name, Long excludeId, Long workspaceId) {
        if (StringUtils.isBlank(name)) {
            return;
        }
        LambdaQueryWrapper<AgentMcpServer> wrapper = new LambdaQueryWrapper<AgentMcpServer>()
            .eq(AgentMcpServer::getName, name)
            .ne(excludeId != null, AgentMcpServer::getId, excludeId);
        if (workspaceId == null) {
            wrapper.isNull(AgentMcpServer::getWorkspaceId);
        } else {
            wrapper.eq(AgentMcpServer::getWorkspaceId, workspaceId);
        }
        if (serverMapper.selectCount(wrapper) > 0) {
            throw new ServiceException("MCP 服务器名称已存在:" + name);
        }
    }

    private void validateConfig(AgentMcpServerBo bo) {
        if (!SERVER_TYPES.contains(bo.getServerType())) {
            throw new ServiceException("传输类型必须为 sse 或 streamable_http");
        }
        if (StringUtils.isNotBlank(bo.getHeaders()) && !JsonUtils.isJson(bo.getHeaders())) {
            throw new ServiceException("自定义请求头不是合法的 JSON");
        }
        // 仅做协议白名单:内网/回环地址是 MCP 服务器的常见合法部署方式,不在此一刀切拦截
        URI uri;
        try {
            uri = URI.create(bo.getUrl());
        } catch (Exception e) {
            throw new ServiceException("MCP 服务端地址非法:" + bo.getUrl());
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase();
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            throw new ServiceException("MCP 服务端地址仅允许 http/https 协议");
        }
        if (StringUtils.isBlank(uri.getHost())) {
            throw new ServiceException("MCP 服务端地址缺少主机名");
        }
    }

    private String rootMessage(Throwable e) {
        Throwable cur = e;
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        return cur.getMessage() == null ? cur.getClass().getSimpleName() : cur.getMessage();
    }
}
