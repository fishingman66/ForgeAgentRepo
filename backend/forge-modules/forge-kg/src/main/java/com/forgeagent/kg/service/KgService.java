package com.forgeagent.kg.service;

import com.forgeagent.common.core.utils.StringUtils;
import com.forgeagent.kg.config.KgProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 知识图谱门面(forge-kg):后端选择、开关、写入收口、召回、冲突判定。
 *
 * <p><b>调用方只需要这个类:</b>长期记忆用 {@code KgScope.agent(agentId,userId)},
 * 智能客服领域能力用 {@code KgScope.workspace(workspaceId,userId)};
 * 二者共用同一套存储实现、同一套归一化与唯一键口径。</p>
 *
 * <p><b>降级口径:</b>与向量服务一致——未实现的后端不是半启用,而是按关闭处理
 * 并告警一次(同值去重);图谱故障只让调用方拿不到关联信息,不抛到主流程。</p>
 *
 * @author ForgeAgent
 */
@Slf4j
@Service
public class KgService {

    private final List<KgStore> stores;
    private final KgProperties properties;

    /** 已告警过的 backend 取值;调用方每轮都会问开关,同值只喊一次 */
    private final AtomicReference<String> warnedBackend = new AtomicReference<>();

    public KgService(List<KgStore> stores, KgProperties properties) {
        this.stores = stores == null ? List.of() : stores;
        this.properties = properties;
    }

    /** 图谱是否可用(开关打开且存在匹配的后端实现) */
    public boolean enabled() {
        return store() != null;
    }

    /**
     * 幂等写入三元组(允许调用方在异步线程里调用)。
     *
     * <p>统一收口条数上限与空实体/空关系过滤。文本脱敏由各业务域在组装
     * {@link KgTriple} 前自己完成(forge-kg 不依赖任何业务模块的脱敏器)。</p>
     */
    public void ingest(KgScope scope, List<KgTriple> triples) {
        if (!validInput(scope, triples)) {
            return;
        }
        KgStore store = store();
        if (store == null) {
            return;
        }
        int max = Math.max(1, properties.getMaxTriplesPerIngest());
        List<KgTriple> accepted = new ArrayList<>(Math.min(max, triples.size()));
        for (KgTriple triple : triples) {
            if (accepted.size() >= max) {
                break;
            }
            if (triple == null) {
                continue;
            }
            String subject = StringUtils.trim(triple.subject());
            String relation = StringUtils.trim(triple.relation());
            String object = StringUtils.trim(triple.object());
            if (StringUtils.isBlank(subject) || StringUtils.isBlank(relation)
                || StringUtils.isBlank(object)) {
                continue;
            }
            if (KgText.nameKey(subject).isEmpty() || KgText.nameKey(object).isEmpty()
                || KgText.relation(relation).isEmpty()) {
                continue;
            }
            accepted.add(new KgTriple(subject, relation, object, triple.evidenceRef()));
        }
        if (accepted.isEmpty()) {
            return;
        }
        try {
            store.upsert(scope, accepted);
        } catch (Exception e) {
            log.warn("[Kg] 三元组写入失败(不影响主流程): {}", e.getMessage());
        }
    }

    /** 1 跳邻接召回;关闭、无命中或后端异常时返回空列表 */
    public List<KgEdge> neighbors(KgScope scope, List<String> texts, int limit) {
        if (!validInput(scope, texts) || limit <= 0) {
            return List.of();
        }
        KgStore store = store();
        if (store == null) {
            return List.of();
        }
        try {
            return store.neighbors(scope, texts, limit);
        } catch (Exception e) {
            log.warn("[Kg] 邻接召回失败: {}", e.getMessage());
            return List.of();
        }
    }

    /** 范围内显著度最高的实体(管理端/客服领域维护用) */
    public List<KgNode> nodes(KgScope scope, int limit) {
        if (scope == null || !scope.valid() || limit <= 0) {
            return List.of();
        }
        KgStore store = store();
        if (store == null) {
            return List.of();
        }
        try {
            return store.nodes(scope, limit);
        } catch (Exception e) {
            log.warn("[Kg] 实体查询失败: {}", e.getMessage());
            return List.of();
        }
    }

    /** 范围内全部关系边,按权重降序(维护页/领域知识盘点用) */
    public List<KgEdge> edges(KgScope scope, int limit) {
        if (scope == null || !scope.valid() || limit <= 0) {
            return List.of();
        }
        KgStore store = store();
        if (store == null) {
            return List.of();
        }
        try {
            return store.edges(scope, limit);
        } catch (Exception e) {
            log.warn("[Kg] 关系查询失败: {}", e.getMessage());
            return List.of();
        }
    }

    /** 删除一条关系边;范围对不上返回 false,后端异常也按 false 处理 */
    public boolean removeEdge(KgScope scope, Long edgeId) {
        if (scope == null || !scope.valid() || edgeId == null) {
            return false;
        }
        KgStore store = store();
        if (store == null) {
            return false;
        }
        try {
            return store.removeEdge(scope, edgeId);
        } catch (Exception e) {
            log.warn("[Kg] 关系删除失败: {}", e.getMessage());
            return false;
        }
    }

    /** 清空范围(遗忘/级联删除预留) */
    public void deleteScope(KgScope scope) {
        if (scope == null || !scope.valid()) {
            return;
        }
        KgStore store = store();
        if (store == null) {
            return;
        }
        try {
            store.deleteScope(scope);
        } catch (Exception e) {
            log.warn("[Kg] 范围清理失败: {}", e.getMessage());
        }
    }

    /** 关系冲突标注:返回应当标「说法相反」的边标识(内置词表 + kg.extra-conflict-pairs 配置扩展) */
    public Set<String> conflicts(List<KgEdge> edges) {
        return KgConflictPairs.conflictingEdges(edges, properties.getExtraConflictPairs());
    }

    /** 已实现的后端列表(运维/自检用) */
    public List<String> availableBackends() {
        List<String> backends = new ArrayList<>(stores.size());
        for (KgStore store : stores) {
            if (store != null) {
                backends.add(store.backend());
            }
        }
        return backends;
    }

    private static boolean validInput(KgScope scope, List<?> input) {
        return scope != null && scope.valid() && input != null && !input.isEmpty();
    }

    /** 按配置选后端;未实现的后端告警一次后返回 null */
    KgStore store() {
        if (!properties.isEnabled()) {
            return null;
        }
        String backend = StringUtils.trim(properties.getBackend());
        for (KgStore store : stores) {
            if (store != null && backend.equalsIgnoreCase(StringUtils.trim(store.backend()))) {
                return store;
            }
        }
        warnUnsupportedBackend(backend);
        return null;
    }

    private void warnUnsupportedBackend(String backend) {
        String value = StringUtils.defaultIfBlank(backend, "<空>");
        if (value.equals(warnedBackend.getAndSet(value))) {
            return;
        }
        log.warn("[Kg] kg.backend={} 没有对应的 KgStore 实现(当前可用 {}),知识图谱按关闭处理,"
            + "调用方主流程不受影响", value, availableBackends());
    }
}
