package com.forgeagent.rag.service.ext;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.forgeagent.common.core.exception.ServiceException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * P3 / B2:Sitemap 全站摄取连接器。
 *
 * <p>流程:解析 sitemap(.xml 或 sitemapindex 递归,{@code maxDepth} 控制嵌套层数)
 * → 逐页抓正文(复用 {@link SsrfGuard},含内网/回环拦截、禁重定向、5MB 上限)
 * → 返回条目交由 {@code KbExtSourceServiceImpl} 逐条 {@code importText}(MD5 去重 +
 * {@code uq_ext_item(source_id, external_id)} 幂等)。</p>
 *
 * <p>刻意<b>不做</b>页面内链接扩张:sitemap 本身就是站点地图,再从页面抽链接会放大抓取量
 * 且需要保留原始 HTML(当前抓取器只产出正文)。这是与"BFS 限深"表述的差别,已在文档记录。</p>
 *
 * <p>安全与礼貌:</p>
 * <ul>
 *   <li>robots.txt <b>fail-closed</b>:拉取失败(超时/5xx)整批拒绝;404 视为"无限制"(标准语义);</li>
 *   <li>每页间隔可配,总时长有预算,超预算提前收尾返回已抓结果;</li>
 *   <li>{@code externalId} 用 URL 的 SHA-256 定长摘要,避免 URL 超长撑破唯一键(200 字符)。</li>
 * </ul>
 *
 * @author ForgeAgent
 */
@Slf4j
@Component
public class SitemapCrawlerConnector implements ExtSourceConnector {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 硬上限:防止一份配置把整站拖垮 */
    private static final int HARD_MAX_PAGES = 200;
    private static final int HARD_MAX_DEPTH = 3;
    private static final int MAX_INTERVAL_MS = 2000;
    /** 单次同步的整体时间预算(秒):避免长事务把连接占死 */
    private static final long TOTAL_BUDGET_MS = 120_000L;

    @Override
    public String type() {
        return "SITEMAP";
    }

    @Override
    public List<ExtDocument> fetch(String configJson) {
        Config cfg = Config.parse(configJson);
        SsrfGuard.verify(cfg.sitemapUrl());

        if (cfg.respectRobots() && !robotsAllows(cfg.sitemapUrl())) {
            throw new ServiceException("目标站点 robots.txt 不允许抓取该路径,已按合规要求中止");
        }

        Set<String> pageUrls = collectPageUrls(cfg);

        List<ExtDocument> docs = new ArrayList<>();
        long deadline = System.currentTimeMillis() + TOTAL_BUDGET_MS;
        int index = 0;
        for (String url : pageUrls) {
            if (System.currentTimeMillis() > deadline) {
                log.warn("[Sitemap] 超出时间预算,已抓 {} / {} 页,余下留待下次同步", docs.size(), pageUrls.size());
                break;
            }
            if (index > 0 && cfg.intervalMs() > 0) {
                sleepQuietly(cfg.intervalMs());
            }
            index++;
            try {
                SsrfGuard.FetchedPage page = SsrfGuard.fetch(url);
                docs.add(new ExtDocument(externalIdOf(url), titleOf(page, url), page.text()));
            } catch (Exception e) {
                // 单页失败不拖垮整批:sitemap 里常有已下线的死链
                log.warn("[Sitemap] 页面抓取失败,已跳过 {}:{}", url, e.getMessage());
            }
        }
        if (docs.isEmpty()) {
            throw new ServiceException("Sitemap 未抓到任何可用页面(检查 sitemap 地址、robots 限制或页面是否服务端渲染)");
        }
        log.info("[Sitemap] 完成:候选 {} 页,成功 {} 页", pageUrls.size(), docs.size());
        return docs;
    }

    /**
     * 解析 sitemap(含 sitemapindex 递归),返回去重后的页面 URL 列表。
     */
    private Set<String> collectPageUrls(Config cfg) {
        Set<String> pages = new LinkedHashSet<>();
        Set<String> visitedSitemaps = new LinkedHashSet<>();
        Deque<Level> queue = new ArrayDeque<>();
        queue.add(new Level(cfg.sitemapUrl(), 1));
        URI base = URI.create(cfg.sitemapUrl());

        while (!queue.isEmpty() && pages.size() < cfg.maxPages()) {
            Level level = queue.poll();
            if (!visitedSitemaps.add(level.url())) {
                continue;
            }
            SitemapDoc doc = parseSitemap(level.url());
            if (doc.index()) {
                if (level.depth() >= cfg.maxDepth()) {
                    log.info("[Sitemap] 达到最大嵌套深度 {},忽略子 sitemap {} 个", cfg.maxDepth(), doc.locs().size());
                    continue;
                }
                for (String child : doc.locs()) {
                    if (sameHost(base, child) || !cfg.sameHostOnly()) {
                        queue.add(new Level(child, level.depth() + 1));
                    }
                }
            } else {
                for (String loc : doc.locs()) {
                    if (pages.size() >= cfg.maxPages()) {
                        break;
                    }
                    if (sameHost(base, loc) || !cfg.sameHostOnly()) {
                        pages.add(loc);
                    }
                }
            }
        }
        if (pages.isEmpty()) {
            throw new ServiceException("Sitemap 中没有可用页面地址(或全部被同域限制过滤)");
        }
        return pages;
    }

