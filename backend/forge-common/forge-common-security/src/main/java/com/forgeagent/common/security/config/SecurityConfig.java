package com.forgeagent.common.security.config;

import cn.dev33.satoken.exception.NotLoginException;
import cn.dev33.satoken.exception.NotPermissionException;
import cn.dev33.satoken.filter.SaServletFilter;
import cn.dev33.satoken.filter.SaTokenContextFilterForJakartaServlet;
import cn.dev33.satoken.httpauth.basic.SaHttpBasicUtil;
import cn.dev33.satoken.interceptor.SaInterceptor;
import cn.dev33.satoken.router.SaRouter;
import cn.dev33.satoken.stp.StpUtil;
import cn.dev33.satoken.util.SaResult;
import cn.dev33.satoken.util.SaTokenConsts;
import jakarta.annotation.PostConstruct;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import com.forgeagent.common.core.constant.HttpStatus;
import com.forgeagent.common.core.utils.NetUtils;
import com.forgeagent.common.core.utils.ServletUtils;
import com.forgeagent.common.core.utils.SpringUtils;
import com.forgeagent.common.core.utils.StringUtils;
import com.forgeagent.common.satoken.utils.LoginHelper;
import com.forgeagent.common.security.config.properties.SecurityProperties;
import com.forgeagent.common.security.filter.SecurityHeadersFilter;
import com.forgeagent.common.security.handler.AllUrlHandler;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.EnumSet;
import java.util.List;

/**
 * 权限安全配置
 *
 * @author Lion Li; 改造: 智造 ForgeAgent 2026-11
 */

@Slf4j
@AutoConfiguration
@EnableConfigurationProperties(SecurityProperties.class)
@RequiredArgsConstructor
public class SecurityConfig implements WebMvcConfigurer {

    private static final String CLIENT_RULE_SEPARATOR_REGEX = "[,;\\r\\n]+";

    private final SecurityProperties securityProperties;

    /**
     * Sa-Token 1.46.0 起 starter 以 FilterRegistrationBean 形式注册上下文过滤器，
     * 这里注入官方注册，初始化前就地调整，不新增 Bean 定义避免双重注册。
     */
    private final FilterRegistrationBean<SaTokenContextFilterForJakartaServlet> saTokenContextRegistration;

    /**
     * 调整 Sa-Token 官方注册的上下文过滤器，使其覆盖 Servlet 异步分发。
     * <p>
     * SSE、WebSocket 握手等场景可能触发 ASYNC/ERROR dispatcher，如果上下文过滤器只处理普通 REQUEST，
     * 后续统一鉴权或业务代码读取 SaHolder/StpUtil 时会出现 SaTokenContext 未初始化。
     */
    @PostConstruct
    public void adjustSaTokenContextFilter() {
        saTokenContextRegistration.setName("saTokenContextFilterForServlet");
        saTokenContextRegistration.addUrlPatterns("/*");
        saTokenContextRegistration.setDispatcherTypes(EnumSet.of(DispatcherType.REQUEST, DispatcherType.ASYNC, DispatcherType.ERROR));
        saTokenContextRegistration.setAsyncSupported(true);
        saTokenContextRegistration.setOrder(Ordered.HIGHEST_PRECEDENCE);
    }

