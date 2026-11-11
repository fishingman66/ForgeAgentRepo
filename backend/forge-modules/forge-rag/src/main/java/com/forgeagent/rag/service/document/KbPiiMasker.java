package com.forgeagent.rag.service.document;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 知识库正文 PII 脱敏(R2-2):手机号 / 身份证 / 银行卡 / 邮箱。
 *
 * <p>正则与 {@code forge-callcenter-common} 的 {@code CsPiiMasker} 逐字一致
 * (两边独立演进会漂,改动任一侧必须在另一侧同步并补对应用例);
 * 之所以不直接依赖,是避免 rag 横向引入呼叫中心模块——两模块本无业务交集。</p>
 *
 * <p>保留必要前后缀便于人工核对;顺序敏感,先长后短(身份证 18 位先于银行卡 12-19 位)。</p>
 *
 * @author ForgeAgent
 */
public final class KbPiiMasker {

    private static final Pattern PHONE = Pattern.compile("(?<!\\d)(1[3-9]\\d)(\\d{4})(\\d{4})(?!\\d)");
    private static final Pattern ID_CARD = Pattern.compile("(?<!\\d)(\\d{6})\\d{8}(\\d{3}[\\dXx])(?!\\d)");
    private static final Pattern BANK_CARD = Pattern.compile("(?<!\\d)\\d{12,15}(\\d{4})(?!\\d)");
    private static final Pattern EMAIL = Pattern.compile("([A-Za-z0-9._%+-])[A-Za-z0-9._%+-]*@([A-Za-z0-9.-]+\\.[A-Za-z]{2,})");

    private KbPiiMasker() {
    }

    /**
     * 打码;返回原文表示没有命中
     */
    public static String mask(String text) {
        if (text == null || text.isBlank()) {
            return text;
        }
        String masked = text;
        Matcher id = ID_CARD.matcher(masked);
        masked = id.replaceAll(m -> m.group(1) + "********" + m.group(2));
        Matcher bank = BANK_CARD.matcher(masked);
        masked = bank.replaceAll(m -> "************" + m.group(1));
        Matcher phone = PHONE.matcher(masked);
        masked = phone.replaceAll(m -> m.group(1) + "****" + m.group(3));
        Matcher mail = EMAIL.matcher(masked);
        masked = mail.replaceAll(m -> m.group(1) + "***@" + m.group(2));
        return masked;
    }
}
