package com.forgeagent.kg.service;

import com.forgeagent.common.core.utils.StringUtils;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 图谱文本归一化(forge-kg 公共工具)。
 *
 * <p>归一化口径必须由所有 {@link KgStore} 实现共用,否则同一实体在
 * MySQL 与 Neo4j 下会拿到不同的唯一键,换后端时图谱等于重建。</p>
 *
 * @author ForgeAgent
 */
public final class KgText {

    /** 空白 + 中英文标点(ASCII 标点用 \p{Punct},中文标点显式列出) */
    private static final Pattern NOISE = Pattern.compile(
        "[\\s\\p{Punct}，。；：、！？“”‘’（）《》【】〈〉「」『』—…·～]+");

    /** 实体名最大长度(与表字段对齐,留余量) */
    public static final int MAX_NAME = 64;

    /** 关系最大长度 */
    public static final int MAX_RELATION = 32;

    private KgText() {
    }

    /** 展示名:去首尾空白与控制字符、压空白,截到上限 */
    public static String clean(String raw) {
        if (StringUtils.isBlank(raw)) {
            return "";
        }
        String text = raw.replaceAll("[\\p{Cntrl}\\u200b\\ufeff]", "").trim().replaceAll("\\s+", " ");
        return text.length() > MAX_NAME ? text.substring(0, MAX_NAME) : text;
    }

    /** 唯一键:去空白与标点后转小写;空串表示该实体不可用 */
    public static String nameKey(String raw) {
        String key = flatten(raw);
        return key.length() > MAX_NAME ? key.substring(0, MAX_NAME) : key;
    }

    /**
     * 压平文本:去空白与标点、转小写,不做长度截断。
     * 记忆/客服文本与实体名用同一套压平口径后再做包含匹配,才能对齐
     * 「我在做 ForgeAgent 项目」与实体名「ForgeAgent 项目」这类空格标点差异。
     */
    public static String flatten(String raw) {
        String text = clean(raw);
        if (text.isEmpty()) {
            return "";
        }
        return NOISE.matcher(text).replaceAll("").toLowerCase(Locale.ROOT);
    }

    /** 关系归一化:去标点、压空白,不转小写 */
    public static String relation(String raw) {
        String text = clean(raw);
        if (text.isEmpty()) {
            return "";
        }
        String relation = NOISE.matcher(text).replaceAll("").trim();
        return relation.length() > MAX_RELATION ? relation.substring(0, MAX_RELATION) : relation;
    }
}
