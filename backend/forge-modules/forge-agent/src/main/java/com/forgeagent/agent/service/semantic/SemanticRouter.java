package com.forgeagent.agent.service.semantic;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import com.forgeagent.agent.domain.AgentSemanticDimension;
import com.forgeagent.agent.domain.AgentSemanticMetric;
import com.forgeagent.agent.domain.AgentSemanticValue;
import com.forgeagent.agent.mapper.AgentSemanticDimensionMapper;
import com.forgeagent.agent.mapper.AgentSemanticMetricMapper;
import com.forgeagent.agent.mapper.AgentSemanticValueMapper;
import com.forgeagent.common.core.utils.StringUtils;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 语义路由器:构建并缓存数据源的语义词表(指标/维度/枚举值),
 * 将用户问题匹配到 Top-K 语义项,并判定歧义。
 *
 * <p>匹配策略:别名精确子串命中得 1.0,否则取字符二元组 Jaccard 相似度。
 * 词表按数据源维度缓存,写操作通过 evictVocabulary 主动清理。</p>
 *
 * @author ForgeAgent
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SemanticRouter {

    /** 语义词表缓存名 — 带 #ttl 后缀设置过期时间兜底 */
    public static final String CACHE_VOCAB = "agent.semantic.vocab#600s";

    private final AgentSemanticMetricMapper metricMapper;
    private final AgentSemanticDimensionMapper dimensionMapper;
    private final AgentSemanticValueMapper valueMapper;

    /**
     * 语义词表(按数据源)
     */
    public record SemanticVocabulary(
        List<MetricEntry> metrics,
        List<DimensionEntry> dimensions,
        List<ValueEntry> values
    ) {
        public static SemanticVocabulary empty() {
            return new SemanticVocabulary(List.of(), List.of(), List.of());
        }

        public boolean isEmpty() {
            return metrics.isEmpty() && dimensions.isEmpty() && values.isEmpty();
        }
    }

    public record MetricEntry(Long id, String name, List<String> aliases, String expression,
                              String baseTable, String defaultFilters, String description) {}

    public record DimensionEntry(Long id, String displayName, List<String> aliases,
                                 String table, String column, String dimType) {}

    public record ValueEntry(Long id, String table, String column, String rawValue,
                             String displayName, List<String> aliases) {}

    /**
     * 路由结果
     *
     * @param items          命中的语义项(按得分降序)
     * @param ambiguous      是否歧义(Top1 与 Top2 分差过小)
     * @param clarifyOptions 歧义时的候选选项文本
     * @param empty          是否无命中
     */
    public record RouteResult(List<MatchedItem> items, boolean ambiguous,
                              List<String> clarifyOptions, boolean empty) {
        public record MatchedItem(String type, String name, String detail, double score) {}
    }

    /**
     * 获取数据源语义词表(缓存)
     */
    @Cacheable(cacheNames = CACHE_VOCAB, key = "#datasourceId")
    public SemanticVocabulary getVocabulary(Long datasourceId) {
        List<MetricEntry> metrics = metricMapper.selectList(
            new LambdaQueryWrapper<AgentSemanticMetric>()
                .eq(AgentSemanticMetric::getDatasourceId, datasourceId)
                .eq(AgentSemanticMetric::getStatus, "0")
                .orderByAsc(AgentSemanticMetric::getSort)
                .orderByAsc(AgentSemanticMetric::getId)
        ).stream().map(m -> new MetricEntry(
            m.getId(), m.getName(), splitAliases(m.getSynonyms(), m.getName()),
            m.getExpression(), m.getBaseTable(),
            m.getDefaultFilters() == null ? "" : m.getDefaultFilters(),
            m.getDescription() == null ? "" : m.getDescription()
        )).toList();

        List<DimensionEntry> dimensions = dimensionMapper.selectList(
            new LambdaQueryWrapper<AgentSemanticDimension>()
                .eq(AgentSemanticDimension::getDatasourceId, datasourceId)
                .eq(AgentSemanticDimension::getStatus, "0")
                .orderByAsc(AgentSemanticDimension::getSort)
                .orderByAsc(AgentSemanticDimension::getId)
        ).stream().map(d -> new DimensionEntry(
            d.getId(), d.getDisplayName(), splitAliases(d.getSynonyms(), d.getDisplayName()),
            d.getTableName(), d.getColumnName(),
            d.getDimType() == null ? "text" : d.getDimType()
        )).toList();

        List<ValueEntry> values = valueMapper.selectList(
            new LambdaQueryWrapper<AgentSemanticValue>()
                .eq(AgentSemanticValue::getDatasourceId, datasourceId)
                .eq(AgentSemanticValue::getStatus, "0")
                .orderByAsc(AgentSemanticValue::getSort)
                .orderByAsc(AgentSemanticValue::getId)
        ).stream().map(v -> new ValueEntry(
            v.getId(), v.getTableName(), v.getColumnName(), v.getRawValue(),
            v.getDisplayName(), splitAliases(v.getSynonyms(), v.getDisplayName(), v.getRawValue())
        )).toList();

        return new SemanticVocabulary(metrics, dimensions, values);
    }

    /**
     * 清理指定数据源的词表缓存(语义资产写操作后调用)
     */
    @CacheEvict(cacheNames = CACHE_VOCAB, key = "#datasourceId")
    public void evictVocabulary(Long datasourceId) {
        // 注解生效即可
    }

    /**
     * 对用户问题做语义路由,返回命中项与歧义判定
     *
     * @param datasourceId     数据源ID
     * @param question         用户问题
     * @param topK             命中数量上限
     * @param minScore         最低相似度阈值
     * @param ambiguityMargin  歧义分差阈值
     */
    public RouteResult route(Long datasourceId, String question, int topK,
                             double minScore, double ambiguityMargin) {
        if (datasourceId == null || StringUtils.isBlank(question)) {
            return new RouteResult(List.of(), false, List.of(), true);
        }
        SemanticVocabulary vocab = getVocabulary(datasourceId);
        if (vocab.isEmpty()) {
            return new RouteResult(List.of(), false, List.of(), true);
        }

        String q = question.toLowerCase();
        Set<String> qBigrams = bigrams(q);

        List<ScoredItem> scored = new ArrayList<>();

        // 指标
        for (MetricEntry m : vocab.metrics()) {
            AliasMatch match = bestAliasMatch(q, qBigrams, m.aliases());
            if (match.score() < minScore) {
                continue;
            }
            scored.add(new ScoredItem("指标", m.name(), buildMetricDetail(m),
                match.score(), match.alias()));
        }

        // 维度
        for (DimensionEntry d : vocab.dimensions()) {
            AliasMatch match = bestAliasMatch(q, qBigrams, d.aliases());
            if (match.score() < minScore) {
                continue;
            }
            String detail = String.format("%s.%s (%s)", d.table(), d.column(), d.dimType());
            scored.add(new ScoredItem("维度", d.displayName(), detail, match.score(), match.alias()));
        }

        // 枚举值(按列分组,只取命中值所属列)
        Map<String, List<ValueEntry>> byColumn = new LinkedHashMap<>();
        Map<String, AliasMatch> columnBest = new LinkedHashMap<>();
        for (ValueEntry v : vocab.values()) {
            AliasMatch match = bestAliasMatch(q, qBigrams, v.aliases());
            if (match.score() < minScore) {
                continue;
            }
            String key = v.table() + "." + v.column();
            byColumn.computeIfAbsent(key, k -> new ArrayList<>()).add(v);
            columnBest.merge(key, match, (a, b) -> b.score() > a.score() ? b : a);
        }
        for (Map.Entry<String, List<ValueEntry>> e : byColumn.entrySet()) {
            StringBuilder sb = new StringBuilder();
            for (ValueEntry v : e.getValue()) {
                if (sb.length() > 0) {
                    sb.append(", ");
                }
                sb.append(v.displayName()).append("=").append(v.rawValue());
            }
            AliasMatch colBest = columnBest.get(e.getKey());
            scored.add(new ScoredItem("值映射", e.getKey(), sb.toString(),
                colBest.score(), colBest.alias()));
        }

        if (scored.isEmpty()) {
            return new RouteResult(List.of(), false, List.of(), true);
        }

        scored.sort((a, b) -> Double.compare(b.score, a.score));
        List<RouteResult.MatchedItem> items = scored.stream()
            .limit(topK)
            .map(s -> new RouteResult.MatchedItem(s.type, s.name, s.detail, round(s.score)))
            .toList();

        // 歧义判定:只在同一类型、同一触发别名(同义指标/同列同值)之间比较分差;
        // 跨类型命中(如"华东"值映射 + "GMV"指标)是互补关系,一律不视为歧义
        Map<String, List<ScoredItem>> slots = new LinkedHashMap<>();
        for (ScoredItem s : scored) {
            slots.computeIfAbsent(s.type() + "|" + s.matchAlias(), k -> new ArrayList<>()).add(s);
        }
        boolean ambiguous = false;
        List<String> clarifyOptions = List.of();
        double bestSlotScore = -1;
        for (List<ScoredItem> slot : slots.values()) {
            if (slot.size() < 2) {
                continue;
            }
            slot.sort((a, b) -> Double.compare(b.score(), a.score()));
            ScoredItem first = slot.get(0);
            ScoredItem second = slot.get(1);
            if (first.score() - second.score() >= ambiguityMargin || first.score() <= bestSlotScore) {
                continue;
            }
            bestSlotScore = first.score();
            ambiguous = true;
            clarifyOptions = List.of(
                first.type() + ":" + first.name(),
                second.type() + ":" + second.name()
            );
        }

        return new RouteResult(items, ambiguous, clarifyOptions, false);
    }

    // ---------------- 内部工具 ----------------

    private record ScoredItem(String type, String name, String detail, double score,
                              String matchAlias) {}

    private String buildMetricDetail(MetricEntry m) {
        StringBuilder sb = new StringBuilder();
        // 基表必须一起注入:只给表达式,模型不知道 FROM 哪张表,只能凭猜测
        if (StringUtils.isNotBlank(m.baseTable())) {
            sb.append("基表 ").append(m.baseTable()).append("; ");
        }
        sb.append("表达式 ").append(m.expression());
        if (StringUtils.isNotBlank(m.defaultFilters())) {
            sb.append("; 默认过滤 ").append(m.defaultFilters());
        }
        if (StringUtils.isNotBlank(m.description())) {
            sb.append("; 口径说明 ").append(m.description());
        }
        return sb.toString();
    }

    /**
     * 别名匹配结果:得分 + 命中的别名(同一别名才算同一槽位,用于歧义判定)
     */
    private record AliasMatch(double score, String alias) {
        static AliasMatch none() {
            return new AliasMatch(0.0, null);
        }
    }

    /** 字符二元组集合上限,防止超长问题 bigram 爆炸 */
    private static final int BIGRAM_CAP = 500;

    /**
     * 计算单个语义项在所有别名中的最高相似度,并记录命中的别名
     */
    private AliasMatch bestAliasMatch(String question, Set<String> qBigrams, List<String> aliases) {
        AliasMatch best = AliasMatch.none();
        for (String alias : aliases) {
            if (StringUtils.isBlank(alias)) {
                continue;
            }
            String a = alias.toLowerCase();
            // 精确子串命中 — 中文别名仅校验右侧边界,防止"支付"伪命中"支付宝";
            // 英文/数字别名紧邻中文是正常写法(如"本月GMV是多少"),不受中文边界约束
            if (containsAliasHit(question, a)) {
                return new AliasMatch(1.0, a);
            }
            double sim = jaccard(qBigrams, bigrams(a));
            if (sim > best.score()) {
                best = new AliasMatch(sim, a);
            }
        }
        return best;
    }

    /**
     * 中文别名右侧可视为词边界的字符:常用虚词、代词、量词、时间与行政区划后缀、指标量纲后缀。
     * 不在该集合内的汉字视为词内延伸(如"支付"后接"宝"构成"支付宝"),不算别名的独立出现。
     */
    private static final String CJK_RIGHT_BOUNDARY_CHARS =
        "的是有在和与及或按比各每这那了吗呢么吧都也就还又再从到对给被把"
            + "多少时天月年日周季内上下中前后里"
            + "元个人家些种次条张笔单"
            + "地区市省县镇路街部组科院店铺站线"
            + "额量率数值同环累计总排名占";

    /**
     * 问题中是否存在该别名的合法命中:同一别名可能出现多次,
     * 首次出现位置不合法时继续尝试后续位置。
     *
     * <p>左侧不做汉字邻接限制——中文没有词间隔,"上个月华东""查销售额"都是正常写法,
     * 限制左侧会把绝大多数中文别名挡掉;右侧仅允许虚词/量词等边界字符,兼顾召回与
     * "支付"不伪命中"支付宝"的保护。英文/数字别名两侧均不受中文邻接约束。</p>
     */
    private boolean containsAliasHit(String question, String alias) {
        boolean cjkEnd = isCjk(alias.charAt(alias.length() - 1));
        int from = 0;
        while (from <= question.length() - alias.length()) {
            int idx = question.indexOf(alias, from);
            if (idx < 0) {
                return false;
            }
            if (isRightBoundaryOk(question, idx, alias.length(), cjkEnd)) {
                return true;
            }
            from = idx + 1;
        }
        return false;
    }

    /**
     * 右侧边界判定:别名以中文结尾时,紧邻字符必须是非汉字或常用边界字符
     */
    private boolean isRightBoundaryOk(String s, int idx, int len, boolean cjkEnd) {
        if (!cjkEnd || idx + len >= s.length()) {
            return true;
        }
        char next = s.charAt(idx + len);
        return !isCjk(next) || CJK_RIGHT_BOUNDARY_CHARS.indexOf(next) >= 0;
    }

    /** 是否中文字符(CJK 基本区 + 扩展区) */
    private boolean isCjk(char c) {
        return (c >= 0x4E00 && c <= 0x9FFF) || (c >= 0x3400 && c <= 0x4DBF)
            || (c >= 0xF900 && c <= 0xFAFF);
    }

    /**
     * 字符二元组集合
     */
    private Set<String> bigrams(String text) {
        if (text == null || text.length() < 2) {
            return Collections.emptySet();
        }
        Set<String> set = new HashSet<>();
        for (int i = 0; i < text.length() - 1 && set.size() < BIGRAM_CAP; i++) {
            set.add(text.substring(i, i + 2));
        }
        return set;
    }

    private double jaccard(Set<String> a, Set<String> b) {
        if (a.isEmpty() || b.isEmpty()) {
            return 0.0;
        }
        Set<String> intersection = new HashSet<>(a);
        intersection.retainAll(b);
        Set<String> union = new HashSet<>(a);
        union.addAll(b);
        return (double) intersection.size() / union.size();
    }

    /**
     * 切分别名:逗号分隔,trim,去空,并把主名作为首个别名
     */
    private List<String> splitAliases(String synonyms, String... primaries) {
        List<String> list = new ArrayList<>();
        for (String p : primaries) {
            if (StringUtils.isNotBlank(p)) {
                list.add(p.trim());
            }
        }
        if (StringUtils.isNotBlank(synonyms)) {
            for (String s : synonyms.split("[,，]")) {
                String t = s.trim();
                if (!t.isEmpty()) {
                    list.add(t);
                }
            }
        }
        return list;
    }

    private double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
