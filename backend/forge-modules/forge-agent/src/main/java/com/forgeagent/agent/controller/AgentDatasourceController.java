package com.forgeagent.agent.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import com.forgeagent.agent.domain.bo.AgentDatasourceBo;
import com.forgeagent.agent.domain.vo.AgentDatasourceVo;
import com.forgeagent.agent.service.IAgentDatasourceService;
import com.forgeagent.common.core.domain.PageResult;
import com.forgeagent.common.core.domain.R;
import com.forgeagent.common.core.validate.AddGroup;
import com.forgeagent.common.core.validate.EditGroup;
import com.forgeagent.common.log.annotation.Log;
import com.forgeagent.common.log.enums.BusinessType;
import com.forgeagent.common.mybatis.core.page.PageQuery;
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
import java.util.Map;

/**
 * 问数数据源 Controller
 *
 * @author ForgeAgent
 */
@Validated
@RequiredArgsConstructor
@RestController
@RequestMapping("/agent/datasource")
public class AgentDatasourceController extends BaseController {

    private final IAgentDatasourceService datasourceService;

    /**
     * 分页查询数据源
     */
    @SaCheckPermission("agent:datasource:list")
    @GetMapping("/list")
    public R<PageResult<AgentDatasourceVo>> list(@Validated PageQuery pageQuery, AgentDatasourceBo bo) {
        return R.ok(datasourceService.queryPageList(bo, pageQuery));
    }

    /**
     * 数据源详情
     */
    @SaCheckPermission("agent:datasource:query")
    @GetMapping("/{id}")
    public R<AgentDatasourceVo> getInfo(@NotNull(message = "主键不能为空")
                                        @PathVariable Long id) {
        return R.ok(datasourceService.queryById(id));
    }

    /**
     * 新增数据源
     */
    @SaCheckPermission("agent:datasource:add")
    @Log(title = "问数数据源", businessType = BusinessType.INSERT)
    @PostMapping()
    public R<Void> add(@Validated(AddGroup.class) @RequestBody AgentDatasourceBo bo) {
        return toAjax(datasourceService.insertByBo(bo));
    }

    /**
     * 修改数据源
     */
    @SaCheckPermission("agent:datasource:edit")
    @Log(title = "问数数据源", businessType = BusinessType.UPDATE)
    @PutMapping()
    public R<Void> edit(@Validated(EditGroup.class) @RequestBody AgentDatasourceBo bo) {
        return toAjax(datasourceService.updateByBo(bo));
    }

    /**
     * 启用/停用数据源
     */
    @SaCheckPermission("agent:datasource:edit")
    @Log(title = "问数数据源", businessType = BusinessType.UPDATE)
    @PutMapping("/status")
    public R<Void> changeStatus(@RequestParam @NotNull Long id,
                                @RequestParam @NotBlank String status) {
        return toAjax(datasourceService.changeStatus(id, status));
    }

    /**
     * 删除数据源
     */
    @SaCheckPermission("agent:datasource:remove")
    @Log(title = "问数数据源", businessType = BusinessType.DELETE)
    @DeleteMapping("/{ids}")
    public R<Void> remove(@NotEmpty(message = "主键不能为空")
                          @PathVariable Long[] ids) {
        return toAjax(datasourceService.deleteByIds(List.of(ids)));
    }

    /**
     * 测试连通性(已保存数据源密码留空沿用库内配置;也支持未保存配置的临时测试)
     */
    @SaCheckPermission("agent:datasource:test")
    @PostMapping("/test")
    public R<Map<String, Object>> test(@RequestBody AgentDatasourceBo bo) {
        Long elapsed = datasourceService.testConnection(bo);
        return R.ok(Map.of("elapsedMs", elapsed));
    }

    /**
     * 查看表结构快照
     */
    @SaCheckPermission("agent:datasource:query")
    @GetMapping("/{id}/schema")
    public R<String> getSchema(@NotNull(message = "主键不能为空")
                               @PathVariable Long id) {
        // R.ok(String) 会命中 ok(msg) 重载,必须用 ok(msg, data)
        return R.ok("操作成功", datasourceService.getSchema(id));
    }

    /**
     * 同步表结构快照
     */
    @SaCheckPermission("agent:datasource:test")
    @Log(title = "问数数据源", businessType = BusinessType.UPDATE)
    @PutMapping("/{id}/schema")
    public R<String> refreshSchema(@NotNull(message = "主键不能为空")
                                   @PathVariable Long id) {
        return R.ok("操作成功", datasourceService.refreshSchema(id));
    }

    /**
     * 可查询表清单(名称+注释,供白名单选择)
     */
    @SaCheckPermission("agent:datasource:query")
    @GetMapping("/{id}/tables")
    public R<List<Map<String, Object>>> listTables(@NotNull(message = "主键不能为空")
                                                   @PathVariable Long id) {
        return R.ok(datasourceService.listTables(id));
    }
}
