package com.forgeagent.agent.config;

import cn.hutool.core.util.StrUtil;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Set;

/**
 * 演示环境写保护过滤器
 *
 * <p>{@code agent.demo.enabled=true} 时启用: 仅放行 GET/HEAD/OPTIONS 与对话体验必需的写接口,
 * 其余写操作(POST/PUT/DELETE/PATCH)一律拦截,防止演示数据被篡改。</p>
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
@ConditionalOnProperty(prefix = "agent.demo", name = "enabled", havingValue = "true")
public class DemoGuardFilter extends OncePerRequestFilter {

    private static final Set<String> WRITE_METHODS = Set.of("POST", "PUT", "DELETE", "PATCH");

    /** 演示环境放行的写接口前缀(登录 + 对话体验闭环) */
    private static final List<String> WRITE_WHITELIST = List.of(
        "/auth/login",
        "/auth/logout",
        "/auth/code",
        "/agent/chat/send",
        "/agent/chat/stream",
        "/agent/chat/attachment/upload",
        "/agent/session",
        "/agent/knowledge/search"
    );

    @Value("${agent.demo.guard-message:演示环境已开启写保护，仅支持对话体验}")
    private String guardMessage;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
        throws ServletException, IOException {
        String method = request.getMethod();
        if (!WRITE_METHODS.contains(method)) {
            chain.doFilter(request, response);
            return;
        }
        String uri = request.getRequestURI();
        String contextPath = request.getContextPath();
        if (StrUtil.isNotBlank(contextPath) && uri.startsWith(contextPath)) {
            uri = uri.substring(contextPath.length());
        }
        for (String prefix : WRITE_WHITELIST) {
            if (uri.startsWith(prefix)) {
                chain.doFilter(request, response);
                return;
            }
        }
        log.info("[DemoGuard] 拦截演示环境写操作: {} {}", method, uri);
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"code\":403,\"msg\":\"" + guardMessage + "\",\"data\":null}");
    }
}
