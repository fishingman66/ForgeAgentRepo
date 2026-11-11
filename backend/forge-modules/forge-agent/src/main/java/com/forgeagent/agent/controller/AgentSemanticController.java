package com.forgeagent.agent.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import com.forgeagent.agent.domain.bo.AgentSemanticDimensionBo;
import com.forgeagent.agent.domain.bo.AgentSemanticMetricBo;
import com.forgeagent.agent.domain.bo.AgentSemanticValueBo;
import com.forgeagent.agent.domain.vo.AgentSemanticDimensionVo;
import com.forgeagent.agent.domain.vo.AgentSemanticMetricVo;
import com.forgeagent.agent.domain.vo.AgentSemanticValueVo;
import com.forgeagent.agent.service.ISemanticLayerService;
import com.forgeagent.common.core.domain.R;
import com.forgeagent.common.core.validate.AddGroup;
import com.forgeagent.common.core.validate.EditGroup;
import com.forgeagent.common.log.annotation.Log;
import com.forgeagent.common.log.enums.BusinessType;
import com.forgeagent.common.web.core.BaseController;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 问数语义层 Controller(复用数据源编辑权限)
 *
 * @author ForgeAgent
 */
@Validated
@RequiredArgsConstructor
@RestController
@RequestMapping("/agent/semantic")
public class AgentSemanticController extends BaseController {

    private final ISemanticLayerService semanticLayerService;

    // ---------------- 指标 ----------------

    @SaCheckPermission("agent:datasource:query")
    @GetMapping("/{datasourceId}/metrics")
    public R<List<AgentSemanticMetricVo>> listMetrics(@NotNull(message = "数据源ID不能为空")
                                                      @PathVariable Long datasourceId) {
        return R.ok(semanticLayerService.listMetrics(datasourceId));
    }

    @SaCheckPermission("agent:datasource:edit")
    @Log(title = "语义指标", businessType = BusinessType.INSERT)
    @PostMapping("/metric")
    public R<Void> addMetric(@Validated(AddGroup.class) @RequestBody AgentSemanticMetricBo bo) {
        semanticLayerService.saveMetric(bo);
        return R.ok();
    }

    @SaCheckPermission("agent:datasource:edit")
    @Log(title = "语义指标", businessType = BusinessType.UPDATE)
    @PutMapping("/metric")
    public R<Void> editMetric(@Validated(EditGroup.class) @RequestBody AgentSemanticMetricBo bo) {
        semanticLayerService.updateMetric(bo);
        return R.ok();
    }

    @SaCheckPermission("agent:datasource:edit")
    @Log(title = "语义指标", businessType = BusinessType.DELETE)
    @DeleteMapping("/metric/{id}")
    public R<Void> removeMetric(@NotNull(message = "ID不能为空") @PathVariable Long id) {
        semanticLayerService.deleteMetric(id);
        return R.ok();
    }

    // ---------------- 维度 ----------------

    @SaCheckPermission("agent:datasource:query")
    @GetMapping("/{datasourceId}/dimensions")
    public R<List<AgentSemanticDimensionVo>> listDimensions(@NotNull(message = "数据源ID不能为空")
                                                            @PathVariable Long datasourceId) {
        return R.ok(semanticLayerService.listDimensions(datasourceId));
    }

    @SaCheckPermission("agent:datasource:edit")
    @Log(title = "语义维度", businessType = BusinessType.INSERT)
    @PostMapping("/dimension")
    public R<Void> addDimension(@Validated(AddGroup.class) @RequestBody AgentSemanticDimensionBo bo) {
        semanticLayerService.saveDimension(bo);
        return R.ok();
    }

    @SaCheckPermission("agent:datasource:edit")
    @Log(title = "语义维度", businessType = BusinessType.UPDATE)
    @PutMapping("/dimension")
    public R<Void> editDimension(@Validated(EditGroup.class) @RequestBody AgentSemanticDimensionBo bo) {
        semanticLayerService.updateDimension(bo);
        return R.ok();
    }

    @SaCheckPermission("agent:datasource:edit")
    @Log(title = "语义维度", businessType = BusinessType.DELETE)
    @DeleteMapping("/dimension/{id}")
    public R<Void> removeDimension(@NotNull(message = "ID不能为空") @PathVariable Long id) {
        semanticLayerService.deleteDimension(id);
        return R.ok();
    }

    @SaCheckPermission("agent:datasource:edit")
    @Log(title = "语义维度", businessType = BusinessType.INSERT)
    @PostMapping("/{datasourceId}/dimensions/import")
    public R<Integer> importDimensions(@NotNull(message = "数据源ID不能为空")
                                       @PathVariable Long datasourceId) {
        return R.ok(semanticLayerService.importDimensionsFromSchema(datasourceId));
    }

    // ---------------- 枚举值字典 ----------------

    @SaCheckPermission("agent:datasource:query")
    @GetMapping("/{datasourceId}/values")
    public R<List<AgentSemanticValueVo>> listValues(@NotNull(message = "数据源ID不能为空")
                                                    @PathVariable Long datasourceId,
                                                    @RequestParam(required = false) String tableName,
                                                    @RequestParam(required = false) String columnName) {
        return R.ok(semanticLayerService.listValues(datasourceId, tableName, columnName));
    }

    @SaCheckPermission("agent:datasource:edit")
    @Log(title = "语义值字典", businessType = BusinessType.INSERT)
    @PostMapping("/value")
    public R<Void> addValue(@Validated(AddGroup.class) @RequestBody AgentSemanticValueBo bo) {
        semanticLayerService.saveValue(bo);
        return R.ok();
    }

    @SaCheckPermission("agent:datasource:edit")
    @Log(title = "语义值字典", businessType = BusinessType.UPDATE)
    @PutMapping("/value")
    public R<Void> editValue(@Validated(EditGroup.class) @RequestBody AgentSemanticValueBo bo) {
        semanticLayerService.updateValue(bo);
        return R.ok();
    }

    @SaCheckPermission("agent:datasource:edit")
    @Log(title = "语义值字典", businessType = BusinessType.INSERT)
    @PostMapping("/values/batch")
    public R<Void> addValues(@NotEmpty(message = "数据不能为空") @RequestBody List<AgentSemanticValueBo> bos) {
        semanticLayerService.saveValues(bos);
        return R.ok();
    }

    @SaCheckPermission("agent:datasource:edit")
    @Log(title = "语义值字典", businessType = BusinessType.DELETE)
    @DeleteMapping("/value/{id}")
    public R<Void> removeValue(@NotNull(message = "ID不能为空") @PathVariable Long id) {
        semanticLayerService.deleteValue(id);
        return R.ok();
    }

    /**
     * 采样指定列的 distinct 值,供快速构建值字典
     */
    @SaCheckPermission("agent:datasource:query")
    @GetMapping("/{datasourceId}/columns/{tableName}/{columnName}/sample")
    public R<List<String>> sampleColumnValues(@NotNull(message = "数据源ID不能为空")
                                              @PathVariable Long datasourceId,
                                              @PathVariable String tableName,
                                              @PathVariable String columnName,
                                              @RequestParam(defaultValue = "100") int limit) {
        return R.ok(semanticLayerService.sampleColumnValues(datasourceId, tableName, columnName, limit));
    }
}
