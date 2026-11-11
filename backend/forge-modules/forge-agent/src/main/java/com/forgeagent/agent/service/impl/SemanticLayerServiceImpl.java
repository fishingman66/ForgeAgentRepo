package com.forgeagent.agent.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import com.forgeagent.agent.domain.AgentDatasource;
import com.forgeagent.agent.domain.AgentSemanticDimension;
import com.forgeagent.agent.domain.AgentSemanticMetric;
import com.forgeagent.agent.domain.AgentSemanticValue;
import com.forgeagent.agent.domain.bo.AgentSemanticDimensionBo;
import com.forgeagent.agent.domain.bo.AgentSemanticMetricBo;
import com.forgeagent.agent.domain.bo.AgentSemanticValueBo;
import com.forgeagent.agent.domain.vo.AgentSemanticDimensionVo;
import com.forgeagent.agent.domain.vo.AgentSemanticMetricVo;
import com.forgeagent.agent.domain.vo.AgentSemanticValueVo;
import com.forgeagent.agent.mapper.AgentDatasourceMapper;
import com.forgeagent.agent.mapper.AgentSemanticDimensionMapper;
import com.forgeagent.agent.mapper.AgentSemanticMetricMapper;
import com.forgeagent.agent.mapper.AgentSemanticValueMapper;
import com.forgeagent.agent.service.ISemanticLayerService;
import com.forgeagent.agent.service.runner.SqlQueryService;
import com.forgeagent.agent.service.semantic.SemanticRouter;
import com.forgeagent.common.core.exception.ServiceException;
import com.forgeagent.common.core.utils.MapstructUtils;
import com.forgeagent.common.core.utils.StringUtils;
import com.forgeagent.common.json.utils.JsonUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 问数语义层Service实现
 *
 * @author ForgeAgent
 */
@Slf4j
@RequiredArgsConstructor
@Service
public class SemanticLayerServiceImpl implements ISemanticLayerService {

    private final AgentSemanticMetricMapper metricMapper;
    private final AgentSemanticDimensionMapper dimensionMapper;
    private final AgentSemanticValueMapper valueMapper;
    private final AgentDatasourceMapper datasourceMapper;
    private final SqlQueryService sqlQueryService;
    private final SemanticRouter semanticRouter;

    // ---------------- 指标 ----------------