    private SitemapDoc parseSitemap(String url) {
        SsrfGuard.RawPage raw = SsrfGuard.get(url);
        if (raw.status() != 200) {
            throw new ServiceException("Sitemap 抓取失败,HTTP " + raw.status() + ":" + url);
        }
        String body = raw.text();
        if (body.contains("<html") || body.contains("<HTML")) {
            throw new ServiceException("该地址返回 HTML 而不是 sitemap XML:" + url);
        }
        Document document = parseXml(body, url);
        Element root = document.getDocumentElement();
        String rootName = root == null ? "" : root.getNodeName().toLowerCase(Locale.ROOT);
        boolean isIndex = rootName.endsWith("sitemapindex");
        if (!isIndex && !rootName.endsWith("urlset")) {
            throw new ServiceException("无法识别的 sitemap 根节点 <" + rootName + ">");
        }
        List<String> locs = new ArrayList<>();
        NodeList nodes = document.getElementsByTagName("loc");
        for (int i = 0; i < nodes.getLength(); i++) {
            Node node = nodes.item(i);
            String text = node.getTextContent();
            if (text == null || text.isBlank()) {
                continue;
            }
            String loc = decodeXmlEntities(text.trim());
            if (loc.startsWith("http://") || loc.startsWith("https://")) {
                locs.add(loc);
            }
        }
        return new SitemapDoc(isIndex, locs);
    }

