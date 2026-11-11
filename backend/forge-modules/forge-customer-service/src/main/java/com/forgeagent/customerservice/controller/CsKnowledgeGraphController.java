package com.forgeagent.customerservice.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import com.forgeagent.common.core.domain.R;
import com.forgeagent.common.web.core.BaseController;
import com.forgeagent.customerservice.config.ConditionalOnCustomerServiceEnabled;
import com.forgeagent.customerservice.domain.CsKgRelation;
import com.forgeagent.customerservice.domain.CsKgRelationVo;
import com.forgeagent.customerservice.service.CsKnowledgeGraphService;
import com.forgeagent.kg.service.KgNode;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 客服领域知识图谱维护端点(P3 / M3)。
 *
 * <p>领域三元组的人工登记入口:产品、故障、话术、意图之间的关系归空间共享级所有,
 * 由 {@link CsKnowledgeGraphService} 统一转交 forge-kg。图谱关闭时列表返回空、
 * 写入返回明确错误,不阻断客服其他端点。</p>
 *
 * <p>坐席端约定:走登录态 + 工作空间解析,不接收 workspaceId 参数,
 * 防止跨空间读写别人的领域知识。</p>
 */
@Validated
@RestController
@RequiredArgsConstructor
@SaCheckLogin
@ConditionalOnCustomerServiceEnabled
@RequestMapping("/agent/customerservice/knowledge/graph")
public class CsKnowledgeGraphController extends BaseController {

    private final CsKnowledgeGraphService graphService;

    /** 领域关系列表(权重降序,带冲突标注) */
    @GetMapping("/relations")
    public R<List<CsKgRelationVo>> relations(@RequestParam(required = false) Integer limit) {
        return R.ok(graphService.list(limit));
    }

    /** 冲突盘点:同一实体对上说法相反的关系两两一组,审核动作=删掉错的那条 */
    @GetMapping("/conflicts")
    public R<List<List<CsKgRelationVo>>> conflicts(@RequestParam(required = false) Integer limit) {
        return R.ok(graphService.conflicts(limit));
    }

    /** 检索扩展效果:最近 N 天日聚合,前端对比扩展/未扩展的零命中率 */
    @GetMapping("/expand-metrics")
    public R<List<java.util.Map<String, Object>>> expandMetrics(
            @RequestParam(required = false) Integer days) {
        return R.ok(graphService.listExpandMetrics(days));
    }

    /** 手工登记领域三元组(幂等 upsert,重复登记只累加权重) */
    @PostMapping("/relations")
    public R<Void> add(@Validated @RequestBody CsKgRelation relation) {
        return toAjax(graphService.add(relation));
    }

    /** 删除一条领域关系;范围对不上或行不存在返回 false */
    @DeleteMapping("/relations/{id}")
    public R<Void> remove(@NotNull(message = "关系ID不能为空") @PathVariable Long id) {
        return toAjax(graphService.remove(id));
    }

    /** 空间内实体 TOP(维护页联想/盘点用) */
    @GetMapping("/nodes")
    public R<List<KgNode>> nodes(@RequestParam(required = false) Integer limit) {
        return R.ok(graphService.nodes(limit));
    }
}
