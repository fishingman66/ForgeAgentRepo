package com.forgeagent.memory.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import com.forgeagent.kg.service.KgEdge;
import com.forgeagent.kg.service.KgNode;
import com.forgeagent.memory.domain.bo.AgentMemoryBo;
import com.forgeagent.memory.domain.vo.AgentMemoryVo;
import com.forgeagent.memory.service.IAgentMemoryService;
import com.forgeagent.common.core.domain.R;
import com.forgeagent.common.log.annotation.Log;
import com.forgeagent.common.log.enums.BusinessType;
import com.forgeagent.common.mybatis.core.page.PageQuery;
import com.forgeagent.common.web.core.BaseController;
import com.forgeagent.common.core.domain.PageResult;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Agent 长期记忆 Controller
 *
 * @author ForgeAgent
 */
@Validated
@RequiredArgsConstructor
@RestController
@RequestMapping("/agent/memory")
public class AgentMemoryController extends BaseController {

    private final IAgentMemoryService memoryService;

    /**
     * 分页查询 Agent 记忆
     */
    @SaCheckPermission("agent:memory:list")
    @GetMapping("/list")
    public R<PageResult<AgentMemoryVo>> list(@NotNull(message = "AgentID不能为空") Long agentId,
                                             @RequestParam(required = false) String keyword,
                                             PageQuery pageQuery) {
        return R.ok(memoryService.queryPageList(agentId, keyword, pageQuery));
    }

    /**
     * 手动新增记忆
     */
    @SaCheckPermission("agent:memory:add")
    @Log(title = "Agent长期记忆", businessType = BusinessType.INSERT)
    @PostMapping
    public R<Void> add(@Validated @RequestBody AgentMemoryBo bo) {
        return toAjax(memoryService.insertByBo(bo));
    }

    /**
     * 删除记忆
     */
    @SaCheckPermission("agent:memory:remove")
    @Log(title = "Agent长期记忆", businessType = BusinessType.DELETE)
    @DeleteMapping("/{ids}")
    public R<Void> remove(@NotNull(message = "记忆ID不能为空") @PathVariable Long[] ids) {
        List<Long> idList = Arrays.asList(ids);
        return toAjax(memoryService.deleteWithValidByIds(idList));
    }

    /**
     * 清空 Agent 全部记忆
     */
    @SaCheckPermission("agent:memory:remove")
    @Log(title = "Agent长期记忆", businessType = BusinessType.CLEAN)
    @DeleteMapping("/agent/{agentId}")
    public R<Void> clear(@NotNull(message = "AgentID不能为空") @PathVariable Long agentId) {
        return toAjax(memoryService.clearByAgent(agentId));
    }

    /**
     * 预览注入给模型的记忆块(含图谱关联背景,调试/运维用)
     */
    @SaCheckPermission("agent:memory:list")
    @GetMapping("/preview")
    public R<String> preview(@NotNull(message = "AgentID不能为空") Long agentId,
                             @RequestParam(required = false) Long userId,
                             @RequestParam(required = false) String query) {
        // 必须用 R.data 而不是 R.ok:R.ok(String) 命中 ok(msg) 重载,内容会进 msg 而 data 为 null
        return R.data(memoryService.previewMemoryBlock(agentId, userId, query));
    }

    /**
     * 语义检索(向量可用走语义召回,不可用回退评分排序;P3/M4 记忆管理页)
     */
    @SaCheckPermission("agent:memory:query")
    @GetMapping("/search")
    public R<List<AgentMemoryVo>> search(@NotNull(message = "AgentID不能为空") Long agentId,
                                         @RequestParam(required = false) Long userId,
                                         @NotBlank(message = "检索词不能为空") String query,
                                         @RequestParam(required = false) Integer limit) {
        return R.ok(memoryService.searchMemories(agentId, userId, query, limit));
    }

    /** 图谱-实体 TOP(记忆管理页图谱 tab;userId 为空只看平台级) */
    @SaCheckPermission("agent:memory:query")
    @GetMapping("/graph/nodes")
    public R<List<KgNode>> graphNodes(@NotNull(message = "AgentID不能为空") Long agentId,
                                      @RequestParam(required = false) Long userId,
                                      @RequestParam(required = false) Integer limit) {
        return R.ok(memoryService.graphNodes(agentId, userId, limit));
    }

    /** 图谱-关系边(记忆管理页图谱 tab;userId 为空只看平台级) */
    @SaCheckPermission("agent:memory:query")
    @GetMapping("/graph/edges")
    public R<List<KgEdge>> graphEdges(@NotNull(message = "AgentID不能为空") Long agentId,
                                      @RequestParam(required = false) Long userId,
                                      @RequestParam(required = false) Integer limit) {
        return R.ok(memoryService.graphEdges(agentId, userId, limit));
    }

    /**
     * 用户级遗忘(L5):级联删除 记忆(含向量)→图谱→归档表 的该用户数据。
     * confirm 必须传 FORGET,平台级记忆不在遗忘范围。
     */
    @SaCheckPermission("agent:memory:remove")
    @Log(title = "Agent长期记忆", businessType = BusinessType.CLEAN)
    @DeleteMapping("/user/{userId}/forget")
    public R<Long> forget(@NotNull(message = "AgentID不能为空") Long agentId,
                          @NotNull(message = "用户ID不能为空") @PathVariable Long userId,
                          @RequestParam String confirm) {
        return R.ok(memoryService.forgetUser(agentId, userId, confirm));
    }

    /** 手动触发一次记忆治理(消息归档+TTL+配额);治理未启用时报错 */
    @SaCheckPermission("agent:memory:remove")
    @Log(title = "Agent长期记忆", businessType = BusinessType.UPDATE)
    @PostMapping("/governance/run")
    public R<Map<String, Long>> runGovernance() {
        return R.ok(memoryService.runGovernance());
    }

    /**
     * 存量记忆补建知识图谱(P3 / M3)。
     *
     * <p>手动触发、异步执行,会真实消耗模型调用;只补建指定用户的记忆,
     * 不传 userId 表示只补建平台级记忆。</p>
     */
    @SaCheckPermission("agent:memory:add")
    @Log(title = "Agent长期记忆", businessType = BusinessType.UPDATE)
    @PostMapping("/graph/rebuild")
    public R<Void> rebuildGraph(@NotNull(message = "AgentID不能为空") Long agentId,
                                @RequestParam(required = false) Long userId) {
        return toAjax(memoryService.rebuildGraphAsync(agentId, userId));
    }
}
