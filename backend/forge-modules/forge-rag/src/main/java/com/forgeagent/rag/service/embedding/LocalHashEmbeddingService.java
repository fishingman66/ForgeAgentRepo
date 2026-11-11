package com.forgeagent.rag.service.embedding;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
/**
 * 内置演示向量模型:基于特征哈希(signed hashing)的词向量嵌入。
 * 无需外部服务、结果确定,仅用于本地联调与会话附件临时索引,不具备语义准确性。
 * P0 起由 {@link EmbeddingFactory} 在生产 profile 下禁止正式知识库使用;
 * 生产请为知识库配置 OpenAI 兼容嵌入模型。
 *
 * @author ForgeAgent
 */
public class LocalHashEmbeddingService implements EmbeddingService {

    public static final String MODEL_KEY = "local-hash";

    private static final Pattern LATIN = Pattern.compile("[a-z0-9_]+");
    private static final Pattern CJK = Pattern.compile("[\\u4e00-\\u9fff]");

    private final int dimension;

    public LocalHashEmbeddingService(int dimension) {
        this.dimension = dimension;
    }

    @Override
    public String modelKey() {
        return MODEL_KEY;
    }

    @Override
    public int dimension() {
        return dimension;
    }

    @Override
    public List<float[]> embed(List<String> texts) {
        List<float[]> result = new ArrayList<>(texts.size());
        for (String text : texts) {
            result.add(embedOne(text == null ? "" : text));
        }
        return result;
    }

    private float[] embedOne(String text) {
        float[] vec = new float[dimension];
        for (String term : tokenize(text)) {
            int h = term.hashCode();
            int idx = Math.floorMod(h, dimension);
            // 第二个哈希决定符号,降低哈希碰撞带来的系统性偏差
            float sign = (Math.floorMod(h * 31 + term.length(), 2) == 0) ? 1f : -1f;
            vec[idx] += sign;
        }
        // L2 归一化
        double norm = 0d;
        for (float v : vec) {
            norm += v * (double) v;
        }
        if (norm > 0) {
            float inv = (float) (1.0 / Math.sqrt(norm));
            for (int i = 0; i < vec.length; i++) {
                vec[i] *= inv;
            }
        }
        return vec;
    }

    /**
     * 分词:拉丁词 + CJK 单字与二元组(提升中文语义重叠度)
     */
    private List<String> tokenize(String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        List<String> terms = new ArrayList<>();
        Matcher m = LATIN.matcher(lower);
        while (m.find()) {
            terms.add(m.group());
        }
        List<String> cjkChars = new ArrayList<>();
        Matcher c = CJK.matcher(lower);
        while (c.find()) {
            cjkChars.add(c.group());
        }
        terms.addAll(cjkChars);
        for (int i = 0; i + 1 < cjkChars.size(); i++) {
            terms.add(cjkChars.get(i) + cjkChars.get(i + 1));
        }
        return terms;
    }
}
