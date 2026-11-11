package com.forgeagent.agent.service.runner;

import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import lombok.extern.slf4j.Slf4j;
import com.forgeagent.agent.domain.AgentDatasource;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 问数表结构查询工具(schema-as-tool)
 *
 * <p>表清单随系统提示词注入,字段明细由模型按需调用本工具获取,
 * 避免全量 schema 撑爆上下文;仅读取已同步的快照 JSON,不访问业务库,
 * 作为只读元数据工具不经审批门。</p>
 *
 * @author ForgeAgent
 */
@Slf4j
record SqlSchemaTool(ToolAssembler.ToolDef def, AgentDatasource datasource,
                     SqlQueryService sqlQueryService) implements AgentTool {

    @Override
    public String getName() {
        return def.name() + "_schema";
    }

    @Override
    public String getDescription() {
        return "获取数据源[" + datasource.getName() + "]中指定表的字段明细(字段/类型/主键/注释)。"
            + "生成 SQL 前必须先调用本工具确认所用表的字段,只使用返回的表和字段";
    }

    @Override
    public Map<String, Object> getParameters() {
        Map<String, Object> tablesProp = new LinkedHashMap<>();
        tablesProp.put("type", "array");
        tablesProp.put("items", Map.of("type", "string"));
        tablesProp.put("description", "要查看字段明细的表名列表(最多 10 张)");
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", Map.of("tables", tablesProp));
        schema.put("required", List.of("tables"));
        return schema;
    }

    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        Map<String, Object> input = param.getInput() == null ? Map.of() : param.getInput();
        return Mono.fromCallable(() -> execute(input)).subscribeOn(Schedulers.boundedElastic());
    }

    private ToolResultBlock execute(Map<String, Object> input) {
        Object raw = input.get("tables");
        List<String> names = new ArrayList<>();
        if (raw instanceof List<?> list) {
            for (Object item : list) {
                if (item != null) {
                    names.add(String.valueOf(item));
                }
            }
        } else if (raw != null) {
            names.add(String.valueOf(raw));
        }
        if (names.isEmpty()) {
            return ToolResultBlock.error("参数 tables 不能为空,请传入要查看的表名列表");
        }
        try {
            return ToolResultBlock.text(sqlQueryService.describeTables(datasource, names));
        } catch (Exception e) {
            String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            log.warn("[SqlSchemaTool] {} 查询失败: {}", getName(), message);
            return ToolResultBlock.error("表结构查询失败: " + message);
        }
    }
}
