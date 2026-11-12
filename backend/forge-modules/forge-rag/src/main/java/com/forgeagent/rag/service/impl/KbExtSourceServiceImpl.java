package com.forgeagent.rag.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import com.forgeagent.common.core.exception.ServiceException;
import com.forgeagent.common.core.utils.StringUtils;
import com.forgeagent.rag.domain.AgentKbExtItem;
import com.forgeagent.rag.domain.AgentKbExtSource;
import com.forgeagent.rag.mapper.AgentKbExtItemMapper;
import com.forgeagent.rag.mapper.AgentKbExtSourceMapper;
import com.forgeagent.rag.service.IAgentKnowledgeDocumentService;
import com.forgeagent.rag.service.KbExtSourceService;
import com.forgeagent.rag.service.ext.ExtSourceConnector;
import com.forgeagent.rag.service.ext.UrlExtSourceConnector;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * R5-4 外部源服务实现
 *
 * @author ForgeAgent
 */
@Slf4j
@RequiredArgsConstructor
@Service
public class KbExtSourceServiceImpl implements KbExtSourceService {

    private final AgentKbExtSourceMapper sourceMapper;
    private final AgentKbExtItemMapper itemMapper;
    private final IAgentKnowledgeDocumentService documentService;
    private final List<ExtSourceConnector> connectors;

    private Map<String, ExtSourceConnector> connectorMap() {
        return connectors.stream().collect(Collectors.toMap(ExtSourceConnector::type, Function.identity(),
            (a, b) -> a));
    }

    @Override
    public Long create(Long knowledgeId, String sourceType, String name, String configJson) {
        if (knowledgeId == null) {
            throw new ServiceException("知识库ID不能为空");
        }
        String type = StringUtils.isBlank(sourceType) ? "" : sourceType.trim().toUpperCase();
        ExtSourceConnector connector = connectorMap().get(type);
        if (connector == null) {
            throw new ServiceException("不支持的外部源类型:" + sourceType + ",可选 "
                + connectorMap().keySet());
        }
        if ("URL".equals(type)) {
            // 登记即校验:地址非法/内网地址不应等到同步才暴露
            com.forgeagent.rag.service.ext.SsrfGuard.verify(UrlExtSourceConnector.urlOf(configJson));
        }
        AgentKbExtSource source = new AgentKbExtSource();
        source.setKnowledgeId(knowledgeId);
        source.setSourceType(type);
        source.setName(StringUtils.isBlank(name) ? type + "-来源" : name.trim());
        source.setConfigJson(configJson);
        source.setStatus("IDLE");
        source.setCreateTime(new Date());
        sourceMapper.insert(source);
        return source.getId();
    }

    @Override
    public List<AgentKbExtSource> list(Long knowledgeId) {
        return sourceMapper.selectList(new LambdaQueryWrapper<AgentKbExtSource>()
            .eq(AgentKbExtSource::getKnowledgeId, knowledgeId)
            .orderByDesc(AgentKbExtSource::getId));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> sync(Long sourceId) {
        AgentKbExtSource source = sourceMapper.selectById(sourceId);
        if (source == null) {
            throw new ServiceException("外部源不存在");
        }
        ExtSourceConnector connector = connectorMap().get(source.getSourceType());
        if (connector == null) {
            throw new ServiceException("外部源类型未注册连接器:" + source.getSourceType());
        }
        sourceMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<AgentKbExtSource>()
            .eq(AgentKbExtSource::getId, sourceId)
            .set(AgentKbExtSource::getStatus, "SYNCING"));
        int imported = 0;
        int skipped = 0;
        int failed = 0;
        try {
            List<ExtSourceConnector.ExtDocument> docs = connector.fetch(source.getConfigJson());
            Map<String, AgentKbExtItem> existing = itemMapper.selectList(
                new LambdaQueryWrapper<AgentKbExtItem>().eq(AgentKbExtItem::getSourceId, sourceId))
                .stream().collect(Collectors.toMap(AgentKbExtItem::getExternalId, i -> i, (a, b) -> a));
            for (ExtSourceConnector.ExtDocument doc : docs) {
                AgentKbExtItem item = existing.get(doc.externalId());
                if (item != null && item.getDocumentId() != null) {
                    skipped++;
                    continue;
                }
                try {
                    Long docId = documentService.importText(source.getKnowledgeId(), doc.title(), doc.content());
                    if (item == null) {
                        item = new AgentKbExtItem();
                        item.setSourceId(sourceId);
                        item.setExternalId(doc.externalId());
                        item.setTitle(StringUtils.substring(doc.title(), 0, 300));
                        item.setDocumentId(docId);
                        item.setStatus("IMPORTED");
                        item.setCreateTime(new Date());
                        itemMapper.insert(item);
                    } else {
                        itemMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<AgentKbExtItem>()
                            .eq(AgentKbExtItem::getId, item.getId())
                            .set(AgentKbExtItem::getDocumentId, docId)
                            .set(AgentKbExtItem::getStatus, "IMPORTED")
                            .set(AgentKbExtItem::getLastError, null));
                    }
                    imported++;
                } catch (Exception e) {
                    failed++;
                    log.warn("[知识库] 外部源 {} 条目 {} 导入失败: {}", sourceId, doc.externalId(), e.getMessage());
                    if (item != null) {
                        itemMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<AgentKbExtItem>()
                            .eq(AgentKbExtItem::getId, item.getId())
                            .set(AgentKbExtItem::getStatus, "FAILED")
                            .set(AgentKbExtItem::getLastError, StringUtils.substring(e.getMessage(), 0, 500)));
                    }
                }
            }
            sourceMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<AgentKbExtSource>()
                .eq(AgentKbExtSource::getId, sourceId)
                .set(AgentKbExtSource::getStatus, failed > 0 ? "FAILED" : "IDLE")
                .set(AgentKbExtSource::getLastSyncAt, new Date())
                .set(AgentKbExtSource::getLastError, failed > 0 ? failed + " 条导入失败" : null));
        } catch (Exception e) {
            sourceMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<AgentKbExtSource>()
                .eq(AgentKbExtSource::getId, sourceId)
                .set(AgentKbExtSource::getStatus, "FAILED")
                .set(AgentKbExtSource::getLastError, StringUtils.substring(e.getMessage(), 0, 500)));
            throw new ServiceException("同步失败:" + e.getMessage());
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sourceId", sourceId);
        result.put("imported", imported);
        result.put("skipped", skipped);
        result.put("failed", failed);
        return result;
    }
}
