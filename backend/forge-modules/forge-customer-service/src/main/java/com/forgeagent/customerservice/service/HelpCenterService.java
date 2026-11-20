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
import java.util.Locale;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 公开帮助中心(B1/F1/F3):只读已发布知识文档 + 已生效 FAQ。
 *
 * <p>不新建 CMS——内容源就是知识库文档(publish_status=PUBLISHED)与 FAQ(ACTIVE)。
 * 过滤全下推 SQL({@link HelpCenterMapper}),响应只含白名单字段,绝不泄漏 workspaceId、
 * 内部 chunk id、检索分数。搜索复用客服知识检索管线(已下推发布过滤),按文档合并多 chunk。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HelpCenterService {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final String FB_PREFIX = "help:feedback:";

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
        vo.setLocale(locale == null ? "zh_CN" : String.valueOf(locale));
        Object source = row.get("source");
        vo.setSource(source == null ? "doc" : String.valueOf(source));
        Object time = row.get("updateTime");
        if (time instanceof java.util.Date d) {
            vo.setUpdateTime(d);
        }
        return vo;
    }

    /** 文章列表:已发布文档 + ACTIVE FAQ,按 locale 过滤(FAQ 无 locale 视为默认语言)。 */
    public List<HelpArticleVo> list(String channelCode, String locale) {
        CsChannel channel = channelGuard.require(channelCode);
        List<HelpArticleVo> result = new ArrayList<>();
        List<Long> ids = knowledgeIds(channel);
        if (!ids.isEmpty()) {
            for (Map<String, Object> row : mapper.listPublishedDocs(channel.getWorkspaceId(), ids, locale)) {
                result.add(toArticle(row));
            }
        }
        if (locale == null || locale.isBlank() || locale.startsWith("zh")) {
            for (Map<String, Object> row : mapper.listActiveFaqs(channel.getWorkspaceId())) {
                HelpArticleVo faq = toArticle(row);
                if (faq.getSlug() == null || faq.getSlug().isBlank()) {
                    faq.setSlug("faq-" + Math.abs(faq.getTitle().hashCode()));
                }
                faq.setLocale("zh_CN");
                result.add(faq);
            }
        }
        result.sort(Comparator.comparing(HelpArticleVo::getUpdateTime,
            Comparator.nullsLast(Comparator.reverseOrder())));
        return result;
    }

    /** 文章详情:只返回白名单字段;正文取 parsed_text,不做任何内部字段透出。 */
    public HelpArticleVo detail(String channelCode, String slug) {
        CsChannel channel = channelGuard.require(channelCode);
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
        return toArticle(row);
    }

    /**
     * F1 站内搜索:走现有客服检索管线(SQL 已下推 publish_status=PUBLISHED),
     * 按文档合并多 chunk 命中为一条,响应只含标题/摘要/slug。
     */
    public List<HelpArticleVo> search(String channelCode, String query, String locale) {
        CsChannel channel = channelGuard.require(channelCode);
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
            vo.setLocale(locale == null ? "zh_CN" : locale);
            vo.setSource("doc");
            result.add(vo);
        }
        return result;
    }

    /** Was this helpful? 按 IP+渠道+文章+自然日去重,24h TTL 自动清理。 */
    public HelpFeedbackResultVo feedback(String channelCode, String slug, boolean helpful, String ip) {
        channelGuard.require(channelCode);
        String day = LocalDate.now().format(DAY);
        String key = FB_PREFIX + channelCode + ":" + (ip == null ? "unknown" : ip)
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

    /** sitemap:列出已发布文档 slug(公开可索引),不含 FAQ 与内部字段。 */
    public List<String> sitemapSlugs(String channelCode) {
        CsChannel channel = channelGuard.require(channelCode);
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
}
