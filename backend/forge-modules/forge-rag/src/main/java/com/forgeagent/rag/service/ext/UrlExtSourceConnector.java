package com.forgeagent.rag.service.ext;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.forgeagent.common.core.exception.ServiceException;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * R5-4 URL 外部源:抓取单页并抽正文(SSRF 防护见 {@link SsrfGuard})。
 *
 * <p>配置:{"url":"https://example.com/doc"}。页面必须服务端渲染(纯前端渲染的页面抓不到正文,
 * 会明确报错而不是静默入空文档)。</p>
 *
 * @author ForgeAgent
 */
@Component
public class UrlExtSourceConnector implements ExtSourceConnector {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public String type() {
        return "URL";
    }

    @Override
    public List<ExtDocument> fetch(String configJson) {
        String url = urlOf(configJson);
        SsrfGuard.FetchedPage page = SsrfGuard.fetch(url);
        return List.of(new ExtDocument(url, page.title(), page.text()));
    }

    /**
     * 从配置 JSON 取 url(登记时做 SSRF 预校验也复用本方法)
     */
    public static String urlOf(String configJson) {
        try {
            JsonNode root = MAPPER.readTree(configJson == null ? "{}" : configJson);
            String url = root.path("url").asText("");
            if (url.isBlank()) {
                throw new ServiceException("URL 来源缺少 url 配置");
            }
            return url.trim();
        } catch (ServiceException e) {
            throw e;
        } catch (Exception e) {
            throw new ServiceException("URL 来源配置不是合法 JSON");
        }
    }
}
