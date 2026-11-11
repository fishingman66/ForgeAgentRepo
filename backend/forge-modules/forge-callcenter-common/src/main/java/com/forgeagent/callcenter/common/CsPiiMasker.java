package com.forgeagent.callcenter.common;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 客服 PII 脱敏(C4):落库与进入模型前统一打码,避免访客原文里的敏感信息进入业务表。
 *
 * <p>只做常见四类(手机号 / 身份证 / 银行卡 / 邮箱),保留必要前后缀便于人工核对;
 * 不改动其它内容,不猜测自定义证件格式。</p>
 *
 * <p>扩展方向:新增敏感类型 = 加一个 Pattern + 一行 replaceAll(顺序敏感,先长后短);
 * 自定义脱敏规则后续可外置为配置(规则热更新);语音号码与转写文本已统一走本类。</p>
 */
public final class CsPiiMasker {

    private static final Pattern PHONE = Pattern.compile("(?<!\\d)(1[3-9]\\d)(\\d{4})(\\d{4})(?!\\d)");
    private static final Pattern ID_CARD = Pattern.compile("(?<!\\d)(\\d{6})\\d{8}(\\d{3}[\\dXx])(?!\\d)");
    private static final Pattern BANK_CARD = Pattern.compile("(?<!\\d)\\d{12,15}(\\d{4})(?!\\d)");
    private static final Pattern EMAIL = Pattern.compile("([A-Za-z0-9._%+-])[A-Za-z0-9._%+-]*@([A-Za-z0-9.-]+\\.[A-Za-z]{2,})");

    private CsPiiMasker() {
    }

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
