package com.forgeagent.agent.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import com.forgeagent.agent.domain.AgentDatasource;
import com.forgeagent.agent.domain.bo.AgentDatasourceBo;
import com.forgeagent.agent.domain.vo.AgentDatasourceVo;
import com.forgeagent.agent.mapper.AgentDatasourceMapper;
import com.forgeagent.agentcore.service.AuditLogHelper;
import com.forgeagent.agent.service.IAgentDatasourceService;
import com.forgeagent.agent.service.runner.AgentDataSourceRegistry;
import com.forgeagent.agent.service.runner.DatasourceCipher;
import com.forgeagent.agent.service.runner.SqlQueryService;
import com.forgeagent.common.core.domain.PageResult;
import com.forgeagent.common.core.exception.ServiceException;
import com.forgeagent.common.core.utils.MapstructUtils;
import com.forgeagent.common.core.utils.StringUtils;
import com.forgeagent.common.json.utils.JsonUtils;
import com.forgeagent.common.mybatis.core.page.PageQuery;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * 问数数据源Service实现
 *
 * @author ForgeAgent
 */
@Slf4j
@RequiredArgsConstructor
@Service
public class AgentDatasourceServiceImpl implements IAgentDatasourceService {

    private final AgentDatasourceMapper datasourceMapper;
    private final SqlQueryService sqlQueryService;
    private final AgentDataSourceRegistry datasourceRegistry;
    private final AuditLogHelper auditLogHelper;
    private final DatasourceCipher datasourceCipher;
    private final com.forgeagent.agentcore.service.ResourceAccessGuard resourceGuard;


    @Override
    public PageResult<AgentDatasourceVo> queryPageList(AgentDatasourceBo bo, PageQuery pageQuery) {
        LambdaQueryWrapper<AgentDatasource> wrapper = new LambdaQueryWrapper<AgentDatasource>()
            .like(StringUtils.isNotBlank(bo.getKeyword()), AgentDatasource::getName, bo.getKeyword())
            .eq(StringUtils.isNotBlank(bo.getStatus()), AgentDatasource::getStatus, bo.getStatus())
            .orderByDesc(AgentDatasource::getId);
        resourceGuard.applyVisible(wrapper, AgentDatasource::getWorkspaceId);
        Page<AgentDatasourceVo> page = datasourceMapper.selectVoPage(pageQuery.build(), wrapper);
        page.getRecords().forEach(this::fillSchemaSize);
        return PageResult.build(page.getRecords(), page.getTotal());
    }

    @Override
    public AgentDatasourceVo queryById(Long id) {
        AgentDatasource entity = datasourceMapper.selectById(id);
        if (entity != null) {
            resourceGuard.assertReadable(entity.getWorkspaceId());
        }
        AgentDatasourceVo vo = MapstructUtils.convert(entity, AgentDatasourceVo.class);
        if (vo == null) {
            throw new ServiceException("数据源不存在");
        }
        fillSchemaSize(vo);
        return vo;
    }

    @Override
    public Boolean insertByBo(AgentDatasourceBo bo) {
        Long scope = resourceGuard.resolveCreateWorkspace(bo.getWorkspaceId(), Boolean.TRUE.equals(bo.getShared()));
        checkNameUnique(bo.getName(), null, scope);
        AgentDatasource entity = MapstructUtils.convert(bo, AgentDatasource.class);
        entity.setWorkspaceId(scope);
        if (StringUtils.isBlank(entity.getDbType())) {
            entity.setDbType("mysql");
        }
        entity.setStatus("0");
        // 密码 AES 加密落库
        entity.setPassword(datasourceCipher.encrypt(entity.getPassword()));
        boolean ok = datasourceMapper.insert(entity) > 0;
        if (ok) {
            auditLogHelper.record(null, "datasource.create", "datasource", String.valueOf(entity.getId()),
                "新增问数数据源[" + entity.getName() + "] " + entity.getHost() + ":" + entity.getPort()
                    + "/" + entity.getDbName());
        }
        return ok;
    }

    @Override
    public Boolean updateByBo(AgentDatasourceBo bo) {
        AgentDatasource exists = datasourceMapper.selectById(bo.getId());
        if (exists == null) {
            throw new ServiceException("数据源不存在");
        }
        resourceGuard.assertWritable(exists.getWorkspaceId());
        checkNameUnique(bo.getName(), bo.getId(), exists.getWorkspaceId());
        AgentDatasource update = MapstructUtils.convert(bo, AgentDatasource.class);
        update.setWorkspaceId(null); // 归属不可变
        // 编辑时密码留空表示不修改(updateById 忽略 null 字段),非空则加密落库
        if (StringUtils.isBlank(update.getPassword())) {
            update.setPassword(null);
        } else {
            update.setPassword(datasourceCipher.encrypt(update.getPassword()));
        }
        boolean ok = datasourceMapper.updateById(update) > 0;
        if (ok) {
            // 连接配置可能已变化,强制重建连接池
            datasourceRegistry.evict(bo.getId());
            auditLogHelper.record(null, "datasource.update", "datasource", String.valueOf(bo.getId()),
                "修改问数数据源[" + bo.getName() + "]");
        }
        return ok;
    }

    @Override
    public Boolean changeStatus(Long id, String status) {
        if (!"0".equals(status) && !"1".equals(status)) {
            throw new ServiceException("非法的数据源状态");
        }
        AgentDatasource current = datasourceMapper.selectById(id);
        if (current != null) {
            resourceGuard.assertWritable(current.getWorkspaceId());
        }
        AgentDatasource update = new AgentDatasource();
        update.setId(id);
        update.setStatus(status);
        boolean ok = datasourceMapper.updateById(update) > 0;
        if (ok) {
            datasourceRegistry.evict(id);
            auditLogHelper.record(null, "datasource.toggle", "datasource", String.valueOf(id),
                "数据源状态变更为 " + ("0".equals(status) ? "启用" : "停用"));
        }
        return ok;
    }

