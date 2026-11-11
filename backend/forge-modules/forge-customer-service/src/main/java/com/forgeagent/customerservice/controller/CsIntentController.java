package com.forgeagent.customerservice.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import com.forgeagent.common.core.domain.R;
import com.forgeagent.common.web.core.BaseController;
import com.forgeagent.customerservice.config.ConditionalOnCustomerServiceEnabled;
import com.forgeagent.customerservice.domain.CsIntent;
import com.forgeagent.customerservice.service.CsIntentService;
import com.forgeagent.customerservice.service.CsWorkspaceResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 意图管理端点(A3):运营页 CRUD。
 *
 * <p>列表含平台级意图(只读)与本空间意图(可改);平台级的写操作由服务层拒绝,
 * 避免某个空间把全局规则改坏。意图打标结果不进本端点——它跟着推荐记录走。</p>
 */
@RestController
@RequiredArgsConstructor
@SaCheckLogin
@ConditionalOnCustomerServiceEnabled
@RequestMapping("/agent/customerservice/intents")
public class CsIntentController extends BaseController {

    private final CsIntentService intentService;
    private final CsWorkspaceResolver workspaceResolver;

    /** 平台级 + 本空间意图,按优先级排序。 */
    @GetMapping
    public R<List<CsIntent>> list() {
        return R.ok(intentService.list(workspaceResolver.currentWorkspaceId()));
    }

    /** 新增或编辑;编辑时 workspace 归属以库内原行为准,不允许跨空间搬移。 */
    @PostMapping
    public R<CsIntent> save(@RequestBody CsIntent intent) {
        return R.ok(intentService.save(intent, workspaceResolver.currentWorkspaceId()));
    }

    /** 删除空间级意图;平台级意图由服务层拒绝。 */
    @DeleteMapping("/{id}")
    public R<Boolean> remove(@PathVariable Long id) {
        return R.ok(intentService.remove(id));
    }
}
