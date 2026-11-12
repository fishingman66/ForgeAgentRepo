package com.forgeagent.rag.service.document;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

/**
 * 知识库配置指纹(R2-5):解析管线指纹与分块配置指纹。
 *
 * <p><b>解决什么</b>:改了知识库配置(清洗规则/分块参数)后,已摄取的文档与新配置不一致,
 * 但系统不知道哪些文档需要重建——运营只能全量重建。指纹让"哪些文档过期"变成可计算的问题,
 * 差异重建只处理真正受影响的文档。</p>
 *
 * <p><b>解析指纹</b>:sha256(管线版本 | 规范化清洗规则)。清洗规则先 parse 成
 * {@link CleanRules} 再序列化,保证语义等价的 JSON(键顺序/缺省键)得到同一指纹。
 * 管线版本在解析器或清洗实现变化时递增,让存量文档整体标脏。</p>
 *
 * <p><b>分块指纹</b>:sha256(单位|策略|大小|重叠|父子开关|父块大小)。
 * 用原始配置值参与计算,不做默认值填充——"显式 500"与"缺省 500"语义等价但指纹不同,
 * 代价只是多一次无害的重建,换来实现不与摄取侧的默认逻辑耦合。</p>
 *
 * @author ForgeAgent
 */
public final class KnowledgeFingerprints {

    /**
     * 解析+清洗管线版本:解析器实现、清洗规则语义变化时递增,让全部文档标脏重解析
     */
    public static final String PARSE_PIPELINE_VERSION = "1";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private KnowledgeFingerprints() {
    }

    /**
     * 解析管线指纹
     *
     * @param cleanRulesJson 库级清洗规则 JSON(NULL/空白=默认规则)
     */
    public static String parseOf(String cleanRulesJson) {
        return parseOf(cleanRulesJson, null);
    }

    /**
     * 解析指纹(含 R5-5 图片描述开关):开关/模型变化必须触发重建,
     * 否则会出现"一半图有描述一半没有"的脏索引
     *
     * @param vlmSignature 形如 {@code off} / {@code on:gpt-4o};null 等同 off
     */
    public static String parseOf(String cleanRulesJson, String vlmSignature) {
        List<String> errors = new ArrayList<>();
        CleanRules rules = CleanRules.parse(cleanRulesJson, errors);
        String vlm = vlmSignature == null || vlmSignature.isBlank() ? "off" : vlmSignature;
        try {
            return sha256(PARSE_PIPELINE_VERSION + "|clean:" + MAPPER.writeValueAsString(rules)
                + "|vlm:" + vlm);
        } catch (Exception e) {
            // record 序列化不会失败;防御性兜底退回原始串
            return sha256(PARSE_PIPELINE_VERSION + "|raw:" + (cleanRulesJson == null ? "" : cleanRulesJson)
                + "|vlm:" + vlm);
        }
    }

    /**
     * R5-5 图片描述签名:全局开关与库级开关同时为真才算开启;开启时模型名参与指纹
     */
    public static String vlmSignature(boolean globalEnabled, Integer kbEnabled, String model) {
        boolean on = globalEnabled && Integer.valueOf(1).equals(kbEnabled);
        return on ? "on:" + (model == null ? "" : model.trim()) : "off";
    }

    /**
     * 分块配置指纹(原始值直接参与,null 记空串)
     */
    public static String chunkOf(String unit, String strategy, Integer chunkSize, Integer chunkOverlap,
                                 String parentEnabled, Integer parentSize) {
        String raw = String.join("|",
            n(unit), n(strategy), n(chunkSize), n(chunkOverlap), n(parentEnabled), n(parentSize));
        return sha256("chunk:" + raw);
    }

    private static String n(Object v) {
        return v == null ? "" : String.valueOf(v);
    }

    private static String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            // JVM 必有 SHA-256;真发生时退回 hashCode,指纹语义降级但不中断
            return "fallback-" + Integer.toHexString(input.hashCode());
        }
    }
}
