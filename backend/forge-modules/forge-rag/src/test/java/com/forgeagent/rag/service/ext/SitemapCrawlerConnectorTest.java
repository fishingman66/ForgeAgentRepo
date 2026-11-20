package com.forgeagent.rag.service.ext;

import com.forgeagent.common.core.exception.ServiceException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P3 / B2:Sitemap 摄取的可测部分。
 *
 * <p>不做真实网络抓取(单测不应依赖外网),聚焦:<b>配置边界夹取</b>、
 * <b>robots 规则解析与最长匹配</b>、<b>externalId 定长稳定性</b>、<b>同域限制</b>。</p>
 *
 * @author ForgeAgent
 */
@Tag("dev")
@DisplayName("Sitemap 摄取")
class SitemapCrawlerConnectorTest {

    private final SitemapCrawlerConnector connector = new SitemapCrawlerConnector();

    @Test
    @DisplayName("连接器类型为 SITEMAP 且默认可用")
    void typeAndAvailability() {
        assertEquals("SITEMAP", connector.type());
        assertTrue(connector.available());
    }

    @Test
    @DisplayName("缺少 sitemapUrl 直接报错(不静默抓空)")
    void missingUrlRejected() {
        ServiceException ex = assertThrows(ServiceException.class,
            () -> SitemapCrawlerConnector.sitemapUrlOf("{}"));
        assertTrue(ex.getMessage().contains("sitemapUrl"));
    }

    @Test
    @DisplayName("非 http/https 的 sitemapUrl 被拒")
    void nonHttpRejected() {
        assertThrows(ServiceException.class,
            () -> SitemapCrawlerConnector.sitemapUrlOf("{\"sitemapUrl\":\"file:///etc/passwd\"}"));
        assertThrows(ServiceException.class,
            () -> SitemapCrawlerConnector.sitemapUrlOf("{\"sitemapUrl\":\"ftp://x/s.xml\"}"));
    }

    @Test
    @DisplayName("兼容 url 字段写法并去除首尾空白")
    void urlAliasAccepted() {
        assertEquals("https://a.com/sitemap.xml",
            SitemapCrawlerConnector.sitemapUrlOf("{\"url\":\"  https://a.com/sitemap.xml  \"}"));
    }

    @Test
    @DisplayName("maxPages / maxDepth / intervalMs 超出硬上限被夹取")
    void limitsClamped() {
        SitemapCrawlerConnector.Config cfg = SitemapCrawlerConnector.Config.parse(
            "{\"sitemapUrl\":\"https://a.com/s.xml\",\"maxPages\":99999,\"maxDepth\":99,\"intervalMs\":99999}");
        assertEquals(200, cfg.maxPages());
        assertEquals(3, cfg.maxDepth());
        assertEquals(2000, cfg.intervalMs());

        SitemapCrawlerConnector.Config low = SitemapCrawlerConnector.Config.parse(
            "{\"sitemapUrl\":\"https://a.com/s.xml\",\"maxPages\":0,\"maxDepth\":0,\"intervalMs\":-5}");
        assertEquals(1, low.maxPages());
        assertEquals(1, low.maxDepth());
        assertEquals(0, low.intervalMs());
    }

    @Test
    @DisplayName("默认值:深度2、50页、200ms、尊重robots、同域")
    void defaults() {
        SitemapCrawlerConnector.Config cfg = SitemapCrawlerConnector.Config.parse(
            "{\"sitemapUrl\":\"https://a.com/s.xml\"}");
        assertEquals(2, cfg.maxDepth());
        assertEquals(50, cfg.maxPages());
        assertEquals(200, cfg.intervalMs());
        assertTrue(cfg.respectRobots());
        assertTrue(cfg.sameHostOnly());
    }

