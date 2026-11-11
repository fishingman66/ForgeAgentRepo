package com.forgeagent.rag.service;

import com.forgeagent.common.core.domain.PageResult;
import com.forgeagent.common.mybatis.core.page.PageQuery;
import com.forgeagent.rag.domain.AgentKbEvalCase;
import com.forgeagent.rag.domain.AgentKbEvalRun;

import java.util.List;
import java.util.Map;

/**
 * 知识库检索评测服务:维护评测问题集,批量运行检索并统计命中率与 MRR,
 * 让分块大小/相关度阈值/重排模型的调参有数据依据而非凭感觉
 *
 * @author ForgeAgent
 */
public interface KbEvalService {

    /**
     * 新增评测用例
     */
    Long saveCase(AgentKbEvalCase evalCase);

    /**
     * 删除评测用例
     */
    void deleteCase(Long id);

    /**
     * 用例列表(按知识库)
     */
    List<AgentKbEvalCase> listCases(Long knowledgeId);

    /**
     * 批量运行评测:逐用例执行检索并按期望文档/关键词判定命中,
     * 统计命中率与 MRR 后落库;返回 汇总 + 每用例明细
     *
     * @param knowledgeId 知识库ID
     * @param topK        召回条数(空取 5)
     * @return run 汇总 + caseResults 明细(每用例:问题/是否命中/首个命中排名/相关度分)
     */
    Map<String, Object> runEvaluation(Long knowledgeId, Integer topK);

    /**
     * 评测运行历史(按知识库,最新在前)
     */
    List<AgentKbEvalRun> listRuns(Long knowledgeId, Integer limit);

    /**
     * 分页查询(与平台分页风格一致,供管理界面使用)
     */
    PageResult<AgentKbEvalRun> pageRuns(Long knowledgeId, PageQuery pageQuery);
}
