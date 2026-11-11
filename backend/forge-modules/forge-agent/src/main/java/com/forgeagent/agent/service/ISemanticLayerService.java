package com.forgeagent.agent.service;

import com.forgeagent.agent.domain.bo.AgentSemanticDimensionBo;
import com.forgeagent.agent.domain.bo.AgentSemanticMetricBo;
import com.forgeagent.agent.domain.bo.AgentSemanticValueBo;
import com.forgeagent.agent.domain.vo.AgentSemanticDimensionVo;
import com.forgeagent.agent.domain.vo.AgentSemanticMetricVo;
import com.forgeagent.agent.domain.vo.AgentSemanticValueVo;

import java.util.List;

/**
 * 问数语义层Service接口(指标/维度/枚举值字典的 CRUD 与辅助能力)
 *
 * @author ForgeAgent
 */
public interface ISemanticLayerService {

    // ---------- 指标 ----------

    List<AgentSemanticMetricVo> listMetrics(Long datasourceId);

    void saveMetric(AgentSemanticMetricBo bo);

    void updateMetric(AgentSemanticMetricBo bo);

    void deleteMetric(Long id);

    // ---------- 维度 ----------

    List<AgentSemanticDimensionVo> listDimensions(Long datasourceId);

    void saveDimension(AgentSemanticDimensionBo bo);

    void updateDimension(AgentSemanticDimensionBo bo);

    void deleteDimension(Long id);

    /**
     * 从数据源已同步的表结构快照批量导入维度(已存在的跳过)
     *
     * @return 新增维度条数
     */
    int importDimensionsFromSchema(Long datasourceId);

    // ---------- 枚举值字典 ----------

    List<AgentSemanticValueVo> listValues(Long datasourceId, String tableName, String columnName);

    void saveValue(AgentSemanticValueBo bo);

    void updateValue(AgentSemanticValueBo bo);

    void saveValues(List<AgentSemanticValueBo> bos);

    void deleteValue(Long id);

    /**
     * 采样指定列的 distinct 值,供前端快速选入值字典
     */
    List<String> sampleColumnValues(Long datasourceId, String tableName, String columnName, int limit);
}
