package com.forgeagent.rag.service.ext;

import com.forgeagent.common.core.exception.ServiceException;
import org.springframework.util.StringUtils;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * R5-4 URL 抓取的 SSRF 守护 + 正文抽取(纯静态,复用工作流 http 节点的防护语义)。
 *
 * <p>仅允许 http/https;DNS 解析后拒绝回环/私网/链路本地/通配/组播地址;
 * <b>禁用重定向</b>(302 后二次解析是已知绕过面);响应体大小与超时硬上限。</p>
 *
 * @author ForgeAgent
 */
public final class SsrfGuard {

    /**
     * 响应体上限 5MB:外部页面可能是任意大小,超过直接拒绝
     */
    public static final int MAX_BYTES = 5 * 1024 * 1024;

    private static final Pattern TITLE = Pattern.compile("<title[^>]*>(.*?)</title>",
        Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern SCRIPT_STYLE = Pattern.compile(
        "<(script|style|noscript)[^>]*>.*?</\\1>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern TAG = Pattern.compile("<[^>]+>");
    private static final Pattern BLANK = Pattern.compile("\\n{3,}");

    private SsrfGuard() {
    }

    /**
     * 校验目标地址;不通过直接抛业务异常
     */
    public static void verify(String url) {
        URI uri;
        try {
            uri = URI.create(url);
        } catch (Exception e) {
            throw new ServiceException("URL 非法:" + url);
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase();
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            throw new ServiceException("仅允许 http/https 协议");
        }
        String host = uri.getHost();
        if (!StringUtils.hasText(host)) {
            throw new ServiceException("请求地址缺少主机名");
        }
        InetAddress address;
        try {
            address = InetAddress.getByName(host);
        } catch (Exception e) {
            throw new ServiceException("目标主机无法解析:" + host);
        }
        if (address.isLoopbackAddress() || address.isAnyLocalAddress() || address.isLinkLocalAddress()
            || address.isSiteLocalAddress() || address.isMulticastAddress()) {
            throw new ServiceException("禁止访问内网或保留地址");
        }
    }

    /**
     * 抓取并抽取正文(HTML 去脚本/样式/标签;纯文本原样)
     */
    public static FetchedPage fetch(String url) {
        verify(url);
        try {
            HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(10))
                .build();
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(20))
                .header("User-Agent", "ForgeAgent-KB/1.0")
                .GET()
                .build();
            HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() >= 300 && response.statusCode() < 400) {
                throw new ServiceException("目标地址发生重定向(" + response.statusCode() + "),出于安全已禁用重定向,请直接填写最终地址");
            }
            if (response.statusCode() != 200) {
                throw new ServiceException("抓取失败,HTTP " + response.statusCode());
            }
            byte[] bytes = readLimited(response.body());
            String raw = new String(bytes, StandardCharsets.UTF_8);
            String contentType = response.headers().firstValue("content-type").orElse("");
            String title = url;
            String text;
            if (contentType.contains("html") || raw.trim().startsWith("<")) {
                Matcher m = TITLE.matcher(raw);
                if (m.find()) {
                    title = TAG.matcher(m.group(1)).replaceAll("").trim();
                }
                text = toPlainText(raw);
            } else {
                text = raw;
            }
            if (!StringUtils.hasText(text)) {
                throw new ServiceException("抓取结果为空(可能是纯前端渲染页面)");
            }
            return new FetchedPage(title, text);
        } catch (ServiceException e) {
            throw e;
        } catch (Exception e) {
            throw new ServiceException("抓取失败:" + e.getMessage());
        }
    }

    private static byte[] readLimited(InputStream in) throws Exception {
        try (InputStream input = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int total = 0;
            int read;
            while ((read = input.read(buf)) > 0) {
                total += read;
                if (total > MAX_BYTES) {
                    throw new ServiceException("页面超过 " + (MAX_BYTES / 1024 / 1024) + "MB 上限,已中止");
                }
                out.write(buf, 0, read);
            }
            return out.toByteArray();
        }
    }

    /**
     * HTML → 纯文本(去脚本样式与标签,压空行)
     */
    public static String toPlainText(String html) {
        String s = SCRIPT_STYLE.matcher(html).replaceAll(" ");
        s = s.replaceAll("(?i)<br\\s*/?>", "\n").replaceAll("(?i)</p>", "\n");
        s = TAG.matcher(s).replaceAll(" ");
        s = s.replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<")
            .replace("&gt;", ">").replace("&quot;", "\"");
        s = s.replaceAll("[ \\t\\x0B\\f\\r]+", " ");
        s = s.replaceAll(" *\\n *", "\n");
        return BLANK.matcher(s).replaceAll("\n\n").trim();
    }

    /**
     * 抓取结果
     */
    public record FetchedPage(String title, String text) {
    }
}
