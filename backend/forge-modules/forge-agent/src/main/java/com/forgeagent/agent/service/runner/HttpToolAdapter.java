package com.forgeagent.agent.service.runner;

import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.message.ToolResultBlock;
import lombok.extern.slf4j.Slf4j;
import com.forgeagent.common.core.utils.StringUtils;
import com.forgeagent.common.json.utils.JsonUtils;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 平台 HTTP 工具的 AgentScope 适配器
 *
 * <p>把 agent_tool 注册表中的 http 类型工具包装为内核 AgentTool:
 * LLM 传入的参数经模板 {{key}} 占位符渲染后真实发起 HTTP 调用。</p>
 *
 * @author ForgeAgent
 */
@Slf4j
record HttpToolAdapter(ToolDef def) implements AgentTool {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{\\s*(\\w+)\\s*}}");

    /**
     * 连接超时(毫秒):避免模型给出的错误地址长时间挂起执行线程
     */
    private static final int CONNECT_TIMEOUT_MS = 5000;

    /**
     * 读取超时(毫秒)
     */
    private static final int READ_TIMEOUT_MS = 30000;

    /**
     * 响应体最大保留字符(超出截断,避免撑爆模型上下文)
     */
    private static final int MAX_RESPONSE_CHARS = 20000;

    private static final RestClient CLIENT = buildClient();

    /**
     * 构建带超时的 HTTP 客户端(默认 RestClient 无超时,慢响应会长期占用线程)
     */
    private static RestClient buildClient() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(CONNECT_TIMEOUT_MS);
        factory.setReadTimeout(READ_TIMEOUT_MS);
        return RestClient.builder().requestFactory(factory).build();
    }

    /**
     * 工具定义(运行时装配快照)
     */
    record ToolDef(Long id, String name, String description, String httpMethod,
                   String httpUrl, String httpHeaders, String httpParams) {
    }

    @Override
    public String getName() {
        return def.name();
    }

    @Override
    public String getDescription() {
        return def.description() == null ? "" : def.description();
    }

    @Override
    public Map<String, Object> getParameters() {
        // 从地址与参数模板中提取 {{key}} 占位符,生成 string 类型的 JSON Schema
        Map<String, Object> properties = new LinkedHashMap<>();
        collectPlaceholders(def.httpUrl(), properties);
        collectPlaceholders(def.httpParams(), properties);
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of());
        return schema;
    }

    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        Map<String, Object> input = param.getInput() == null ? Map.of() : param.getInput();
        return Mono.fromCallable(() -> execute(input)).subscribeOn(Schedulers.boundedElastic());
    }

    private ToolResultBlock execute(Map<String, Object> input) {
        try {
            HttpMethod method = HttpMethod.valueOf(StringUtils.defaultIfBlank(def.httpMethod(), "GET"));
            String url = buildUrl(method, input);
            checkUrlAllowed(url);
            RestClient.RequestBodySpec spec = CLIENT.method(method).uri(url);
            applyHeaders(spec, input);
            RestClient.ResponseSpec response;
            if (method == HttpMethod.POST || method == HttpMethod.PUT) {
                String body = StringUtils.isNotBlank(def.httpParams())
                    ? render(def.httpParams(), input) : JsonUtils.toJsonString(input);
                response = spec.contentType(MediaType.APPLICATION_JSON).body(body).retrieve();
            } else {
                response = spec.retrieve();
            }
            String result = response.body(String.class);
            if (StringUtils.isBlank(result)) {
                return ToolResultBlock.text("(空响应)");
            }
            if (result.length() > MAX_RESPONSE_CHARS) {
                result = result.substring(0, MAX_RESPONSE_CHARS) + "\n...(响应过长已截断)";
            }
            return ToolResultBlock.text(result);
        } catch (Exception e) {
            log.warn("[HttpTool] {} 调用失败: {}", def.name(), e.getMessage());
            return ToolResultBlock.error("工具调用失败: " + e.getMessage());
        }
    }

    /**
     * 地址安全校验:仅允许 http/https,且默认禁止访问本机/内网/链路本地地址(防 SSRF)
     * 需要访问内网服务时可通过 -Dagent.http-tool.allow-private-network=true 显式放开
     */
    private void checkUrlAllowed(String url) throws java.net.UnknownHostException {
        URI uri;
        try {
            uri = URI.create(url);
        } catch (Exception e) {
            throw new IllegalArgumentException("工具地址格式非法: " + url);
        }
        String scheme = uri.getScheme();
        if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
            throw new IllegalArgumentException("工具地址仅支持 http/https 协议");
        }
        String host = uri.getHost();
        if (StringUtils.isBlank(host)) {
            throw new IllegalArgumentException("工具地址缺少主机名");
        }
        if (Boolean.getBoolean("agent.http-tool.allow-private-network")
            || "true".equalsIgnoreCase(System.getenv("AGENT_HTTP_TOOL_ALLOW_PRIVATE"))) {
            return;
        }
        for (InetAddress addr : InetAddress.getAllByName(host)) {
            if (isBlockedAddress(addr)) {
                throw new IllegalArgumentException("工具地址不允许访问本机或内网地址: " + host);
            }
        }
    }

    /**
     * 判断地址是否属于本机/内网/链路本地等禁止访问的网段
     */
    private boolean isBlockedAddress(InetAddress addr) {
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
     * 渲染 URL 模板;GET/DELETE 场景下未在模板中消费的参数转为查询串
     */
    private String buildUrl(HttpMethod method, Map<String, Object> input) {
        String url = render(def.httpUrl(), input);
        if (method == HttpMethod.POST || method == HttpMethod.PUT) {
            return url;
        }
        String consumedUrl = def.httpUrl() == null ? "" : def.httpUrl();
        // 模板中已消费的占位符(容许 {{ key }} 带空格)不再追加到查询串
        List<String> consumedKeys = new ArrayList<>();
        Matcher consumedMatcher = PLACEHOLDER.matcher(consumedUrl);
        while (consumedMatcher.find()) {
            consumedKeys.add(consumedMatcher.group(1));
        }
        StringBuilder sb = new StringBuilder(url);
        List<String> pairs = new ArrayList<>();
        input.forEach((k, v) -> {
            if (!consumedKeys.contains(k) && !consumedUrl.contains("{" + k + "}")) {
                pairs.add(k + "=" + v);
            }
        });
        if (!pairs.isEmpty()) {
            sb.append(url.contains("?") ? '&' : '?').append(String.join("&", pairs));
        }
        return sb.toString();
    }

    private void applyHeaders(RestClient.RequestBodySpec spec, Map<String, Object> input) {
        if (StringUtils.isBlank(def.httpHeaders())) {
            return;
        }
        Map<String, Object> headers = JsonUtils.parseMap(def.httpHeaders());
        if (headers != null) {
            headers.forEach((k, v) -> spec.header(k, render(String.valueOf(v), input)));
        }
    }

    private void collectPlaceholders(String template, Map<String, Object> properties) {
        if (StringUtils.isBlank(template)) {
            return;
        }
        Matcher matcher = PLACEHOLDER.matcher(template);
        while (matcher.find()) {
            properties.putIfAbsent(matcher.group(1), Map.of("type", "string"));
        }
    }

    private String render(String template, Map<String, Object> input) {
        if (StringUtils.isBlank(template) || input.isEmpty()) {
            return template;
        }
        // 按占位符正则渲染,兼容 {{key}} 与 {{ key }} 写法;未传参的占位符保留原样
        Matcher matcher = PLACEHOLDER.matcher(template);
        StringBuilder sb = new StringBuilder();
        while (matcher.find()) {
            Object value = input.get(matcher.group(1));
            matcher.appendReplacement(sb,
                value == null ? Matcher.quoteReplacement(matcher.group(0))
                    : Matcher.quoteReplacement(String.valueOf(value)));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }
}
