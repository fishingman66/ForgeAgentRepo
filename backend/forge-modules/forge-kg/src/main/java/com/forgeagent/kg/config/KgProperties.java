package com.forgeagent.kg.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 知识图谱模块配置(forge-kg,前缀 {@code kg})。
 *
 * <p>默认关闭:未显式开启时所有调用方(记忆注入、客服领域能力)都拿不到图数据,
 * 不会引入额外表读写。开关归本模块所有,记忆侧只配「注入多少字符」这类自用参数。</p>
 *
 * @author ForgeAgent
 */
@Data
@Component
@ConfigurationProperties(prefix = "kg")
public class KgProperties {

    /** 是否启用知识图谱(写入 + 召回) */
    private boolean enabled = false;

    /**
     * 存图后端:mysql(两表关系存图,开发/测试默认)。
     * neo4j 等其余取值是预留扩展位:配成未实现的值按关闭处理并告警一次。
     */
    private String backend = "mysql";

    /** 参与「文本 → 实体」匹配的节点扫描上限(按显著度降序) */
    private int scanNodeLimit = 500;

    /** 单次写入最多接受的三元组条数(防模型输出把图库写爆) */
    private int maxTriplesPerIngest = 6;

    /** 边权重低于此值不参与召回(1.0 = 至少出现过一次) */
    private double minEdgeWeight = 1.0;

    /**
     * 反义关系对扩展(冲突判定用):每项格式「A:B」,如「解决:导致」「开通:关闭」。
     * 内置词表见 KgConflictPairs;这里是运维/业务按语料补充的口子,不用改代码。
     */
    private List<String> extraConflictPairs = new ArrayList<>();
}
