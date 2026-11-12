package com.forgeagent.agent.service.support;

import com.forgeagent.common.core.exception.ServiceException;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * R4-2 引用校验器(硬门禁,纯静态):只解析 Markdown 文本节点里的 {@code [n]}。
 *
 * <p>不解析的位置:代码围栏 ``` 与行内代码 `...`、图片 {@code ![alt](url)}、
 * 链接 {@code [label](url)}(链接/图片括号内容不算)。校验 {@code 1 <= n <= referenceCount},
 * 越界即失败;referenceCount=0(零召回)时任何 {@code [数字]} 都失败。</p>
 *
 * <p>不改写答案、不裁剪引用 —— 静默修正会掩盖模型问题。非流式抛业务异常提示重新生成;
 * 流式在 done 之前校验,失败由调用方转 error 终态(禁止"带着假引用宣布成功")。</p>
 *
 * @author ForgeAgent
 */
public final class AnswerCitationValidator {

    private static final Pattern CITATION = Pattern.compile("\\[(\\d+)]");
    private static final Pattern LINK_OR_IMAGE = Pattern.compile("!?!?\\[[^\\]]*]\\([^)]*\\)");

    private AnswerCitationValidator() {
    }

    /**
     * 校验回答里的引用编号;越界抛 ServiceException(带最小修复指引)
     *
     * @param answer         模型回答全文
     * @param referenceCount 本轮注入资料的引用数;0=零召回;负数=未绑定知识库(跳过校验)
     */
    public static void validate(String answer, int referenceCount) {
        if (referenceCount < 0 || answer == null || answer.isBlank()) {
            return;
        }
        List<Integer> citations = extract(answer);
        if (citations.isEmpty()) {
            return;
        }
        if (referenceCount == 0) {
            throw new ServiceException("回答引用校验未通过:本轮没有可引用资料,回答中不得出现任何 [数字] 引用标记,请重新生成");
        }
        for (int n : citations) {
            if (n < 1 || n > referenceCount) {
                throw new ServiceException("回答引用校验未通过:引用编号 [" + n
                    + "] 超出本轮资料范围(合法编号 1~" + referenceCount + "),请重新生成");
            }
        }
    }

    /**
     * 提取文本节点里的全部引用编号(代码块/链接/图片内容除外,按出现顺序去重)
     */
    public static List<Integer> extract(String text) {
        List<Integer> result = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return result;
        }
        String cleaned = stripCode(stripLinksAndImages(text));
        Matcher m = CITATION.matcher(cleaned);
        while (m.find()) {
            int n = Integer.parseInt(m.group(1));
            if (!result.contains(n)) {
                result.add(n);
            }
        }
        return result;
    }

    /**
     * 去掉围栏代码块与行内代码(里面的 [1] 是示例代码,不是引用)
     */
    private static String stripCode(String text) {
        StringBuilder sb = new StringBuilder(text.length());
        boolean inFence = false;
        boolean inInline = false;
        int i = 0;
        while (i < text.length()) {
            if (i + 2 < text.length() + 1 && text.startsWith("```", i)) {
                inFence = !inFence;
                i += 3;
                continue;
            }
            char c = text.charAt(i);
            if (!inFence && c == '`') {
                inInline = !inInline;
                i++;
                continue;
            }
            if (!inFence && !inInline) {
                sb.append(c);
            }
            i++;
        }
        return sb.toString();
    }

    /**
     * 去掉链接与图片标记(标签内的 [n] 是链接文字,不是引用)
     */
    private static String stripLinksAndImages(String text) {
        Matcher m = LINK_OR_IMAGE.matcher(text);
        return m.replaceAll(" ");
    }
}
