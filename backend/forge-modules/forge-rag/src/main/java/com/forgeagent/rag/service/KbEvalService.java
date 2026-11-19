package com.forgeagent.rag.service;

import com.forgeagent.common.core.domain.PageResult;
import com.forgeagent.common.mybatis.core.page.PageQuery;
import com.forgeagent.rag.domain.AgentKbEvalCase;
import com.forgeagent.rag.domain.vo.AgentKbEvalCaseVo;
import com.forgeagent.rag.domain.vo.AgentKbEvalRunVo;
import com.forgeagent.rag.domain.vo.KbEvalCompareVo;
import com.forgeagent.rag.domain.vo.KbEvalGateVo;
import com.forgeagent.rag.domain.vo.KbEvalRunResultVo;

import java.util.List;

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
    List<AgentKbEvalCaseVo> listCases(Long knowledgeId);

    /**
     * 批量运行评测:逐用例执行检索并按期望文档/关键词判定命中,
     * 统计命中率与 MRR 后落库;返回 汇总 + 每用例明细
     *
     * @param knowledgeId 知识库ID
     * @param topK        召回条数(空取 5)
     * @return run 汇总 + metrics 五指标 + caseResults 明细(每用例:问题/是否命中/首个命中排名/相关度分)
     */
    KbEvalRunResultVo runEvaluation(Long knowledgeId, Integer topK);

    /**
     * 评测运行历史(按知识库,最新在前)
     */
    List<AgentKbEvalRunVo> listRuns(Long knowledgeId, Integer limit);

    /**
     * 分页查询(与平台分页风格一致,供管理界面使用)
     */
    PageResult<AgentKbEvalRunVo> pageRuns(Long knowledgeId, PageQuery pageQuery);

    /**
     * 当前语料指纹(R3-4):排序后的 (documentId, activeGeneration) | embeddingModel:dim | chunkFingerprint
     */
    String corpusFingerprint(Long knowledgeId);

    /**
     * 双跑对比(R3-4):按用例交集重算两侧指标后交 {@code GateJudgeCore} 判决,
     * 结果写回候选 run 的 gate_state/gate_reason
     *
     * @return verdict + 两侧指标 + 交集规模
     */
    KbEvalCompareVo compare(Long knowledgeId, Long baselineRunId, Long candidateRunId);

    /**
     * 重建门禁(R3-4):取最近两次运行做判决;不足两次按"首次(无基线)"处理,永不拦截
     */
    KbEvalGateVo gateCheck(Long knowledgeId);
}
