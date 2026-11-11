package com.forgeagent.memory.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 长期记忆「图谱注入」配置(P3 / M3,前缀 {@code memory.graph})。
 *
 * <p>图谱能力本体已抽到 {@code forge-kg},总开关与后端在 {@code kg.*};
 * 这里只留记忆域自用参数:注入多少字符、召回几条边、存量补建的批量与上限。
 * 参数为零时记忆块行为与 M2′ 完全一致。</p>
 *
 * @author ForgeAgent
 */
@Data
@Component
@ConfigurationProperties(prefix = "memory.graph")
public class MemoryGraphProperties {

    /** 「关联背景」占用的字符预算,从记忆块总预算里扣,不额外挤占上下文 */
    private int injectChars = 220;

    /** 单次注入的邻接关系条数上限 */
    private int neighborLimit = 5;

    /** 存量补建:每次送模型的记忆条数 */
    private int rebuildBatch = 10;

    /** 存量补建:单次最多处理的记忆条数(防止一次点爆模型配额) */
    private int rebuildLimit = 200;
}