    @Override
    public Boolean deleteByIds(List<Long> ids) {
        for (Long id : ids) {
            AgentDatasource current = datasourceMapper.selectById(id);
            if (current != null) {
                resourceGuard.assertWritable(current.getWorkspaceId());
            }
        }
        boolean ok = datasourceMapper.deleteByIds(ids) > 0;
        if (ok) {
            ids.forEach(id -> {
                datasourceRegistry.evict(id);
                auditLogHelper.record(null, "datasource.delete", "datasource", String.valueOf(id),
                    "删除问数数据源 id=" + id);
            });
        }
        return ok;
    }

    @Override
    public Long testConnection(AgentDatasourceBo bo) {
        AgentDatasource target = resolveTestTarget(bo);
        return sqlQueryService.testConnection(target);
    }

    @Override
    public String refreshSchema(Long id) {
        AgentDatasource ds = datasourceMapper.selectById(id);
        if (ds == null) {
            throw new ServiceException("数据源不存在");
        }
        if ("1".equals(ds.getStatus())) {
            throw new ServiceException("数据源已停用,无法同步表结构");
        }
        resourceGuard.assertWritable(ds.getWorkspaceId());
        SqlQueryService.SchemaData schemaData = sqlQueryService.buildSchemaData(ds);
        AgentDatasource update = new AgentDatasource();
        update.setId(id);
        update.setSchemaSnapshot(schemaData.snapshotText());
        update.setSchemaTables(JsonUtils.toJsonString(schemaData.tables()));
        update.setSchemaSyncedAt(new Date());
        datasourceMapper.updateById(update);
        auditLogHelper.record(null, "datasource.schema.refresh", "datasource", String.valueOf(id),
            "同步数据源[" + ds.getName() + "]表结构,共 " + schemaData.tables().size() + " 张表");
        return schemaData.snapshotText();
    }

    @Override
    public String getSchema(Long id) {
        AgentDatasource ds = datasourceMapper.selectById(id);
        if (ds == null) {
            throw new ServiceException("数据源不存在");
        }
        resourceGuard.assertReadable(ds.getWorkspaceId());
        return ds.getSchemaSnapshot();
    }

    @Override
    public List<Map<String, Object>> listTables(Long id) {
        AgentDatasource ds = datasourceMapper.selectById(id);
        if (ds == null) {
            throw new ServiceException("数据源不存在");
        }
        resourceGuard.assertReadable(ds.getWorkspaceId());
        if (StringUtils.isBlank(ds.getSchemaTables())) {
            return List.of();
        }
        List<SqlQueryService.TableSchema> tables = JsonUtils.parseArray(ds.getSchemaTables(), SqlQueryService.TableSchema.class);
        if (tables == null) {
            return List.of();
        }
        List<Map<String, Object>> result = new java.util.ArrayList<>(tables.size());
        for (SqlQueryService.TableSchema t : tables) {
            Map<String, Object> item = new java.util.LinkedHashMap<>();
            item.put("name", t.name());
            item.put("comment", t.comment());
            result.add(item);
        }
        return result;
    }

    /**
     * 解析测试目标:已保存数据源(密码留空沿用库内配置)或临时配置
     */
    private AgentDatasource resolveTestTarget(AgentDatasourceBo bo) {
        if (bo.getId() != null) {
            AgentDatasource stored = datasourceMapper.selectById(bo.getId());
            if (stored == null) {
                throw new ServiceException("数据源不存在");
            }
            resourceGuard.assertReadable(stored.getWorkspaceId());
            if (StringUtils.isNotBlank(bo.getPassword())) {
                stored.setPassword(bo.getPassword());
            }
            if (StringUtils.isNotBlank(bo.getHost())) {
                stored.setHost(bo.getHost());
                stored.setPort(bo.getPort());
                stored.setDbName(bo.getDbName());
                stored.setUsername(bo.getUsername());
                stored.setParams(bo.getParams());
            }
            return stored;
        }
        if (StringUtils.isBlank(bo.getHost()) || bo.getPort() == null || StringUtils.isBlank(bo.getDbName())
            || StringUtils.isBlank(bo.getUsername()) || StringUtils.isBlank(bo.getPassword())) {
            throw new ServiceException("请完整填写连接信息");
        }
        AgentDatasource temp = MapstructUtils.convert(bo, AgentDatasource.class);
        temp.setId(-1L);
        return temp;
    }

    private void checkNameUnique(String name, Long excludeId, Long workspaceId) {
        if (StringUtils.isBlank(name)) {
            return;
        }
        LambdaQueryWrapper<AgentDatasource> wrapper = new LambdaQueryWrapper<AgentDatasource>()
            .eq(AgentDatasource::getName, name)
            .ne(excludeId != null, AgentDatasource::getId, excludeId);
        if (workspaceId == null) {
            wrapper.isNull(AgentDatasource::getWorkspaceId);
        } else {
            wrapper.eq(AgentDatasource::getWorkspaceId, workspaceId);
        }
        if (datasourceMapper.selectCount(wrapper) > 0) {
            throw new ServiceException("数据源名称已存在:" + name);
        }
    }

    /**
     * 填充快照规模字段(列表不回传快照全文)
     */
    private void fillSchemaSize(AgentDatasourceVo vo) {
        if (vo.getSchemaSnapshot() != null) {
            vo.setSchemaSize((long) vo.getSchemaSnapshot().length());
            vo.setSchemaSnapshot(null);
        }
    }
}
