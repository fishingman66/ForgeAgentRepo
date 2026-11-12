package com.forgeagent.rag.service.document;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.message.Base64Source;
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.Model;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import com.forgeagent.agentcore.service.AgentScopeModelFactory;
import com.forgeagent.common.core.utils.StringUtils;
import com.forgeagent.rag.config.KnowledgeProperties;
import com.forgeagent.rag.domain.AgentKnowledge;
import com.forgeagent.rag.service.support.KbVlmConfigResolver;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * R5-5 图片描述器:把解析阶段抽出的图片交给视觉模型生成描述,替换正文里的 {@code [[img:n]]} 标记。
 *
 * <p><b>成本与开关</b>:{@code rag.knowledge.vlm-enabled} 默认关;开启后每篇最多描述
 * {@link DocumentImage#MAX_IMAGES} 张、最小边长 {@link DocumentImage#MIN_PIXELS};
 * 同图(内容 MD5)描述走 Redis 复用 30 天,跨文档重复素材不重复烧钱。</p>
 *
 * <p><b>降级</b>:模型不可用/调用失败/超时都只把该图替换成「（未生成描述）」,不阻断解析;
 * 开关关闭时标记被整体剥离,正文与未开启该能力时完全一致。</p>
 *
 * @author ForgeAgent
 */
@Slf4j
@RequiredArgsConstructor
@Component
public class KbImageDescriber {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final Pattern MARKER = Pattern.compile("\\[\\[img=(\\d+)]]");

    private static final String CACHE_PREFIX = "agent:kb:imgdesc:";

    private static final Duration CACHE_TTL = Duration.ofDays(30);

    private static final String PROMPT = """
        请用中文客观描述这张图片,供知识库检索使用。要求:
        1) 先说明图片类型(流程图/截图/表格/照片/示意图);
        2) 概括图中关键信息(标题、步骤、字段名、数值),不要臆造看不清的内容;
        3) 不超过 150 字,不要出现"这张图片"以外的口语表述。
        """;

    private final KnowledgeProperties properties;
    private final KbVlmConfigResolver vlmConfigResolver;

    /**
     * 描述结果
     *
     * @param described 成功描述的图片数
     * @param failed    失败/跳过的图片数
     */
    public record Result(String text, int described, int failed) {
    }

    /**
     * 按标记把图片描述写进正文(开关关闭时只剥离标记)
     */
    public Result apply(String text, List<DocumentImage> images, AgentKnowledge kb, Long documentId) {
        if (text == null || text.isEmpty() || !MARKER.matcher(text).find()) {
            return new Result(text, 0, 0);
        }
        // 注意:实体字段是 Integer,不能用 Boolean.TRUE.equals(会恒为 false)
        boolean enabled = Boolean.TRUE.equals(properties.getVlmEnabled())
            && Integer.valueOf(1).equals(kb.getVlmEnabled());
        if (!enabled) {
            // 关闭状态:标记整体剥离,不改变正文语义
            return new Result(MARKER.matcher(text).replaceAll(""), 0, 0);
        }
        KbVlmConfigResolver.Resolved model = vlmConfigResolver.resolve(kb);
        if (model == null) {
            log.warn("[知识库] 已开启图片描述但未解析到可用模型"
                + "(工作空间配置 / 平台默认 / 全局 rag.knowledge.vlm-model 均未命中),本次仅剥离标记");
            return new Result(MARKER.matcher(text).replaceAll(""), 0, 0);
        }
        int described = 0;
        int failed = 0;
        Matcher matcher = MARKER.matcher(text);
        StringBuilder out = new StringBuilder(text.length());
        while (matcher.find()) {
            int index = Integer.parseInt(matcher.group(1));
            DocumentImage image = index >= 0 && index < images.size() ? images.get(index) : null;
            String replacement = "";
            if (image != null && image.describable(properties.getVlmMinPixels())) {
                persist(documentId, image);
                String desc = describe(model, image);
                if (StringUtils.isNotBlank(desc)) {
                    replacement = "\n【图片描述 " + image.locationLabel() + "】" + desc.trim() + "\n";
                    described++;
                } else {
                    replacement = "\n【图片 " + image.locationLabel() + "】（未生成描述）\n";
                    failed++;
                }
            }
            matcher.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(out);
        return new Result(out.toString(), described, failed);
    }

    /**
     * 单图描述:先查缓存(同图复用),再调用视觉模型
     */
    private String describe(KbVlmConfigResolver.Resolved model, DocumentImage image) {
        String md5 = PdfDocumentParser.hashOf(image.data());
        String cacheKey = CACHE_PREFIX + md5;
        try {
            Object cached = com.forgeagent.common.redis.utils.RedisUtils.getCacheObject(cacheKey);
            if (cached != null) {
                return String.valueOf(cached);
            }
        } catch (Exception e) {
            log.debug("[知识库] 图片描述缓存不可用,直接调用模型: {}", e.getMessage());
        }
        try {
            Model asModel = AgentScopeModelFactory.build(model.baseUrl(), model.apiKey(),
                model.modelName(), 0.0, 400);
            ReActAgent agent = ReActAgent.builder().name("kb-image-describer").model(asModel).maxIters(1).build();
            Msg msg = Msg.builder().name("user").role(MsgRole.USER)
                .content(TextBlock.builder().text(PROMPT).build())
                .content(ImageBlock.builder().source(Base64Source.builder()
                    .mediaType(image.mediaType())
                    .data(Base64.getEncoder().encodeToString(image.data()))
                    .build()).build())
                .build();
            Msg reply = agent.call(msg).block(Duration.ofSeconds(45));
            String text = reply == null ? null : reply.getTextContent();
            if (StringUtils.isBlank(text)) {
                return null;
            }
            String desc = text.strip();
            if (desc.length() > 400) {
                desc = desc.substring(0, 400);
            }
            try {
                com.forgeagent.common.redis.utils.RedisUtils.setCacheObject(cacheKey, desc, CACHE_TTL);
            } catch (Exception e) {
                log.debug("[知识库] 图片描述写缓存失败: {}", e.getMessage());
            }
            return desc;
        } catch (Exception e) {
            log.warn("[知识库] 图片描述模型调用失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 图片落盘留痕(供人工核对);失败不影响描述与解析
     */
    private void persist(Long documentId, DocumentImage image) {
        try {
            Path dir = Path.of(properties.getFileDir(), "images", String.valueOf(documentId));
            Files.createDirectories(dir);
            String ext = switch (image.mediaType()) {
                case "image/jpeg" -> "jpg";
                case "image/gif" -> "gif";
                case "image/tiff" -> "tiff";
                default -> "png";
            };
            Files.write(dir.resolve("img-" + image.index() + "." + ext), image.data());
        } catch (Exception e) {
            log.debug("[知识库] 图片落盘失败(不影响描述): {}", e.getMessage());
        }
    }

    /**
     * 仅供单测构造空实现时使用
     */
    static ObjectMapper mapper() {
        return MAPPER;
    }
}
