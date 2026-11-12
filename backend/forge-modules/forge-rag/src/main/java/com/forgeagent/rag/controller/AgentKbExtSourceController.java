package com.forgeagent.rag.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import com.forgeagent.common.core.domain.R;
import com.forgeagent.common.log.annotation.Log;
import com.forgeagent.common.log.enums.BusinessType;
import com.forgeagent.common.web.core.BaseController;
import com.forgeagent.rag.domain.AgentKbExtSource;
import com.forgeagent.rag.service.KbExtSourceService;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 知识库外部源 Controller(R5-4):URL 抓取 + S3/Confluence SPI 骨架
 *
 * @author ForgeAgent
 */
@Validated
@RequiredArgsConstructor
@RestController
@RequestMapping("/agent/knowledge/ext-source")
public class AgentKbExtSourceController extends BaseController {

    private final KbExtSourceService extSourceService;

    /**
     * 登记外部源
     */
    @SaCheckPermission("agent:knowledgeDoc:upload")
    @Log(title = "知识库外部源", businessType = BusinessType.INSERT)
    @PostMapping
    public R<Long> create(@NotNull(message = "知识库ID不能为空") Long knowledgeId,
                          @NotNull(message = "来源类型不能为空") String sourceType,
                          String name, String configJson) {
        return R.ok(extSourceService.create(knowledgeId, sourceType, name, configJson));
    }

    /**
     * 外部源列表
     */
    @SaCheckPermission("agent:knowledgeDoc:list")
    @GetMapping("/list")
    public R<List<AgentKbExtSource>> list(@NotNull(message = "知识库ID不能为空") Long knowledgeId) {
        return R.ok(extSourceService.list(knowledgeId));
    }

    /**
     * 同步一次(条目级去重:已导入的跳过)
     */
    @SaCheckPermission("agent:knowledgeDoc:upload")
    @Log(title = "知识库外部源同步", businessType = BusinessType.UPDATE)
    @PostMapping("/{id}/sync")
    public R<Map<String, Object>> sync(@NotNull(message = "来源ID不能为空") @PathVariable Long id) {
        return R.ok(extSourceService.sync(id));
    }
}
