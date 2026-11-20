package com.forgeagent.rag.service.ext;

import com.forgeagent.common.core.exception.ServiceException;
import org.springframework.util.StringUtils;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.Inet4Address;
import java.net.Inet6Address;
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
 * <p>仅允许 http/https;DNS 解析后拒绝回环/私网/链路本地/通配/组播地址,并补齐
 * <b>CGNAT 100.64/10</b> 与 <b>IPv6 ULA fc00::/7</b>(旧实现只看 IPv4 私有段,这两类可绕过);
 * <b>禁用重定向</b>(302 后二次解析是已知绕过面);响应体大小与超时硬上限。</p>
 *
 * <p>已知边界:DNS 只解析一次,发请求时 JDK 会再解析一次,理论上存在 DNS rebinding 窗口
 * (与工作流 http 节点同口径,已在 docs 记录;需连接级 pin IP 才能根治)。</p>
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
     * 校验目标地址;不通过直接抛业务异常。
     *
     * <p>遍历该主机的<b>全部 A/AAAA 记录</b>:只要有一条落在禁网段就拒绝,
     * 避免"多记录里混一条内网"的绕过。</p>
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
        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(host);
        } catch (Exception e) {
            throw new ServiceException("目标主机无法解析:" + host);
        }
        if (addresses == null || addresses.length == 0) {
            throw new ServiceException("目标主机无法解析:" + host);
        }
        for (InetAddress address : addresses) {
            if (isBlocked(address)) {
                throw new ServiceException("禁止访问内网或保留地址:" + host);
            }
        }
    }

    /**
     * 判断地址是否属于本机/内网/保留网段。
     *
     * <p>{@code isSiteLocalAddress} 只覆盖 10/8、172.16/12、192.168/16,
     * 不覆盖 CGNAT 与 IPv6 ULA,故单独判定。</p>
     */
    public static boolean isBlocked(InetAddress addr) {
        if (addr.isAnyLocalAddress() || addr.isLoopbackAddress() || addr.isLinkLocalAddress()
            || addr.isSiteLocalAddress() || addr.isMulticastAddress()) {
            return true;
        }
        byte[] bytes = addr.getAddress();
        if (addr instanceof Inet4Address) {
            // 100.64.0.0/10 运营商级 NAT 网段
            return (bytes[0] & 0xff) == 100 && (bytes[1] & 0xc0) == 64;
        }
        if (addr instanceof Inet6Address) {
            // fc00::/7 唯一本地地址
            return (bytes[0] & 0xfe) == 0xfc;
        }
        return false;
    }

    /**
     * 发起一次 GET,返回<b>原始响应</b>(不校验状态码)。
     *
     * <p>给需要区分 404 的调用方用(如 robots.txt:404 表示"无限制",而不是抓取失败)。
     * 仍然执行 {@link #verify(String)}、禁用重定向、限制 5MB。</p>
     */
    public static RawPage get(String url) {
        verify(url);
        try {
            HttpResponse<InputStream> response = send(url);
            byte[] bytes = readLimited(response.body());
            return new RawPage(response.statusCode(),
                response.headers().firstValue("content-type").orElse(""), bytes);
        } catch (ServiceException e) {
            throw e;
        } catch (Exception e) {
            throw new ServiceException("抓取失败:" + e.getMessage());
        }
    }

    private static HttpResponse<InputStream> send(String url) throws Exception {
        HttpClient client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(10))
            .build();
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(20))
            .header("User-Agent", "ForgeAgent-KB/1.0")
            .GET()
            .build();
        return client.send(request, HttpResponse.BodyHandlers.ofInputStream());
    }

    /**
     * 抓取并抽取正文(HTML 去脚本/样式/标签;纯文本原样)。
     *
     * <p>3xx 与 非 200 一律失败:重定向会引入"校验过的地址 ≠ 实际访问的地址"。</p>
     */
    public static FetchedPage fetch(String url) {
        verify(url);
        try {
            HttpResponse<InputStream> response = send(url);
            if (response.statusCode() >= 300 && response.statusCode() < 400) {
                throw new ServiceException("目标地址发生重定向(" + response.statusCode()
                    + "),出于安全已禁用重定向,请直接填写最终地址");
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

    /**
     * 原始响应(不解析正文、不校验状态码)
     */
    public record RawPage(int status, String contentType, byte[] body) {

        /** 按 UTF-8 解成文本(项目内既有页面的既定口径) */
        public String text() {
            return new String(body == null ? new byte[0] : body, StandardCharsets.UTF_8);
        }
    }
}
