package com.forgeagent.agent.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import com.forgeagent.agent.domain.bo.AgentApiKeyBo;
import com.forgeagent.agent.domain.vo.AgentApiKeyCreateVo;
import com.forgeagent.agent.domain.vo.AgentApiKeyVo;
import com.forgeagent.agent.service.IAgentApiKeyService;
import com.forgeagent.common.core.domain.PageResult;
import com.forgeagent.common.core.domain.R;
import com.forgeagent.common.core.validate.AddGroup;
import com.forgeagent.common.log.annotation.Log;
import com.forgeagent.common.log.enums.BusinessType;
import com.forgeagent.common.mybatis.core.page.PageQuery;
import com.forgeagent.common.web.core.BaseController;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

/**
 * 空间 API Key Controller
 *
 * @author ForgeAgent
 */
@Validated
@RequiredArgsConstructor
@RestController
@RequestMapping("/agent/apikey")
public class AgentApiKeyController extends BaseController {

    private final IAgentApiKeyService apiKeyService;

    /**
     * 分页查询 API Key(列表脱敏)
     */
    @SaCheckPermission("agent:apikey:list")
    @GetMapping("/list")
    public R<PageResult<AgentApiKeyVo>> list(@Validated PageQuery pageQuery, AgentApiKeyBo bo) {
        return R.ok(apiKeyService.queryPageList(bo, pageQuery));
    }

    /**
     * 创建 API Key(明文仅此一次返回)
     */
    @SaCheckPermission("agent:apikey:add")
    @Log(title = "空间API Key", businessType = BusinessType.INSERT)
    @PostMapping()
    public R<AgentApiKeyCreateVo> add(@Validated(AddGroup.class) @RequestBody AgentApiKeyBo bo) {
        return R.ok("Key 创建成功,明文仅此一次展示,请妥善保存", apiKeyService.createByBo(bo));
    }

    /**
     * 吊销 API Key
     */
    @SaCheckPermission("agent:apikey:revoke")
    @Log(title = "空间API Key", businessType = BusinessType.UPDATE)
    @PutMapping("/revoke/{id}")
    public R<Void> revoke(@NotNull(message = "主键不能为空")
                          @PathVariable Long id) {
        return toAjax(apiKeyService.revoke(id));
    }
}