    /**
     * 注册 Sa-Token 路由拦截器并配置鉴权规则。
     *
     * @param registry 拦截器注册器
     */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // 注册路由拦截器，自定义验证规则
        registry.addInterceptor(new SaInterceptor(handler -> {
                AllUrlHandler allUrlHandler = SpringUtils.getBean(AllUrlHandler.class);
                // 登录验证 -- 排除多个路径
                SaRouter
                    // 获取所有的
                    .match(allUrlHandler.getUrls())
                    // 对未排除的路径进行检查
                    .check(() -> {
                        HttpServletRequest request = ServletUtils.getRequest();
                        HttpServletResponse response = ServletUtils.getResponse();
                        response.setContentType(SaTokenConsts.CONTENT_TYPE_APPLICATION_JSON);
                        // 检查是否登录 是否有token
                        StpUtil.checkLogin();

                        // 检查 header 与 param 里的 clientid 与 token 里的是否一致
                        String headerCid = request.getHeader(LoginHelper.CLIENT_KEY);
                        String paramCid = ServletUtils.getParameter(LoginHelper.CLIENT_KEY);
                        String clientId = StpUtil.getExtra(LoginHelper.CLIENT_KEY).toString();
                        if (!StringUtils.equalsAny(clientId, headerCid, paramCid)) {
                            // token 无效
                            throw NotLoginException.newInstance(StpUtil.getLoginType(),
                                "-100", "客户端ID与Token不匹配",
                                StpUtil.getTokenValue());
                        }
                        validateClientAccessRules(request);

                        // 有效率影响 用于临时测试
                        // if (log.isDebugEnabled()) {
                        //     log.info("剩余有效时间: {}", StpUtil.getTokenTimeout());
                        //     log.info("临时有效时间: {}", StpUtil.getTokenActivityTimeout());
                        // }

                    });
            })).addPathPatterns("/**")
            // 排除不需要拦截的路径
            .excludePathPatterns(securityProperties.getExcludes());
    }

    /**
     * 注册安全响应头过滤器:为所有非 SSE 响应写入 X-Content-Type-Options/X-Frame-Options/Referrer-Policy/CSP。
     * 顺序置于 Sa-Token 上下文过滤器之后,确保任何业务/异常出口都能带上响应头。
     *
     * @return 过滤器注册配置
     */
    @Bean
    public FilterRegistrationBean<SecurityHeadersFilter> securityHeadersFilterRegistration() {
        FilterRegistrationBean<SecurityHeadersFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(new SecurityHeadersFilter());
        registration.setName("securityHeadersFilter");
        registration.addUrlPatterns("/*");
        registration.setDispatcherTypes(EnumSet.of(DispatcherType.REQUEST, DispatcherType.ASYNC, DispatcherType.ERROR));
        registration.setAsyncSupported(true);
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 1);
        return registration;
    }

    /**
     * 为 actuator 健康检查接口配置 Basic Auth 鉴权过滤器。
     *
     * @return Sa-Token Servlet 过滤器
     */
    @Bean
    public SaServletFilter getSaServletFilter() {
        String username = SpringUtils.getProperty("spring.boot.admin.client.username");
        String password = SpringUtils.getProperty("spring.boot.admin.client.password");
        return new SaServletFilter()
            .addInclude("/actuator", "/actuator/**")
            .setAuth(obj -> {
                SaHttpBasicUtil.check(username + StringUtils.COLON + password);
            })
            .setError(e -> {
                HttpServletResponse response = ServletUtils.getResponse();
                response.setContentType(SaTokenConsts.CONTENT_TYPE_APPLICATION_JSON);
                return SaResult.error(e.getMessage()).setCode(HttpStatus.UNAUTHORIZED);
            });
    }

    /**
     * 按客户端配置校验接口访问路径与来源 IP。
     *
     * @param request 当前请求
     */
    private void validateClientAccessRules(HttpServletRequest request) {
        String requestPath = StringUtils.blankToDefault(request.getServletPath(), request.getRequestURI());
        String accessPath = getTokenExtra(LoginHelper.CLIENT_ACCESS_PATH_KEY);
        if (StringUtils.isNotBlank(accessPath)) {
            List<String> accessPathList = StringUtils.str2List(accessPath, CLIENT_RULE_SEPARATOR_REGEX, true, true);
            if (!StringUtils.matches(requestPath, accessPathList)) {
                throw new NotPermissionException("当前客户端未授权访问该接口路径");
            }
        }

        String ipWhitelist = getTokenExtra(LoginHelper.CLIENT_IP_WHITELIST_KEY);
        if (StringUtils.isNotBlank(ipWhitelist)) {
            String clientIp = ServletUtils.getClientIP(request);
            List<String> ipWhitelistList = StringUtils.str2List(ipWhitelist, CLIENT_RULE_SEPARATOR_REGEX, true, true);
            boolean matched = ipWhitelistList.stream().anyMatch(rule -> NetUtils.isMatchIpRule(rule, clientIp));
            if (!matched) {
                throw new NotPermissionException("当前客户端IP不在白名单内");
            }
        }
    }

    /**
     * 读取 token 扩展信息，兼容空值场景。
     *
     * @param key 扩展字段
     * @return 扩展值
     */
    private String getTokenExtra(String key) {
        Object extra = StpUtil.getExtra(key);
        return extra == null ? null : extra.toString();
    }

}