    @Override
    public List<AgentSemanticMetricVo> listMetrics(Long datasourceId) {
        return metricMapper.selectVoList(new LambdaQueryWrapper<AgentSemanticMetric>()
            .eq(AgentSemanticMetric::getDatasourceId, datasourceId)
            .orderByAsc(AgentSemanticMetric::getSort)
            .orderByAsc(AgentSemanticMetric::getId));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void saveMetric(AgentSemanticMetricBo bo) {
        AgentSemanticMetric entity = MapstructUtils.convert(bo, AgentSemanticMetric.class);
        if (StringUtils.isBlank(entity.getStatus())) {
            entity.setStatus("0");
        }
        metricMapper.insert(entity);
        bumpVersion(entity.getDatasourceId());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateMetric(AgentSemanticMetricBo bo) {
        AgentSemanticMetric update = MapstructUtils.convert(bo, AgentSemanticMetric.class);
        metricMapper.updateById(update);
        bumpVersion(bo.getDatasourceId());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteMetric(Long id) {
        AgentSemanticMetric metric = metricMapper.selectById(id);
        if (metric == null) {
            return;
        }
        metricMapper.deleteById(id);
        bumpVersion(metric.getDatasourceId());
    }

    // ---------------- 维度 ----------------

    @Override
    public List<AgentSemanticDimensionVo> listDimensions(Long datasourceId) {
        return dimensionMapper.selectVoList(new LambdaQueryWrapper<AgentSemanticDimension>()
            .eq(AgentSemanticDimension::getDatasourceId, datasourceId)
            .orderByAsc(AgentSemanticDimension::getSort)
            .orderByAsc(AgentSemanticDimension::getId));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void saveDimension(AgentSemanticDimensionBo bo) {
        AgentSemanticDimension entity = MapstructUtils.convert(bo, AgentSemanticDimension.class);
        // 应用层查重:同数据源下 (table, column) 不允许重复(已去掉 DB 唯一键,避免软删除 del_flag 冲突)
        Long cnt = dimensionMapper.selectCount(new LambdaQueryWrapper<AgentSemanticDimension>()
            .eq(AgentSemanticDimension::getDatasourceId, entity.getDatasourceId())
            .eq(AgentSemanticDimension::getTableName, entity.getTableName())
            .eq(AgentSemanticDimension::getColumnName, entity.getColumnName()));
        if (cnt != null && cnt > 0) {
            throw new ServiceException("该列已存在维度定义,请勿重复添加");
        }
        if (StringUtils.isBlank(entity.getStatus())) {
            entity.setStatus("0");
        }
        if (StringUtils.isBlank(entity.getDimType())) {
            entity.setDimType("text");
        }
        dimensionMapper.insert(entity);
        bumpVersion(entity.getDatasourceId());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateDimension(AgentSemanticDimensionBo bo) {
        AgentSemanticDimension update = MapstructUtils.convert(bo, AgentSemanticDimension.class);
        dimensionMapper.updateById(update);
        bumpVersion(bo.getDatasourceId());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteDimension(Long id) {
        AgentSemanticDimension dim = dimensionMapper.selectById(id);
        if (dim == null) {
            return;
        }
        dimensionMapper.deleteById(id);
        bumpVersion(dim.getDatasourceId());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int importDimensionsFromSchema(Long datasourceId) {
        AgentDatasource ds = datasourceMapper.selectById(datasourceId);
        if (ds == null) {
            throw new ServiceException("数据源不存在");
        }
        if (StringUtils.isBlank(ds.getSchemaTables())) {
            throw new ServiceException("该数据源尚未同步表结构,请先同步");
        }
        List<SqlQueryService.TableSchema> tables = JsonUtils.parseArray(ds.getSchemaTables(), SqlQueryService.TableSchema.class);
        if (tables == null || tables.isEmpty()) {
            return 0;
        }

        // 已存在的 (table,column) 集合,跳过
        List<AgentSemanticDimension> existing = dimensionMapper.selectList(
            new LambdaQueryWrapper<AgentSemanticDimension>()
                .eq(AgentSemanticDimension::getDatasourceId, datasourceId));
        Set<String> existsKeys = new HashSet<>();
        for (AgentSemanticDimension d : existing) {
            existsKeys.add(d.getTableName().toLowerCase() + "." + d.getColumnName().toLowerCase());
        }

        int added = 0;
        for (SqlQueryService.TableSchema t : tables) {
            if (StringUtils.isBlank(t.columnsText())) {
                continue;
            }
            String tableName = t.name();
            for (String line : t.columnsText().split("\n")) {
                String trimmed = line.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }
                // 列名是行首第一个 token
                String[] parts = trimmed.split("\\s+");
                if (parts.length == 0) {
                    continue;
                }
                String columnName = parts[0];
                String key = tableName.toLowerCase() + "." + columnName.toLowerCase();
                if (existsKeys.contains(key)) {
                    continue;
                }
                String type = parts.length > 1 ? parts[1].toLowerCase() : "";
                String dimType = inferDimType(type);
                AgentSemanticDimension dim = new AgentSemanticDimension();
                dim.setDatasourceId(datasourceId);
                dim.setTableName(tableName);
                dim.setColumnName(columnName);
                dim.setDisplayName(columnName);
                dim.setDimType(dimType);
                dim.setIsTime("time".equals(dimType) ? 1 : 0);
                dim.setStatus("0");
                dimensionMapper.insert(dim);
                existsKeys.add(key);
                added++;
            }
        }
        if (added > 0) {
            bumpVersion(datasourceId);
        }
        return added;
    }

    // ---------------- 枚举值字典 ----------------

    @Override
    public List<AgentSemanticValueVo> listValues(Long datasourceId, String tableName, String columnName) {
        LambdaQueryWrapper<AgentSemanticValue> wrapper = new LambdaQueryWrapper<AgentSemanticValue>()
            .eq(AgentSemanticValue::getDatasourceId, datasourceId)
            .orderByAsc(AgentSemanticValue::getSort)
            .orderByAsc(AgentSemanticValue::getId);
        if (StringUtils.isNotBlank(tableName)) {
            wrapper.eq(AgentSemanticValue::getTableName, tableName);
        }
        if (StringUtils.isNotBlank(columnName)) {
            wrapper.eq(AgentSemanticValue::getColumnName, columnName);
        }
        return valueMapper.selectVoList(wrapper);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void saveValue(AgentSemanticValueBo bo) {
        AgentSemanticValue entity = MapstructUtils.convert(bo, AgentSemanticValue.class);
        if (StringUtils.isBlank(entity.getStatus())) {
            entity.setStatus("0");
        }
        valueMapper.insert(entity);
        bumpVersion(entity.getDatasourceId());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateValue(AgentSemanticValueBo bo) {
        AgentSemanticValue update = MapstructUtils.convert(bo, AgentSemanticValue.class);
        valueMapper.updateById(update);
        bumpVersion(bo.getDatasourceId());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void saveValues(List<AgentSemanticValueBo> bos) {
        if (bos == null || bos.isEmpty()) {
            return;
        }
        Long datasourceId = null;
        for (AgentSemanticValueBo bo : bos) {
            AgentSemanticValue entity = MapstructUtils.convert(bo, AgentSemanticValue.class);
            if (StringUtils.isBlank(entity.getStatus())) {
                entity.setStatus("0");
            }
            valueMapper.insert(entity);
            datasourceId = entity.getDatasourceId();
        }
        if (datasourceId != null) {
            bumpVersion(datasourceId);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteValue(Long id) {
        AgentSemanticValue value = valueMapper.selectById(id);
        if (value == null) {
            return;
        }
        valueMapper.deleteById(id);
        bumpVersion(value.getDatasourceId());
    }

    @Override
    public List<String> sampleColumnValues(Long datasourceId, String tableName, String columnName, int limit) {
        AgentDatasource ds = datasourceMapper.selectById(datasourceId);
        if (ds == null) {
            throw new ServiceException("数据源不存在");
        }
        if ("1".equals(ds.getStatus())) {
            throw new ServiceException("数据源已停用");
        }
        return sqlQueryService.sampleDistinctValues(ds, tableName, columnName, limit);
    }

    // ---------------- 内部工具 ----------------

    /**
     * 语义资产写操作后:版本号自增 + 清理词表缓存
     */
    private void bumpVersion(Long datasourceId) {
        if (datasourceId == null) {
            return;
        }
        datasourceMapper.update(null, new LambdaUpdateWrapper<AgentDatasource>()
            .eq(AgentDatasource::getId, datasourceId)
            .setSql("semantic_version = semantic_version + 1"));
        semanticRouter.evictVocabulary(datasourceId);
    }

    /**
     * 根据列类型推断维度类型
     */
    private String inferDimType(String type) {
        if (type == null) {
            return "text";
        }
        if (type.contains("date") || type.contains("time")) {
            return "time";
        }
        if (type.contains("int") || type.contains("decimal") || type.contains("float")
            || type.contains("double") || type.contains("number")) {
            return "text";
        }
        return "text";
    }
}
