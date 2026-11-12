package com.forgeagent.rag.service.graph;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * R5-3 查询分词(纯静态):把 query 拆成"候选实体词"。
 *
 * <p>口径:连续字母/数字 run 整体保留(ProductX1、v2.3);CJK 连续段整体保留并补 2~4 字滑窗,
 * 因为中文实体边界不可靠,只用整段会漏"部署手册"落在长句里的情况。去重保序、过滤单字。</p>
 *
 * @author ForgeAgent
 */
public final class GraphQueryTokenizer {

    private GraphQueryTokenizer() {
    }

    public static List<String> tokenize(String query) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        Set<String> tokens = new LinkedHashSet<>();
        StringBuilder alnum = new StringBuilder();
        StringBuilder cjk = new StringBuilder();
        for (int i = 0; i < query.length(); i++) {
            char c = query.charAt(i);
            if (isCjk(c)) {
                flush(alnum, tokens);
                cjk.append(c);
            } else if (Character.isLetterOrDigit(c)) {
                flush(cjk, tokens);
                alnum.append(c);
            } else {
                flush(alnum, tokens);
                flush(cjk, tokens);
            }
        }
        flush(alnum, tokens);
        flush(cjk, tokens);
        List<String> result = new ArrayList<>();
        for (String t : tokens) {
            if (t.length() >= 2) {
                result.add(t);
            }
        }
        return result;
    }

    private static void flush(StringBuilder buf, Set<String> tokens) {
        if (buf.length() == 0) {
            return;
        }
        String text = buf.toString();
        buf.setLength(0);
        tokens.add(text);
        // CJK 段补 2~4 字滑窗(英文 run 本身就是完整词,不再切)
        if (text.length() >= 2 && isCjk(text.charAt(0))) {
            for (int win = 2; win <= Math.min(4, text.length()); win++) {
                for (int i = 0; i + win <= text.length(); i++) {
                    tokens.add(text.substring(i, i + win));
                }
            }
        }
    }

    private static boolean isCjk(char c) {
        return c >= 0x4E00 && c <= 0x9FFF;
    }
}
