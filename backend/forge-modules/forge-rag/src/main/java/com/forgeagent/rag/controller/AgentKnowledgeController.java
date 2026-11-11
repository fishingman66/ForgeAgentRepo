package com.forgeagent.rag.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import com.forgeagent.rag.domain.bo.AgentKnowledgeBo;
import com.forgeagent.rag.domain.bo.KnowledgeSearchBo;
import com.forgeagent.rag.domain.vo.AgentKbZeroHitLogVo;
import com.forgeagent.rag.domain.vo.AgentKnowledgeVo;
import com.forgeagent.rag.domain.vo.KnowledgeSearchHitVo;
import com.forgeagent.rag.domain.vo.KbRebuildProgressVo;
import com.forgeagent.rag.service.IAgentKnowledgeService;
import com.forgeagent.common.core.domain.PageResult;
import com.forgeagent.common.core.domain.R;
import com.forgeagent.common.core.validate.AddGroup;
import com.forgeagent.common.core.validate.EditGroup;
import com.forgeagent.common.log.annotation.Log;
import com.forgeagent.common.log.enums.BusinessType;
import com.forgeagent.common.mybatis.core.page.PageQuery;
import com.forgeagent.common.web.core.BaseController;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import java.util.List;
/**
 * 知识库注册 Controller
 *
 * @author ForgeAgent
 */
@Validated
@RequiredArgsConstructor
@RestController
@RequestMapping("/agent/knowledge")
public class AgentKnowledgeController extends BaseController {

    private final IAgentKnowledgeService knowledgeService;

    /**
     * 分页查询知识库
     */
    @SaCheckPermission("agent:knowledge:list")
    @GetMapping("/list")
    public R<PageResult<AgentKnowledgeVo>> list(@Validated PageQuery pageQuery, AgentKnowledgeBo bo) {
        return R.ok(knowledgeService.queryPageList(bo, pageQuery));
    }

    /**
     * 知识库详情
     */
    @SaCheckPermission("agent:knowledge:query")
    @GetMapping("/{id}")
    public R<AgentKnowledgeVo> getInfo(@NotNull(message = "主键不能为空")
                                       @PathVariable Long id) {
        return R.ok(knowledgeService.queryById(id));
    }

    /**
     * 新增知识库
     */
    @SaCheckPermission("agent:knowledge:add")
    @Log(title = "知识库注册", businessType = BusinessType.INSERT)
    @PostMapping()
    public R<Void> add(@Validated(AddGroup.class) @RequestBody AgentKnowledgeBo bo) {
        return toAjax(knowledgeService.insertByBo(bo));
    }

    /**
     * 修改知识库
     */
    @SaCheckPermission("agent:knowledge:edit")
    @Log(title = "知识库注册", businessType = BusinessType.UPDATE)
    @PutMapping()
    public R<Void> edit(@Validated(EditGroup.class) @RequestBody AgentKnowledgeBo bo) {
        return toAjax(knowledgeService.updateByBo(bo));
    }

    /**
     * 启用/停用知识库
     */
    @SaCheckPermission("agent:knowledge:edit")
    @Log(title = "知识库注册", businessType = BusinessType.UPDATE)
    @PutMapping("/status")
    public R<Void> changeStatus(@RequestParam @NotNull Long id,
                                @RequestParam @NotBlank String status) {
        return toAjax(knowledgeService.changeStatus(id, status));
    }

    /**
     * 重建知识库索引:逐文档按「当前代次 + 1」写入新代分块,成功后切指针并清理旧代。
     * 重建期间旧代照常可检索;全量文档都会重做,配置没变的文档建议走差异重建
     */
    @SaCheckPermission("agent:knowledge:edit")
    @Log(title = "知识库重建索引", businessType = BusinessType.UPDATE)
    @PutMapping("/{id}/reindex")
    public R<Void> reindex(@NotNull(message = "主键不能为空") @PathVariable Long id) {
        return toAjax(knowledgeService.rebuildIndex(id));
    }

    /**
     * 差异重建(R2-5):只重建配置过期(config_stale=1)的文档;
     * 解析指纹未变的文档直接复用解析产物,成本从"重解析+重嵌"降到"重切+重嵌"。
     * 返回触发的文档数,0 表示没有需要重建的文档
     */
    @SaCheckPermission("agent:knowledge:edit")
    @Log(title = "知识库差异重建", businessType = BusinessType.UPDATE)
    @PutMapping("/{id}/reindex-stale")
    public R<Integer> reindexStale(@NotNull(message = "主键不能为空") @PathVariable Long id) {
        return R.ok(knowledgeService.rebuildStale(id));
    }
    /**
     * 查询索引重建进度(按文档状态统计,前端可轮询展示重建完成度)
     */
    @SaCheckPermission("agent:knowledge:query")
    @GetMapping("/{id}/reindex/progress")
    public R<KbRebuildProgressVo> rebuildProgress(@NotNull(message = "主键不能为空") @PathVariable Long id) {
        return R.ok(knowledgeService.rebuildProgress(id));
    }

    /**
     * 向量检索:对指定知识库做一次召回
     */
    @SaCheckPermission("agent:knowledge:search")
    @Log(title = "知识库检索", businessType = BusinessType.OTHER)
    @PostMapping("/search")
    public R<List<KnowledgeSearchHitVo>> search(@Validated @RequestBody KnowledgeSearchBo bo) {
        return R.ok(knowledgeService.search(bo));
    }

    /**
     * 分页查询零命中查询日志(运营发现知识缺口)
     */
    @SaCheckPermission("agent:knowledge:list")
    @GetMapping("/zerohit/list")
    public R<PageResult<AgentKbZeroHitLogVo>> zeroHitList(@Validated PageQuery pageQuery,
                                                          Long agentId, String keyword) {
        return R.ok(knowledgeService.queryZeroHitPage(agentId, keyword, pageQuery));
    }

    /**
     * 分页查询检索日志(排障:本次为什么只召回 N 条、有没有降级、阈值作用在哪种分)
     */
    @SaCheckPermission("agent:knowledge:list")
    @GetMapping("/querylog/list")
    public R<PageResult<com.forgeagent.rag.domain.vo.AgentKbQueryLogVo>> queryLogList(
        @Validated PageQuery pageQuery, Long agentId, String keyword, Boolean zeroHit) {
        return R.ok(knowledgeService.queryLogPage(agentId, keyword, zeroHit, pageQuery));
    }

    /**
     * 删除知识库
     */
    @SaCheckPermission("agent:knowledge:remove")
    @Log(title = "知识库注册", businessType = BusinessType.DELETE)
    @DeleteMapping("/{ids}")
    public R<Void> remove(@NotEmpty(message = "主键不能为空")
                          @PathVariable Long[] ids) {
        return toAjax(knowledgeService.deleteByIds(List.of(ids)));
    }
}
