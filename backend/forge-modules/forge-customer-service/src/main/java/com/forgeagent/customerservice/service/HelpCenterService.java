package com.forgeagent.customerservice.service;

import com.forgeagent.agent.api.customerservice.CsKnowledgeGateway;
import com.forgeagent.common.core.exception.ServiceException;
import com.forgeagent.common.redis.utils.RedisUtils;
import com.forgeagent.customerservice.domain.CsChannel;
import com.forgeagent.customerservice.domain.vo.HelpArticleVo;
import com.forgeagent.customerservice.domain.vo.HelpFeedbackResultVo;
import com.forgeagent.customerservice.mapper.HelpCenterMapper;
import com.forgeagent.rag.domain.vo.KnowledgeSearchHitVo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBucket;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 公开帮助中心(B1/F1/F3):只读已发布知识文档 + 已生效 FAQ。
 *
 * <p>不新建 CMS——内容源就是知识库文档(publish_status=PUBLISHED)与 FAQ(ACTIVE)。
 * 过滤全下推 SQL({@link HelpCenterMapper}),响应只含白名单字段,绝不泄漏 workspaceId、
 * 内部 chunk id、检索分数。搜索复用客服知识检索管线(已下推发布过滤),按文档合并多 chunk。</p>
 *
 * <p>F3:请求语言无内容时回退默认语言(zh_CN),用 {@code fallback} 标记,
 * 前端显式提示"该文章尚未翻译",不静默返回空列表。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HelpCenterService {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final String FB_PREFIX = "help:feedback:";
    /** 帮助中心的内容兜底语言;与前端 /help/:lang 路由默认值一致。 */
    public static final String DEFAULT_LOCALE = "zh_CN";
    /** hreflang 允许的语言集合,顺序即输出顺序。 */
    public static final List<String> SUPPORTED_LOCALES = List.of("zh_CN", "en_US");

    private final HelpCenterMapper mapper;
    private final CsChannelGuard channelGuard;
    private final CsKnowledgeGateway knowledgeGateway;

    /** 渠道绑定的知识库 ID 列表(逗号分隔)。 */
    private List<Long> knowledgeIds(CsChannel channel) {
        if (channel.getKnowledgeIds() == null || channel.getKnowledgeIds().isBlank()) {
            return List.of();
        }
        List<Long> ids = new ArrayList<>();
        for (String part : channel.getKnowledgeIds().split("[,;]")) {
            try {
                long id = Long.parseLong(part.trim());
                if (id > 0) {
                    ids.add(id);
                }
            } catch (NumberFormatException ignored) {
                // 脏配置跳过,不让一条坏数据打挂整个帮助中心
            }
        }
        return ids;
    }

    /**
     * 公开入口统一守卫:渠道有效性 + IP 限流 + Origin 白名单。
     *
     * <p>帮助中心是免登录公开面,和 /chat-api 一样必须先过 IP 预限流,
     * 否则任何人都能无成本刷检索(每条都打向量库)。</p>
     */
    private CsChannel authorize(String channelCode, String clientIp, String origin) {
        CsChannel channel = channelGuard.require(channelCode);
        channelGuard.checkRateLimit(channel, clientIp);
        channelGuard.checkOrigin(channel, origin);
        return channel;
    }

    private static String normalizeLocale(String locale) {
        if (locale == null || locale.isBlank()) {
            return DEFAULT_LOCALE;
        }
        String value = locale.trim();
        return SUPPORTED_LOCALES.contains(value) ? value : DEFAULT_LOCALE;
    }

    /**
     * F1 高亮:把 summary 按 query 大小写不敏感切 structured 片段。
     * 只产出纯文本片段,前端用插值 + &lt;mark&gt; 渲染,不返回 HTML,杜绝 XSS。
     */
    private java.util.List<HelpArticleVo.Segment> highlight(String summary, String query) {
        if (summary == null || summary.isEmpty() || query == null || query.isBlank()) {
            return null;
        }
        String lower = summary.toLowerCase(Locale.ROOT);
        String needle = query.trim().toLowerCase(Locale.ROOT);
        if (needle.isEmpty()) {
            return null;
        }
        java.util.List<HelpArticleVo.Segment> parts = new ArrayList<>();
        int from = 0;
        int at;
        while ((at = lower.indexOf(needle, from)) >= 0 && parts.size() < 50) {
            if (at > from) {
                parts.add(new HelpArticleVo.Segment(summary.substring(from, at), false));
            }
            parts.add(new HelpArticleVo.Segment(summary.substring(at, Math.min(at + needle.length(), summary.length())), true));
            from = at + needle.length();
        }
        if (from < summary.length()) {
            parts.add(new HelpArticleVo.Segment(summary.substring(from), false));
        }
        return parts.isEmpty() ? null : parts;
    }

    private HelpArticleVo toArticle(Map<String, Object> row) {
        HelpArticleVo vo = new HelpArticleVo();
        Object id = row.get("id");
        vo.setSlug(id == null ? String.valueOf(row.get("title")) : "d" + id);
        Object title = row.get("title");
        vo.setTitle(title == null ? "" : String.valueOf(title));
        Object summary = row.containsKey("summary") ? row.get("summary") : row.get("content");
        vo.setSummary(summary == null ? "" : String.valueOf(summary));
        Object locale = row.get("locale");
        vo.setLocale(locale == null ? DEFAULT_LOCALE : String.valueOf(locale));
        Object source = row.get("source");
        vo.setSource(source == null ? "doc" : String.valueOf(source));
        Object time = row.get("updateTime");
        if (time instanceof java.util.Date d) {
            vo.setUpdateTime(d);
        }
        vo.setFallback(false);
        return vo;
    }

    private List<HelpArticleVo> docsOf(CsChannel channel, String locale) {
        List<HelpArticleVo> result = new ArrayList<>();
        List<Long> ids = knowledgeIds(channel);
        if (ids.isEmpty()) {
            return result;
        }
        for (Map<String, Object> row : mapper.listPublishedDocs(channel.getWorkspaceId(), ids, locale)) {
            result.add(toArticle(row));
        }
        return result;
    }

    private void appendFaqs(CsChannel channel, List<HelpArticleVo> result, boolean fallback) {
        for (Map<String, Object> row : mapper.listActiveFaqs(channel.getWorkspaceId())) {
            HelpArticleVo faq = toArticle(row);
            if (faq.getSlug() == null || faq.getSlug().isBlank()) {
                faq.setSlug("faq-" + Math.abs(faq.getTitle().hashCode()));
            }
            faq.setLocale(DEFAULT_LOCALE);
            faq.setFallback(fallback);
            result.add(faq);
        }
    }

    /** 兼容旧调用(测试/内部):不带 IP 限流。生产入口走四参重载。 */
    public List<HelpArticleVo> list(String channelCode, String locale) {
        return listByChannel(channelGuard.require(channelCode), locale);
    }

    /** 文章列表:已发布文档 + ACTIVE FAQ;请求语言无内容时回退默认语言并标记 fallback。 */
    public List<HelpArticleVo> list(String channelCode, String locale, String clientIp, String origin) {
        return listByChannel(authorize(channelCode, clientIp, origin), locale);
    }

    private List<HelpArticleVo> listByChannel(CsChannel channel, String locale) {
        String want = normalizeLocale(locale);
        List<HelpArticleVo> result = docsOf(channel, want);
        boolean fallback = false;
        if (result.isEmpty() && !DEFAULT_LOCALE.equals(want)) {
            // 目标语言完全没有内容:整站进入兜底模式(包括 FAQ),前端统一提示"未翻译"
            fallback = true;
            result = docsOf(channel, DEFAULT_LOCALE);
            for (HelpArticleVo vo : result) {
                vo.setFallback(true);
            }
        }
        // FAQ 只有默认语言:请求默认语言、或已回退时一并展示(回退时标 fallback)
        if (DEFAULT_LOCALE.equals(want) || fallback) {
            appendFaqs(channel, result, fallback);
        }
        result.sort(Comparator.comparing(HelpArticleVo::getUpdateTime,
            Comparator.nullsLast(Comparator.reverseOrder())));
        return result;
    }

    /** 兼容旧调用:不带 locale / IP 限流。 */
    public HelpArticleVo detail(String channelCode, String slug) {
        return detailByChannel(channelGuard.require(channelCode), slug, null);
    }

    public HelpArticleVo detail(String channelCode, String slug, String locale, String clientIp, String origin) {
        return detailByChannel(authorize(channelCode, clientIp, origin), slug, locale);
    }

    /** 文章详情:只返回白名单字段;正文取 parsed_text,不做任何内部字段透出。 */
    private HelpArticleVo detailByChannel(CsChannel channel, String slug, String locale) {
        if (slug == null || !slug.startsWith("d") || slug.length() < 2) {
            throw new ServiceException("文章不存在");
        }
        long docId;
        try {
            docId = Long.parseLong(slug.substring(1));
        } catch (NumberFormatException e) {
            throw new ServiceException("文章不存在");
        }
        Map<String, Object> row = mapper.findPublishedDoc(channel.getWorkspaceId(), docId);
        if (row == null) {
            throw new ServiceException("文章不存在");
        }
        HelpArticleVo vo = toArticle(row);
        String want = normalizeLocale(locale);
        vo.setFallback(!want.equals(vo.getLocale()));
        return vo;
    }

    /** 兼容旧调用:不带 IP 限流。 */
    public List<HelpArticleVo> search(String channelCode, String query, String locale) {
        return searchByChannel(channelGuard.require(channelCode), query, locale);
    }

    /**
     * F1 站内搜索:走现有客服检索管线(SQL 已下推 publish_status=PUBLISHED),
     * 按文档合并多 chunk 命中为一条,响应只含标题/摘要/slug。
     */
    public List<HelpArticleVo> search(String channelCode, String query, String locale,
                                      String clientIp, String origin) {
        CsChannel channel = authorize(channelCode, clientIp, origin);
        return searchByChannel(channel, query, locale);
    }

    private List<HelpArticleVo> searchByChannel(CsChannel channel, String query, String locale) {
        if (query == null || query.isBlank() || query.length() > 200) {
            return List.of();
        }
        List<Long> ids = knowledgeIds(channel);
        if (ids.isEmpty()) {
            return List.of();
        }
        CsKnowledgeGateway.RetrievalOutcome outcome =
            knowledgeGateway.retrieve(channel.getWorkspaceId(), ids, query.trim());
        if (outcome == null || outcome.hits() == null || outcome.hits().isEmpty()) {
            return List.of();
        }
        // 同一文档多 chunk 合并:取最高分片段代表,展示标题/摘要,不暴露 chunk id / score
        Map<Long, KnowledgeSearchHitVo> bestByDoc = new LinkedHashMap<>();
        for (KnowledgeSearchHitVo hit : outcome.hits()) {
            if (hit.getDocumentId() == null) {
                continue;
            }
            KnowledgeSearchHitVo prev = bestByDoc.get(hit.getDocumentId());
            if (prev == null || (hit.getScore() != null && (prev.getScore() == null
                || hit.getScore() > prev.getScore()))) {
                bestByDoc.put(hit.getDocumentId(), hit);
            }
        }
        List<HelpArticleVo> result = new ArrayList<>();
        for (Map.Entry<Long, KnowledgeSearchHitVo> e : bestByDoc.entrySet()) {
            KnowledgeSearchHitVo hit = e.getValue();
            HelpArticleVo vo = new HelpArticleVo();
            vo.setSlug("d" + e.getKey());
            vo.setTitle(hit.getFileName() == null ? "文档 " + e.getKey() : hit.getFileName());
            String content = hit.getContent() == null ? "" : hit.getContent();
            String summary = content.length() > 300 ? content.substring(0, 300) : content;
            vo.setSummary(summary);
            vo.setSegments(highlight(summary, query));
            vo.setLocale(normalizeLocale(locale));
            vo.setSource("doc");
            vo.setFallback(false);
            result.add(vo);
        }
        return result;
    }

    /** 兼容旧调用:不带 IP 限流。 */
    public HelpFeedbackResultVo feedback(String channelCode, String slug, boolean helpful, String ip) {
        return feedbackByChannel(channelGuard.require(channelCode), slug, helpful, ip);
    }

    /** Was this helpful? 按 IP+渠道+文章+自然日去重,24h TTL 自动清理;入口同时做 IP 限流。 */
    public HelpFeedbackResultVo feedback(String channelCode, String slug, boolean helpful, String ip, String origin) {
        CsChannel channel = authorize(channelCode, ip, origin);
        return feedbackByChannel(channel, slug, helpful, ip);
    }

    private HelpFeedbackResultVo feedbackByChannel(CsChannel channel, String slug, boolean helpful, String ip) {
        String day = LocalDate.now().format(DAY);
        String key = FB_PREFIX + channel.getChannelCode() + ":" + (ip == null ? "unknown" : ip)
            + ":" + (slug == null ? "" : slug) + ":" + day;
        try {
            RBucket<String> bucket = RedisUtils.getClient().getBucket(key);
            boolean first = bucket.trySet(helpful ? "up" : "down", 1, TimeUnit.DAYS);
            HelpFeedbackResultVo vo = new HelpFeedbackResultVo();
            vo.setAccepted(first);
            vo.setMessage(first ? "感谢反馈" : "今日已反馈过该文章");
            return vo;
        } catch (Exception e) {
            // Redis 故障不阻断反馈体验,degrade 为接受
            log.warn("[HELP] 反馈去重失败 key={}: {}", key, e.getMessage());
            HelpFeedbackResultVo vo = new HelpFeedbackResultVo();
            vo.setAccepted(true);
            vo.setMessage("感谢反馈");
            return vo;
        }
    }

    /** 兼容旧调用:不带 IP 限流。 */
    public List<String> sitemapSlugs(String channelCode) {
        return sitemapSlugsByChannel(channelGuard.require(channelCode));
    }

    public List<String> sitemapSlugs(String channelCode, String clientIp, String origin) {
        return sitemapSlugsByChannel(authorize(channelCode, clientIp, origin));
    }

    /** sitemap:列出已发布文档 slug(公开可索引),不含 FAQ 与内部字段。 */
    private List<String> sitemapSlugsByChannel(CsChannel channel) {
        List<Long> ids = knowledgeIds(channel);
        if (ids.isEmpty()) {
            return List.of();
        }
        List<String> slugs = new ArrayList<>();
        for (Map<String, Object> row : mapper.listPublishedDocs(channel.getWorkspaceId(), ids, null)) {
            Object id = row.get("id");
            if (id != null) {
                slugs.add("d" + id);
            }
        }
        return slugs;
    }

    /**
     * F3:SEO sitemap XML。搜索引擎要求 loc 为绝对地址,且多语言页用
     * {@code xhtml:link rel="alternate" hreflang=...} 声明互链;SPA 里动态插 &lt;link&gt; 不被所有爬虫执行,
     * sitemap 是更可靠的真源。
     *
     * @param baseUrl 站点根地址(如 https://help.example.com),由 Controller 从请求头推导并做白名单校验
     */
    public String sitemapXml(String channelCode, String baseUrl, String clientIp, String origin) {
        return sitemapXmlByChannel(authorize(channelCode, clientIp, origin), baseUrl);
    }

    /** 兼容内部/测试调用:不做 IP 限流,生产入口走四参重载。 */
    public String sitemapXml(String channelCode, String baseUrl) {
        return sitemapXmlByChannel(channelGuard.require(channelCode), baseUrl);
    }

    private String sitemapXmlByChannel(CsChannel channel, String baseUrl) {
        String root = baseUrl == null ? "" : baseUrl.replaceAll("/+$", "");
        StringBuilder xml = new StringBuilder(512);
        xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
            .append("<urlset xmlns=\"http://www.sitemaps.org/schemas/sitemap/0.9\" ")
            .append("xmlns:xhtml=\"http://www.w3.org/1999/xhtml\">\n");
        // 语言入口页:zh_CN / en_US 互链,并给 x-default
        for (String locale : SUPPORTED_LOCALES) {
            xml.append("  <url><loc>").append(esc(root + "/help/" + locale)).append("</loc>")
                .append("<changefreq>weekly</changefreq><priority>")
                .append(DEFAULT_LOCALE.equals(locale) ? "0.8" : "0.7").append("</priority>");
            for (String alt : SUPPORTED_LOCALES) {
                xml.append("<xhtml:link rel=\"alternate\" hreflang=\"")
                    .append(hreflangOf(alt)).append("\" href=\"")
                    .append(esc(root + "/help/" + alt)).append("\"/>");
            }
            xml.append("<xhtml:link rel=\"alternate\" hreflang=\"x-default\" href=\"")
                .append(esc(root + "/help/" + DEFAULT_LOCALE)).append("\"/>");
            xml.append("</url>\n");
        }
        // 文章页:文档本身带 locale,不做跨语言 alternate(当前模型一篇文章即一种语言)
        List<Long> ids = knowledgeIds(channel);
        if (!ids.isEmpty()) {
            for (Map<String, Object> row : mapper.listPublishedDocs(channel.getWorkspaceId(), ids, null)) {
                Object id = row.get("id");
                if (id == null) {
                    continue;
                }
                String docLocale = normalizeLocale(row.get("locale") == null ? null : String.valueOf(row.get("locale")));
                xml.append("  <url><loc>")
                    .append(esc(root + "/help/" + docLocale + "/d" + id)).append("</loc>")
                    .append("<changefreq>monthly</changefreq><priority>0.6</priority>")
                    .append("<xhtml:link rel=\"alternate\" hreflang=\"").append(hreflangOf(docLocale))
                    .append("\" href=\"").append(esc(root + "/help/" + docLocale + "/d" + id)).append("\"/>")
                    .append("</url>\n");
            }
        }
        xml.append("</urlset>\n");
        return xml.toString();
    }

    private static String hreflangOf(String locale) {
        return "en_US".equals(locale) ? "en" : "zh-CN";
    }

    private static String esc(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            .replace("\"", "&quot;").replace("'", "&apos;");
    }
}
