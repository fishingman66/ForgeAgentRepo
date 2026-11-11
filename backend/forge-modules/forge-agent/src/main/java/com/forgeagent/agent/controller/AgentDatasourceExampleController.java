package com.forgeagent.agent.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import com.forgeagent.agent.domain.bo.AgentDatasourceExampleBo;
import com.forgeagent.agent.domain.vo.AgentDatasourceExampleVo;
import com.forgeagent.agent.service.IAgentDatasourceExampleService;
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
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 问数示例库 Controller(复用数据源权限,不新增菜单)
 *
 * @author ForgeAgent
 */
@Validated
@RequiredArgsConstructor
@RestController
@RequestMapping("/agent/datasource/example")
public class AgentDatasourceExampleController extends BaseController {

    private final IAgentDatasourceExampleService exampleService;

    /**
     * 按数据源查询示例列表
     */
    @SaCheckPermission("agent:datasource:query")
    @GetMapping("/list/{datasourceId}")
    public R<List<AgentDatasourceExampleVo>> list(@NotNull(message = "数据源ID不能为空")
                                                  @PathVariable Long datasourceId) {
        return R.ok(exampleService.queryList(datasourceId));
    }

    /**
     * 新增示例
     */
    @SaCheckPermission("agent:datasource:edit")
    @Log(title = "问数示例", businessType = BusinessType.INSERT)
    @PostMapping()
    public R<Void> add(@Validated(AddGroup.class) @RequestBody AgentDatasourceExampleBo bo) {
        return toAjax(exampleService.insertByBo(bo));
    }

    /**
     * 修改示例
     */
    @SaCheckPermission("agent:datasource:edit")
    @Log(title = "问数示例", businessType = BusinessType.UPDATE)
    @PutMapping()
    public R<Void> edit(@Validated(EditGroup.class) @RequestBody AgentDatasourceExampleBo bo) {
        return toAjax(exampleService.updateByBo(bo));
    }

    /**
     * 删除示例
     */
    @SaCheckPermission("agent:datasource:edit")
    @Log(title = "问数示例", businessType = BusinessType.DELETE)
    @DeleteMapping("/{ids}")
    public R<Void> remove(@NotEmpty(message = "主键不能为空")
                          @PathVariable Long[] ids) {
        return toAjax(exampleService.deleteByIds(List.of(ids)));
    }
}
