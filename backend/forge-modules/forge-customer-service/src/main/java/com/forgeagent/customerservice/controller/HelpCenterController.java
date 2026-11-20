package com.forgeagent.customerservice.controller;

import com.forgeagent.common.core.domain.R;
import com.forgeagent.customerservice.domain.vo.HelpArticleVo;
import com.forgeagent.customerservice.domain.vo.HelpFeedbackResultVo;
import com.forgeagent.customerservice.service.HelpCenterService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.regex.Pattern;

/**
 * 公开帮助中心 API(B1/F1/F3):{@code /help-api/**} 由 security.excludes 放行,
 * 但所有请求仍经 CsChannelGuard 做渠道校验、IP 限流与 Origin 白名单。只读,不接任何管理端点。
 *
 * <p>响应经 {@link HelpCenterService} 裁剪为白名单字段,不泄漏内部 ID / 分数 / workspaceId。</p>
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/help-api/{channelCode}")
public class HelpCenterController {

    /** 站点根地址白名单:只允许 http/https + host[:port],防止 sitemap 里注入任意文本。 */
    private static final Pattern ORIGIN = Pattern.compile("^https?://[A-Za-z0-9.\\-]+(:\\d{1,5})?$");

    private final HelpCenterService service;

    @GetMapping("/articles")
    public R<List<HelpArticleVo>> articles(@PathVariable String channelCode,
                                           @RequestParam(required = false) String locale,
                                           HttpServletRequest request) {
        return R.data(service.list(channelCode, locale, clientIp(request), origin(request)));
    }

    @GetMapping("/articles/{slug}")
    public R<HelpArticleVo> detail(@PathVariable String channelCode, @PathVariable String slug,
                                   @RequestParam(required = false) String locale,
                                   HttpServletRequest request) {
        return R.data(service.detail(channelCode, slug, locale, clientIp(request), origin(request)));
    }

    /** F1 站内搜索:零命中由前端引导"向 AI 提问"/"转人工"。 */
    @GetMapping("/search")
    public R<List<HelpArticleVo>> search(@PathVariable String channelCode,
                                         @RequestParam String q,
                                         @RequestParam(required = false) String locale,
                                         HttpServletRequest request) {
        return R.data(service.search(channelCode, q, locale, clientIp(request), origin(request)));
    }

    /** 文章反馈:按 IP+文章+日去重;入口同样走 IP 限流,防止刷反馈。 */
    @PostMapping("/articles/{slug}/feedback")
    public R<HelpFeedbackResultVo> feedback(@PathVariable String channelCode,
                                            @PathVariable String slug,
                                            @RequestParam boolean helpful,
                                            HttpServletRequest request) {
        return R.data(service.feedback(channelCode, slug, helpful, clientIp(request), origin(request)));
    }

    /** SEO sitemap(纯文本 slug 列表,前端/搜索引擎据此生成 XML)。 */
    @GetMapping("/sitemap")
    public R<List<String>> sitemap(@PathVariable String channelCode, HttpServletRequest request) {
        return R.data(service.sitemapSlugs(channelCode, clientIp(request), origin(request)));
    }

    /**
     * F3 动态 sitemap:绝对 URL + xhtml:link 多语言互链。
     * 静态 /sitemap.xml 只能放样例,多租户场景由反向代理把 /sitemap.xml 指到本端点。
     */
    @GetMapping(value = "/sitemap.xml", produces = MediaType.APPLICATION_XML_VALUE)
    public ResponseEntity<String> sitemapXml(@PathVariable String channelCode, HttpServletRequest request) {
        String xml = service.sitemapXml(channelCode, origin(request), clientIp(request), origin(request));
        return ResponseEntity.ok()
            .contentType(MediaType.APPLICATION_XML)
            .header("Cache-Control", "public, max-age=600")
            .body(xml);
    }

    private static String clientIp(HttpServletRequest request) {
        String ip = request.getHeader("X-Forwarded-For");
        if (ip == null || ip.isBlank()) {
            ip = request.getRemoteAddr();
        }
        return ip == null ? "unknown" : ip.split(",")[0].trim();
    }

    /** 推导站点根地址;非法/缺失时回落到请求自身,保证 sitemap 仍是绝对 URL。 */
    private static String origin(HttpServletRequest request) {
        String proto = firstNonBlank(request.getHeader("X-Forwarded-Proto"), request.getScheme());
        String host = firstNonBlank(request.getHeader("X-Forwarded-Host"), request.getHeader("Host"));
        if (host == null || host.isBlank()) {
            host = request.getServerName();
            int port = request.getServerPort();
            if (port > 0 && port != 80 && port != 443) {
                host = host + ":" + port;
            }
        }
        String candidate = (proto == null ? "http" : proto.trim()) + "://" + host.trim();
        return ORIGIN.matcher(candidate).matches() ? candidate : "http://" + host.trim();
    }

    private static String firstNonBlank(String a, String b) {
        return a != null && !a.isBlank() ? a : b;
    }
}