    /**
     * XXE 防护:禁用 DOCTYPE 与外部实体(sitemap 不需要 DTD)。
     */
    private Document parseXml(String body, String url) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            DocumentBuilder builder = factory.newDocumentBuilder();
            return builder.parse(new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new ServiceException("Sitemap XML 解析失败(" + url + "):" + e.getMessage());
        }
    }

    // ---------------- robots.txt ----------------

    /**
     * 判断 sitemap 入口路径是否被 robots.txt 允许。
     *
     * <p>语义:404/410 → 视为无限制;其他非 200(含超时/5xx)→ <b>拒绝</b>(fail-closed,
     * 否则"取不到 robots 就全站随便爬");解析失败同样拒绝。</p>
     */
    boolean robotsAllows(String url) {
        URI uri = URI.create(url);
        String robotsUrl = uri.getScheme() + "://" + uri.getHost()
            + (uri.getPort() > 0 ? ":" + uri.getPort() : "") + "/robots.txt";
        SsrfGuard.RawPage raw;
        try {
            raw = SsrfGuard.get(robotsUrl);
        } catch (Exception e) {
            throw new ServiceException("robots.txt 拉取失败,按合规要求中止抓取:" + e.getMessage());
        }
        if (raw.status() == 404 || raw.status() == 410) {
            return true;
        }
        if (raw.status() != 200) {
            throw new ServiceException("robots.txt 返回 HTTP " + raw.status() + ",按合规要求中止抓取");
        }
        List<String> rules = rulesFor(robotAgent(), raw.text());
        String path = uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath();
        String query = uri.getRawQuery();
        String target = query == null ? path : path + "?" + query;

        String bestRule = null;
        boolean bestAllow = false;
        for (String rule : rules) {
            boolean allow = rule.startsWith("ALLOW:");
            String pattern = rule.substring(rule.indexOf(':') + 1);
            if (pattern.isEmpty()) {
                continue;
            }
            if (matchesRobots(pattern, target) && (bestRule == null || pattern.length() > bestRule.length())) {
                bestRule = pattern;
                bestAllow = allow;
            }
        }
        return bestRule == null || bestAllow;
    }

    private String robotAgent() {
        return "ForgeAgent";
    }

    /**
     * 提取适用于本单位 UA 的规则:"User-agent: *" 与 "User-agent: ForgeAgent" 两段都收。
     */
    List<String> rulesFor(String agent, String robots) {
        List<String> rules = new ArrayList<>();
        boolean applicable = false;
        boolean inAgentBlock = false;
        for (String rawLine : robots.split("\\r?\\n")) {
            String line = rawLine;
            int hash = line.indexOf('#');
            if (hash >= 0) {
                line = line.substring(0, hash);
            }
            line = line.trim();
            if (line.isEmpty()) {
                continue;
            }
            int colon = line.indexOf(':');
            if (colon <= 0) {
                continue;
            }
            String key = line.substring(0, colon).trim().toLowerCase(Locale.ROOT);
            String value = line.substring(colon + 1).trim();
            if ("user-agent".equals(key)) {
                if (inAgentBlock) {
                    // 连续 UA 行属于同一组
                    applicable = applicable || isOurAgent(value, agent);
                } else {
                    applicable = isOurAgent(value, agent);
                    inAgentBlock = true;
                }
                continue;
            }
            inAgentBlock = false;
            if (!applicable) {
                continue;
            }
            if ("disallow".equals(key) && !value.isEmpty()) {
                rules.add("DISALLOW:" + value);
            } else if ("allow".equals(key) && !value.isEmpty()) {
                rules.add("ALLOW:" + value);
            }
        }
        return rules;
    }

    private boolean isOurAgent(String value, String agent) {
        return "*".equals(value) || value.toLowerCase(Locale.ROOT).contains(agent.toLowerCase(Locale.ROOT));
    }

    /**
     * robots 路径匹配:{@code *} 通配,结尾 {@code $} 锚定。
     */
    boolean matchesRobots(String pattern, String path) {
        StringBuilder regex = new StringBuilder();
        boolean anchorEnd = pattern.endsWith("$");
        String body = anchorEnd ? pattern.substring(0, pattern.length() - 1) : pattern;
        for (char c : body.toCharArray()) {
            if (c == '*') {
                regex.append(".*");
            } else {
                regex.append(Pattern.quote(String.valueOf(c)));
            }
        }
        if (anchorEnd) {
            regex.append('$');
        }
        try {
            return Pattern.compile("^" + regex).matcher(path).find();
        } catch (Exception e) {
            return false;
        }
    }

    // ---------------- 工具 ----------------

    boolean sameHost(URI base, String candidate) {
        try {
            URI other = URI.create(candidate);
            return base.getHost() != null && base.getHost().equalsIgnoreCase(other.getHost());
        } catch (Exception e) {
            return false;
        }
    }

    /** externalId 定长摘要:URL 可能远超 uq_ext_item.external_id 的 200 字符 */
    String externalIdOf(String url) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(url.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder("sm:");
            for (int i = 0; i < 20; i++) {
                hex.append(String.format("%02x", digest[i]));
            }
            return hex.toString();
        } catch (Exception e) {
            return "sm:" + Integer.toHexString(url.hashCode());
        }
    }

    private String titleOf(SsrfGuard.FetchedPage page, String url) {
        String title = page.title();
        if (title == null || title.isBlank() || title.equals(url)) {
            String path = URI.create(url).getPath();
            title = path == null || path.isEmpty() || "/".equals(path) ? url : path;
        }
        return title.length() > 200 ? title.substring(0, 200) : title;
    }

    private String decodeXmlEntities(String text) {
        return text.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
            .replace("&quot;", "\"").replace("&apos;", "'");
    }

    private void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 从配置 JSON 取 sitemap 地址(登记阶段做 SSRF 预校验时复用)
     */
    public static String sitemapUrlOf(String configJson) {
        return Config.parse(configJson).sitemapUrl();
    }

    private record Level(String url, int depth) {
    }

    private record SitemapDoc(boolean index, List<String> locs) {
    }

    /**
     * 连接器配置。
     */
    record Config(String sitemapUrl, int maxDepth, int maxPages, int intervalMs,
                          boolean respectRobots, boolean sameHostOnly) {

        static Config parse(String configJson) {
            try {
                JsonNode root = MAPPER.readTree(configJson == null || configJson.isBlank() ? "{}" : configJson);
                String url = root.path("sitemapUrl").asText("");
                if (url.isBlank()) {
                    // 兼容把地址写在 url 字段的直觉写法
                    url = root.path("url").asText("");
                }
                if (url.isBlank()) {
                    throw new ServiceException("Sitemap 来源缺少 sitemapUrl 配置");
                }
                String trimmed = url.trim();
                if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
                    throw new ServiceException("sitemapUrl 仅支持 http/https");
                }
                int maxDepth = clamp(root.path("maxDepth").asInt(2), 1, HARD_MAX_DEPTH);
                int maxPages = clamp(root.path("maxPages").asInt(50), 1, HARD_MAX_PAGES);
                int intervalMs = clamp(root.path("intervalMs").asInt(200), 0, MAX_INTERVAL_MS);
                boolean respectRobots = root.path("respectRobots").asBoolean(true);
                boolean sameHostOnly = root.path("sameHostOnly").asBoolean(true);
                return new Config(trimmed, maxDepth, maxPages, intervalMs, respectRobots, sameHostOnly);
            } catch (ServiceException e) {
                throw e;
            } catch (Exception e) {
                throw new ServiceException("Sitemap 来源配置不是合法 JSON");
            }
        }

        private static int clamp(int value, int min, int max) {
            return Math.max(min, Math.min(max, value));
        }
    }
}
