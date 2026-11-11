package com.forgeagent.rag.service;

import com.forgeagent.common.core.domain.PageResult;
import com.forgeagent.common.mybatis.core.page.PageQuery;
import com.forgeagent.rag.domain.vo.AgentKbQualityIssueVo;

import java.util.Map;

/**
 * 知识库质量信息服务(R3-5):质量问题登记/流转 + 从检索生成评测用例。
 *
 * <p>闭环:检索日志(零命中/低分) → 一键登记质量问题 → 排查修复 →
 * 从命中结果一键生成评测用例 → 评测运行回归。</p>
 *
 * @author ForgeAgent
 */
public interface KbQualityIssueService {

    /**
     * 登记质量问题:按 (knowledgeId, source, queryHash) 去重,重复登记返回已有记录不新增
     *
     * @return issueId(新登记或已存在的)
     */
    Long register(Long knowledgeId, String queryText, String reason, String source,
                  Long sourceId, String note);

    /**
     * 分页查询
     *
     * @param status 空=全部
     */
    PageResult<AgentKbQualityIssueVo> page(Long knowledgeId, String status, PageQuery pageQuery);

    /**
     * 状态流转:NEW → IN_PROGRESS → RESOLVED;reopen:RESOLVED → IN_PROGRESS。
     * revision 乐观锁,不一致直接拒绝
     */
    void changeStatus(Long id, String target, String note, Integer revision, String operator);

    /**
     * 关联评测用例(修复后补建用例做回归)
     */
    void linkCase(Long id, Long caseId);

    /**
     * 从检索命中生成评测用例(R3-5,source=DEBUG_PAGE):
     * query 作问题,指定 chunk 的文本作证据 span,判定口径与 R3-2 一致
     *
     * @return 用例ID
     */
    Long createCaseFromHit(Long knowledgeId, String question, Long documentId,
                           Long chunkId, String spanOverride);
}
