package com.forgeagent.common.security.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 安全响应头过滤器:对所有非 SSE 响应写入 X-Content-Type-Options/X-Frame-Options/Referrer-Policy/CSP 等头。
 *
 * <p>SSE 接口(text/event-stream)不写 CSP,避免部分代理/浏览器对 stream 的 CSP 误判影响流式解析。
 *
 * @author ForgeAgent
 */
public class SecurityHeadersFilter extends OncePerRequestFilter {

    private static final String CSP_VALUE =
        "default-src 'self'; " +
            "img-src 'self' data: blob:; " +
            "style-src 'self' 'unsafe-inline'; " +
            "script-src 'self'; " +
            "connect-src 'self' ws: wss:; " +
            "font-src 'self' data:";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
        throws ServletException, IOException {
        // 基础头一律写
        setIfAbsent(response, "X-Content-Type-Options", "nosniff");
        // SAMEORIGIN:流程设计器等前端页面经同源代理 iframe 嵌入后端页面,DENY 会直接堵死;跨域嵌套仍被拦截
        setIfAbsent(response, "X-Frame-Options", "SAMEORIGIN");
        setIfAbsent(response, "Referrer-Policy", "strict-origin-when-cross-origin");
        // 显式关闭旧浏览器 XSS auditor,防滥用
        setIfAbsent(response, "X-XSS-Protection", "0");

        // SSE 接口不写 CSP
        if (!isSseRequest(request)) {
            setIfAbsent(response, "Content-Security-Policy", CSP_VALUE);
        }
        filterChain.doFilter(request, response);
    }

    private void setIfAbsent(HttpServletResponse response, String name, String value) {
        if (!response.containsHeader(name)) {
            response.setHeader(name, value);
        }
    }

    /**
     * 判定是否为 SSE 请求:URI 以 stream 结尾 或 Accept 头包含 text/event-stream。
     */
    private boolean isSseRequest(HttpServletRequest request) {
        String uri = request.getRequestURI();
        if (uri != null && (uri.endsWith("/stream") || uri.contains("/stream/"))) {
            return true;
        }
        String accept = request.getHeader("Accept");
        return accept != null && accept.contains(MediaType.TEXT_EVENT_STREAM_VALUE);
    }
}
