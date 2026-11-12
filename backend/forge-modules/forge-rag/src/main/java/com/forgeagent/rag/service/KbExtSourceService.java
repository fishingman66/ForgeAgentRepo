package com.forgeagent.rag.service;

import com.forgeagent.rag.domain.AgentKbExtSource;

import java.util.List;
import java.util.Map;

/**
 * R5-4 外部源服务:来源登记 + 同步入库(条目级去重与增量)
 *
 * @author ForgeAgent
 */
public interface KbExtSourceService {

    /**
     * 登记外部源(按 type 匹配连接器;URL 在登记时就校验地址合法性)
     */
    Long create(Long knowledgeId, String sourceType, String name, String configJson);

    List<AgentKbExtSource> list(Long knowledgeId);

    /**
     * 同步一次:拉取 → 逐条入库(已导入的跳过)→ 返回统计
     */
    Map<String, Object> sync(Long sourceId);
}