    @Test
    @DisplayName("robots:* 段与具名段都会被收集,其他 UA 段被忽略")
    void robotsRulesParsed() {
        String robots = String.join("\n",
            "User-agent: Googlebot",
            "Disallow: /google-only",
            "",
            "User-agent: *",
            "Disallow: /private/",
            "Allow: /private/public/",
            "",
            "User-agent: ForgeAgent",
            "Disallow: /agent-blocked");
        List<String> rules = connector.rulesFor("ForgeAgent", robots);
        assertTrue(rules.contains("DISALLOW:/private/"));
        assertTrue(rules.contains("ALLOW:/private/public/"));
        assertTrue(rules.contains("DISALLOW:/agent-blocked"));
        assertFalse(rules.contains("DISALLOW:/google-only"), "其他 UA 的规则不应生效");
    }

    @Test
    @DisplayName("robots 最长匹配优先:Allow 长的放行,Disallow 长的拦截")
    void robotsLongestMatchWins() {
        String robots = String.join("\n",
            "User-agent: *",
            "Disallow: /private/",
            "Allow: /private/public/");
        List<String> rules = connector.rulesFor("ForgeAgent", robots);
        assertTrue(allowed(rules, "/private/public/doc.html"), "更长的 Allow 应放行");
        assertFalse(allowed(rules, "/private/secret.html"), "只命中 Disallow 应拦截");
    }

    @Test
    @DisplayName("robots 通配与结尾锚定")
    void robotsWildcardAndAnchor() {
        assertTrue(connector.matchesRobots("/*.pdf$", "/files/a.pdf"));
        assertFalse(connector.matchesRobots("/*.pdf$", "/files/a.pdf?x=1"));
        assertTrue(connector.matchesRobots("/tmp/*", "/tmp/anything"));
        assertFalse(connector.matchesRobots("/tmp/", "/other/tmp/x"));
    }

    @Test
    @DisplayName("无匹配规则视为允许")
    void noRuleMeansAllowed() {
        List<String> rules = connector.rulesFor("ForgeAgent", "User-agent: *\nDisallow: /admin/");
        assertTrue(allowed(rules, "/docs/index.html"));
    }

    @Test
    @DisplayName("externalId 定长、稳定、不同 URL 不同值(URL 超 200 字符也不撑破唯一键)")
    void externalIdStableAndBounded() {
        String longUrl = "https://example.com/" + "a".repeat(400);
        String first = connector.externalIdOf(longUrl);
        String second = connector.externalIdOf(longUrl);
        assertEquals(first, second);
        assertTrue(first.length() <= 64, "externalId 必须定长且远小于 200 字符上限");
        assertFalse(first.equals(connector.externalIdOf("https://example.com/b")));
    }

    @Test
    @DisplayName("同域判断:不同 host 被过滤,端口不影响")
    void sameHostCheck() {
        URI base = URI.create("https://a.com/sitemap.xml");
        assertTrue(connector.sameHost(base, "https://a.com/doc"));
        assertTrue(connector.sameHost(base, "http://a.com:8080/doc"));
        assertFalse(connector.sameHost(base, "https://b.com/doc"));
    }

    /** 复用生产口径判定某路径是否放行 */
    private boolean allowed(List<String> rules, String target) {
        String bestRule = null;
        boolean bestAllow = false;
        for (String rule : rules) {
            boolean allow = rule.startsWith("ALLOW:");
            String pattern = rule.substring(rule.indexOf(':') + 1);
            if (pattern.isEmpty()) {
                continue;
            }
            if (connector.matchesRobots(pattern, target) && (bestRule == null || pattern.length() > bestRule.length())) {
                bestRule = pattern;
                bestAllow = allow;
            }
        }
        return bestRule == null || bestAllow;
    }

    /** 防回归:ensure 连接器不被误改为可选 */
    @Test
    @DisplayName("骨架连接器(S3/Confluence)声明为不可用,避免可登记但同步必失败")
    void skeletonConnectorsUnavailable() {
        assertFalse(new S3ExtSourceConnector().available());
        assertFalse(new ConfluenceExtSourceConnector().available());
    }
}
