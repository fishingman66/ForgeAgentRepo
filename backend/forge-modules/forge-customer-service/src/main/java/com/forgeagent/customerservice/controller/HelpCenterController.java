package com.forgeagent.customerservice.controller;

import com.forgeagent.common.core.domain.R;
import com.forgeagent.customerservice.domain.vo.HelpArticleVo;
import com.forgeagent.customerservice.domain.vo.HelpFeedbackResultVo;
import com.forgeagent.customerservice.service.HelpCenterService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 公开帮助中心 API(B1/F1/F3):{@code /help-api/**} 由 security.excludes 放行,
 * 但所有请求仍经 CsChannelGuard 做渠道校验与 IP 预限流。只读,不接任何管理端点。
 *
 * <p>响应经 {@link HelpCenterService} 裁剪为白名单字段,不泄漏内部 ID / 分数 / workspaceId。</p>
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/help-api/{channelCode}")
public class HelpCenterController {

    private final HelpCenterService service;

    @GetMapping("/articles")
    public R<List<HelpArticleVo>> articles(@PathVariable String channelCode,
                                           @RequestParam(required = false) String locale) {
        return R.data(service.list(channelCode, locale));
    }

    @GetMapping("/articles/{slug}")
    public R<HelpArticleVo> detail(@PathVariable String channelCode, @PathVariable String slug) {
        return R.data(service.detail(channelCode, slug));
    }

    /** F1 站内搜索:零命中由前端引导"向 AI 提问"/"转人工"。 */
    @GetMapping("/search")
    public R<List<HelpArticleVo>> search(@PathVariable String channelCode,
                                         @RequestParam String q,
                                         @RequestParam(required = false) String locale) {
        return R.data(service.search(channelCode, q, locale));
    }

    /** 文章反馈:按 IP+文章+日去重。 */
    @PostMapping("/articles/{slug}/feedback")
    public R<HelpFeedbackResultVo> feedback(@PathVariable String channelCode,
                                            @PathVariable String slug,
                                            @RequestParam boolean helpful,
                                            HttpServletRequest request) {
        String ip = request.getHeader("X-Forwarded-For");
        if (ip == null || ip.isBlank()) {
            ip = request.getRemoteAddr();
        } else {
            ip = ip.split(",")[0].trim();
        }
        return R.data(service.feedback(channelCode, slug, helpful, ip));
    }

    /** SEO sitemap(纯文本 slug 列表,前端/搜索引擎据此生成 XML)。 */
    @GetMapping("/sitemap")
    public R<List<String>> sitemap(@PathVariable String channelCode) {
        return R.data(service.sitemapSlugs(channelCode));
    }
}
