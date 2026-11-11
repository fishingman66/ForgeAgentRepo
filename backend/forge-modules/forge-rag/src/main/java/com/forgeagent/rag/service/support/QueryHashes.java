package com.forgeagent.rag.service.support;

import org.springframework.util.DigestUtils;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * 查询指纹(R3-5 与 R1-6 检索日志同口径):trim + 小写 + 折叠连续空白后 md5。
 * 唯一真源 —— 检索日志落库与质量问题去重都从这里取,改口径只改一处。
 *
 * @author ForgeAgent
 */
public final class QueryHashes {

    private QueryHashes() {
    }

    public static String of(String query) {
        String normalized = query == null ? "" : query.trim().toLowerCase(Locale.ROOT)
            .replaceAll("\\s+", " ");
        return DigestUtils.md5DigestAsHex(normalized.getBytes(StandardCharsets.UTF_8));
    }
}
