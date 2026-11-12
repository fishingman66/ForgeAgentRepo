package com.forgeagent.rag.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import com.forgeagent.common.core.domain.R;
import com.forgeagent.common.log.annotation.Log;
import com.forgeagent.common.log.enums.BusinessType;
import com.forgeagent.common.web.core.BaseController;
import com.forgeagent.rag.domain.bo.KbVlmConfigBo;
import com.forgeagent.rag.domain.vo.KbVlmConfigVo;
import com.forgeagent.rag.service.KbVlmConfigService;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 知识库图片识别模型配置(按工作空间):平台模型或第三方 OpenAI 兼容模型
 *
 * @author ForgeAgent
 */
@Validated
@RequiredArgsConstructor
@RestController
@RequestMapping("/agent/knowledge/vlm-config")
public class KbVlmConfigController extends BaseController {

    private final KbVlmConfigService vlmConfigService;

    /**
     * 读取配置(API Key 只回显掩码);workspaceId=0 表示平台默认
     */
    @SaCheckPermission("agent:knowledge:query")
    @GetMapping
    public R<KbVlmConfigVo> get(@NotNull(message = "工作空间不能为空") Long workspaceId) {
        return R.ok(vlmConfigService.get(workspaceId));
    }

    /**
     * 保存配置(UPSERT);apiKey 留空沿用已存密文
     */
    @SaCheckPermission("agent:knowledge:edit")
    @Log(title = "图片识别模型配置", businessType = BusinessType.UPDATE)
    @PutMapping
    public R<Void> save(@RequestBody KbVlmConfigBo bo) {
        vlmConfigService.save(bo);
        return R.ok();
    }

    /**
     * 连通性测试(用当前表单值;apiKey 留空时回退已存密文)
     */
    @SaCheckPermission("agent:knowledge:edit")
    @PostMapping("/test")
    public R<Map<String, Object>> test(@RequestBody KbVlmConfigBo bo) {
        return R.ok(vlmConfigService.test(bo));
    }
}
