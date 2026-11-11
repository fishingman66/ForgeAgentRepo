package com.forgeagent.agent.service.semantic;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import com.forgeagent.agent.config.AgentRuntimeProperties;
import com.forgeagent.agent.domain.AgentDatasource;
import com.forgeagent.agent.domain.AgentDatasourceExample;
import com.forgeagent.agent.mapper.AgentDatasourceExampleMapper;
import com.forgeagent.agent.mapper.AgentDatasourceMapper;
import com.forgeagent.agent.service.runner.SqlQueryService;
import com.forgeagent.agent.service.runner.ToolAssembler;
import com.forgeagent.common.core.utils.StringUtils;
import com.forgeagent.common.json.utils.JsonUtils;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 问数语义上下文构建器:替代原静态 buildSqlSchemaBlock。
 *
 * <p>对启用语义层的数据源,先由 {@link SemanticRouter} 做语义路由,只把命中的指标/维度/值映射
 * 注入提示词,并附带与问题最相似的示例;未启用或无命中时回退到原表清单注入,保持兼容。</p>
 *
 * @author ForgeAgent
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SemanticContextBuilder {
    private final com.forgeagent.agentcore.service.ResourceAccessGuard resourceGuard;


    private final AgentDatasourceMapper datasourceMapper;
    private final AgentDatasourceExampleMapper exampleMapper;
    private final SemanticRouter semanticRouter;
    private final AgentRuntimeProperties runtimeProperties;

    /**
     * 构建问数提示词块
     *
     * @param toolDefs Agent 绑定的工具定义快照
     * @param question 用户问题(用于语义路由)
     */
    public String build(List<ToolAssembler.ToolDef> toolDefs, String question, Long callerWorkspaceId) {
        List<ToolAssembler.ToolDef> sqlTools = toolDefs == null ? List.of() : toolDefs.stream()
            .filter(d -> "sql".equals(d.toolType()) && d.datasourceId() != null)
            .toList();
        if (sqlTools.isEmpty()) {
            return "";
        }
        List<Long> dsIds = sqlTools.stream().map(ToolAssembler.ToolDef::datasourceId).distinct().toList();
        StringBuilder block = new StringBuilder();
        for (AgentDatasource ds : datasourceMapper.selectByIds(dsIds)) {
            if (!resourceGuard.readableBy(ds.getWorkspaceId(), callerWorkspaceId)) {
                continue;
            }
            if ("1".equals(ds.getStatus()) || StringUtils.isBlank(ds.getSchemaTables())) {
                continue;
            }
            List<String> toolNames = sqlTools.stream()
                .filter(d -> ds.getId().equals(d.datasourceId()))
                .map(ToolAssembler.ToolDef::name)
                .toList();
            if ("1".equals(ds.getSemanticEnabled())) {
                block.append(buildSemanticBlock(ds, question, toolNames));
            } else {
                block.append(buildLegacyBlock(ds, toolNames));
            }
        }
        if (block.isEmpty()) {
            return "";
        }
        // 注入当前日期:模型对"今天/本月/最近N天"等相对时间没有换算基准,必须显式给出锚点,
        // 避免日期条件凭训练记忆瞎猜(改写助手被要求保留相对时间表述,具体换算由主模型基于该基准完成)
        java.time.LocalDate today = java.time.LocalDate.now();
        String[] weekCn = {"周一", "周二", "周三", "周四", "周五", "周六", "周日"};
        String week = weekCn[today.getDayOfWeek().getValue() - 1];
        return "你可以通过问数工具对下列数据源执行只读 SQL 查询来回答用户的数据问题。\n"
            + "当前日期:" + today + "(" + week + ")。涉及\"今天/昨天/本月/上个月/最近N天\"等相对时间时,"
            + "必须以该日期为基准换算成具体日期范围,不要凭记忆猜测日期。\n"
            + "只生成 SELECT 语句并用 LIMIT 控制返回行数,不要猜测不存在的表或列:\n" + block;
    }

    // ---------------- 语义层注入 ----------------

    private String buildSemanticBlock(AgentDatasource ds, String question, List<String> toolNames) {
        SemanticRouter.RouteResult route = semanticRouter.route(
            ds.getId(), question,
            runtimeProperties.getSemanticTopK(),
            runtimeProperties.getSemanticMinScore(),
            runtimeProperties.getSemanticAmbiguityMargin());

        StringBuilder sb = new StringBuilder();
        sb.append("### 数据源[").append(ds.getName()).append("](")
            .append(ds.getDescription() == null ? "" : ds.getDescription()).append(")\n");

        if (route.empty()) {
            // 无命中:注入精简表清单 + 引导
            sb.append("可查询表:\n").append(tableListText(ds, 2000)).append('\n');
            sb.append("生成 SQL 前必须先调用 ")
                .append(toolNames.stream().map(n -> n + "_schema").collect(Collectors.joining(" / ")))
                .append(" 工具查看所用表字段明细,只使用返回的表和字段;\n");
        } else {
            sb.append("生成 SQL 前必须先调用 ")
                .append(toolNames.stream().map(n -> n + "_schema").collect(Collectors.joining(" / ")))
                .append(" 工具确认所用表的字段,只使用返回的表和字段。\n");

            // 命中语义分组输出
            sb.append("命中语义(请严格使用以下口径与取值,不要自行臆测):\n");
            for (SemanticRouter.RouteResult.MatchedItem item : route.items()) {
                sb.append("- [").append(item.type()).append("] ").append(item.name())
                    .append(": ").append(item.detail()).append('\n');
            }

            // 歧义提示
            if (route.ambiguous()) {
                sb.append("【澄清要求】以下语义存在歧义,你必须先用一句话向用户确认选择哪一个,不要直接生成 SQL:\n");
                for (String opt : route.clarifyOptions()) {
                    sb.append("  - ").append(opt).append('\n');
                }
            }
        }

        // 相似示例
        String examples = similarExamplesText(ds, question);
        if (StringUtils.isNotBlank(examples)) {
            sb.append(examples);
        }

        // 字符预算裁剪
        return enforceBudget(sb.toString(), runtimeProperties.getSemanticContextChars());
    }

    // ---------------- 兼容回退(原逻辑) ----------------

    private String buildLegacyBlock(AgentDatasource ds, List<String> toolNames) {
        StringBuilder sb = new StringBuilder();
        sb.append("### 数据源[").append(ds.getName()).append("](")
            .append(ds.getDescription() == null ? "" : ds.getDescription()).append(")\n");
        sb.append("可查询表:\n").append(tableListText(ds, 4000)).append('\n');
        sb.append("生成 SQL 前必须先调用 ")
            .append(toolNames.stream().map(n -> n + "_schema").collect(Collectors.joining(" / ")))
            .append(" 工具查看所用表字段明细,只使用返回的表和字段;\n");
        sb.append(legacyExamplesText(ds));
        return sb.toString();
    }

    /**
     * 表清单文本(名称+注释,最多 maxChars 字符)
     */
    private String tableListText(AgentDatasource ds, int maxChars) {
        List<SqlQueryService.TableSchema> tables = JsonUtils.parseArray(ds.getSchemaTables(), SqlQueryService.TableSchema.class);
        if (tables == null || tables.isEmpty()) {
            return "(暂无,请先同步表结构)\n";
        }
        StringBuilder sb = new StringBuilder();
        for (SqlQueryService.TableSchema t : tables) {
            sb.append("- ").append(t.name());
            if (StringUtils.isNotBlank(t.comment())) {
                sb.append(" -- ").append(t.comment());
            }
            sb.append('\n');
            if (sb.length() > maxChars) {
                sb.append("...(表过多已截断,可通过 _schema 工具按名查看字段)\n");
                break;
            }
        }
        return sb.toString();
    }

    /**
     * 与问题最相似的示例(bigram Jaccard Top-N),替代静态前 10
     */
    private String similarExamplesText(AgentDatasource ds, String question) {
        List<AgentDatasourceExample> examples = exampleMapper.selectList(
            new LambdaQueryWrapper<AgentDatasourceExample>()
                .eq(AgentDatasourceExample::getDatasourceId, ds.getId())
                .eq(AgentDatasourceExample::getStatus, "0")
                .orderByAsc(AgentDatasourceExample::getSort)
                .orderByAsc(AgentDatasourceExample::getId));
        if (examples.isEmpty()) {
            return "";
        }
        Set<String> qBigrams = bigrams(StringUtils.isBlank(question) ? "" : question.toLowerCase());
        int topN = Math.max(1, runtimeProperties.getSemanticExampleTopN());
        List<AgentDatasourceExample> picked = examples.stream()
            .sorted(Comparator.comparingDouble(e -> -jaccard(qBigrams, bigrams(e.getQuestion().toLowerCase()))))
            .limit(topN)
            .toList();
        StringBuilder sb = new StringBuilder("参考示例:\n");
        for (AgentDatasourceExample e : picked) {
            sb.append("问题: ").append(truncate(e.getQuestion(), 200)).append('\n')
                .append("SQL: ").append(truncate(e.getSqlContent(), 1000)).append("\n\n");
        }
        return sb.toString();
    }

    /**
     * 兼容模式:取前 10 条示例(原逻辑)
     */
    private String legacyExamplesText(AgentDatasource ds) {
        List<AgentDatasourceExample> examples = exampleMapper.selectList(
            new LambdaQueryWrapper<AgentDatasourceExample>()
                .eq(AgentDatasourceExample::getDatasourceId, ds.getId())
                .eq(AgentDatasourceExample::getStatus, "0")
                .orderByAsc(AgentDatasourceExample::getSort)
                .orderByAsc(AgentDatasourceExample::getId)
                .last("limit 10"));
        if (examples.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("参考示例:\n");
        for (AgentDatasourceExample e : examples) {
            sb.append("问题: ").append(truncate(e.getQuestion(), 200)).append('\n')
                .append("SQL: ").append(truncate(e.getSqlContent(), 1000)).append("\n\n");
        }
        return sb.toString();
    }

    // ---------------- 工具 ----------------

    private String enforceBudget(String text, int maxChars) {
        if (text.length() <= maxChars) {
            return text;
        }
        return text.substring(0, maxChars) + "\n...(语义上下文过长已截断)";
    }

    private String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() > max ? s.substring(0, max) : s;
    }

    private Set<String> bigrams(String text) {
        if (text == null || text.length() < 2) {
            return new HashSet<>();
        }
        Set<String> set = new HashSet<>();
        for (int i = 0; i < text.length() - 1; i++) {
            set.add(text.substring(i, i + 2));
        }
        return set;
    }

    private double jaccard(Set<String> a, Set<String> b) {
        if (a.isEmpty() || b.isEmpty()) {
            return 0.0;
        }
        Set<String> inter = new HashSet<>(a);
        inter.retainAll(b);
        Set<String> union = new HashSet<>(a);
        union.addAll(b);
        return (double) inter.size() / union.size();
    }
}
