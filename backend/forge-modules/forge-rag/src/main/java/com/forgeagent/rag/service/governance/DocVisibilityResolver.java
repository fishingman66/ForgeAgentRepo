package com.forgeagent.rag.service.governance;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import com.forgeagent.common.satoken.utils.LoginHelper;
import com.forgeagent.rag.domain.AgentKbDocAcl;
import com.forgeagent.rag.domain.AgentKnowledgeDocument;
import com.forgeagent.rag.mapper.AgentKbDocAclMapper;
import com.forgeagent.rag.mapper.AgentKnowledgeDocumentMapper;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * R5-1/R5-2 检索可见性解析器:把「治理谓词 + ACL 角色求交」统一算成 hidden 文档集。
 *
 * <p>可见性谓词(与方案 §R5-2 一致):{@code trashed=0 AND publish_status='PUBLISHED'
 * AND (effective_at IS NULL OR effective_at<=now) AND (expires_at IS NULL OR expires_at>now)};
 * 在此之上,{@code visibility='RESTRICTED'} 的文档还要求登录用户 roleIds 与其 ACL 行有交集。</p>
 *
 * <p><b>刻意不缓存</b>:一次过期的授权等于泄露,几 ms 的成本不值得换。未登录场景
 * (触发器/免登录接口)角色集为空,RESTRICTED 文档一律不可见(默认拒绝)。</p>
 *
 * <p>hidden 集由调用方下推到检索:<b>MySQL 全文</b>走 {@code document_id not in (...)} +
 * 同谓词 JOIN;<b>Milvus</b> 走 filter {@code document_id not in [...]};hidden 超过
 * {@value #VECTOR_FILTER_LIMIT} 时改用 visible 允许列表(表达式长度上限)。</p>
 *
 * @author ForgeAgent
 */
@Slf4j
@RequiredArgsConstructor
@Component
public class DocVisibilityResolver {

    /**
     * 向量 filter 的 not-in 列表上限,超过改用允许列表
     */
    public static final int VECTOR_FILTER_LIMIT = 500;

    /**
     * 允许列表兜底上限:超过则本次向量路直接跳过(关键词路仍可下推)
     */
    public static final int ALLOW_FILTER_LIMIT = 2000;

    public static final String VISIBILITY_RESTRICTED = "RESTRICTED";
    public static final String PUBLISH_PUBLISHED = "PUBLISHED";

    private final AgentKnowledgeDocumentMapper documentMapper;
    private final AgentKbDocAclMapper aclMapper;

    /**
     * 一次检索的文档范围
     *
     * @param hiddenDocIds  不可见文档ID(用于 not-in 下推)
     * @param visibleDocIds hidden 过多时给出的允许列表;null 表示按 not-in 即可
     */
    public record DocScope(List<Long> hiddenDocIds, List<Long> visibleDocIds) {

        public boolean isEmpty() {
            return hiddenDocIds.isEmpty();
        }

        /**
         * 允许列表也超限,本次向量路应跳过(避免把不可见文档召回后过滤)
         */
        public boolean vectorFilterOverflow() {
            return visibleDocIds != null && visibleDocIds.size() > ALLOW_FILTER_LIMIT;
        }
    }

    public DocScope resolve(Long knowledgeId) {
        List<AgentKnowledgeDocument> docs = documentMapper.selectList(
            new LambdaQueryWrapper<AgentKnowledgeDocument>()
                .eq(AgentKnowledgeDocument::getKnowledgeId, knowledgeId)
                .select(AgentKnowledgeDocument::getId, AgentKnowledgeDocument::getVisibility,
                    AgentKnowledgeDocument::getPublishStatus, AgentKnowledgeDocument::getEffectiveAt,
                    AgentKnowledgeDocument::getExpiresAt, AgentKnowledgeDocument::getTrashed));
        if (docs.isEmpty()) {
            return new DocScope(List.of(), null);
        }
        Date now = new Date();
        Set<Long> roleIds = currentRoleIds();
        List<Long> restrictedIds = docs.stream()
            .filter(d -> VISIBILITY_RESTRICTED.equals(d.getVisibility()))
            .map(AgentKnowledgeDocument::getId).toList();
        Set<Long> permittedDocs = restrictedIds.isEmpty() ? Set.of() : permittedDocIds(restrictedIds, roleIds);

        List<Long> hidden = new ArrayList<>();
        for (AgentKnowledgeDocument doc : docs) {
            if (Integer.valueOf(1).equals(doc.getTrashed())) {
                hidden.add(doc.getId());
            } else if (!PUBLISH_PUBLISHED.equals(doc.getPublishStatus() == null
                ? PUBLISH_PUBLISHED : doc.getPublishStatus())) {
                hidden.add(doc.getId());
            } else if (doc.getEffectiveAt() != null && doc.getEffectiveAt().after(now)) {
                hidden.add(doc.getId());
            } else if (doc.getExpiresAt() != null && !doc.getExpiresAt().after(now)) {
                hidden.add(doc.getId());
            } else if (VISIBILITY_RESTRICTED.equals(doc.getVisibility())
                && !permittedDocs.contains(doc.getId())) {
                hidden.add(doc.getId());
            }
        }
        if (hidden.isEmpty()) {
            return new DocScope(List.of(), null);
        }
        List<Long> visible = null;
        if (hidden.size() > VECTOR_FILTER_LIMIT) {
            Set<Long> hiddenSet = new HashSet<>(hidden);
            visible = docs.stream().map(AgentKnowledgeDocument::getId)
                .filter(id -> !hiddenSet.contains(id)).toList();
        }
        return new DocScope(List.copyOf(hidden), visible);
    }

    /**
     * RESTRICTED 文档中当前用户可访问的集合:用户 roleIds 与 ACL 行求交
     */
    private Set<Long> permittedDocIds(List<Long> restrictedIds, Set<Long> roleIds) {
        Set<Long> result = new HashSet<>();
        if (roleIds.isEmpty()) {
            return result;
        }
        List<AgentKbDocAcl> rows = aclMapper.selectList(new LambdaQueryWrapper<AgentKbDocAcl>()
            .in(AgentKbDocAcl::getDocumentId, restrictedIds));
        for (AgentKbDocAcl row : rows) {
            if (row.getRoleId() != null && roleIds.contains(row.getRoleId())) {
                result.add(row.getDocumentId());
            }
        }
        return result;
    }

    /**
     * 当前登录用户的角色ID集合;未登录或查询失败返回空集(默认拒绝)
     */
    private Set<Long> currentRoleIds() {
        try {
            Long userId = LoginHelper.getUserId();
            if (userId == null) {
                return Set.of();
            }
            return new HashSet<>(aclMapper.selectRoleIdsByUserId(userId));
        } catch (Exception e) {
            log.debug("[知识库] 当前用户角色解析失败,按无角色处理: {}", e.getMessage());
            return Set.of();
        }
    }
}
