package com.forgeagent.agent.service.runner;

import com.forgeagent.agent.domain.AgentDatasource;
import com.forgeagent.agent.mapper.AgentDatasourceMapper;
import io.agentscope.core.tool.AgentTool;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * sql 类型工具 Provider(08方案 / A3 自 ToolAssembler 抽出,行为不变)。
 *
 * <p>绑定数据源缺失或停用时跳过并告警;查询工具配套注册 schema 明细工具
 * (只读元数据,后续在装配层按同一条审批规则包装)。</p>
 *
 * @author ForgeAgent
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SqlToolProvider implements ToolProvider {

    private final AgentDatasourceMapper datasourceMapper;
    private final SqlQueryService sqlQueryService;

    @Override
    public String type() {
        return "sql";
    }

    @Override
    public List<AgentTool> provide(ToolAssembler.ToolDef def, ToolAssembler.BuildContext ctx) {
        AgentDatasource ds = def.datasourceId() == null
            ? null : datasourceMapper.selectById(def.datasourceId());
        if (ds == null || "1".equals(ds.getStatus())) {
            log.warn("[ToolAssembler] SQL 工具 [{}] 绑定数据源缺失或停用,跳过注册", def.name());
            return List.of();
        }
        return List.of(
            new SqlQueryTool(def, ds, sqlQueryService, ctx.sqlCollector()),
            new SqlSchemaTool(def, ds, sqlQueryService));
    }
}
