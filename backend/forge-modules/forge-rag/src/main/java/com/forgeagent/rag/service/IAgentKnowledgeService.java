package com.forgeagent.rag.service;

import com.forgeagent.rag.domain.bo.AgentKnowledgeBo;
import com.forgeagent.rag.domain.bo.KnowledgeSearchBo;
import com.forgeagent.rag.domain.vo.AgentKbZeroHitLogVo;
import com.forgeagent.rag.domain.vo.AgentKnowledgeVo;
import com.forgeagent.rag.domain.vo.KnowledgeSearchHitVo;
import com.forgeagent.rag.domain.vo.KbRebuildProgressVo;
import com.forgeagent.common.core.domain.PageResult;
import com.forgeagent.common.mybatis.core.page.PageQuery;
import java.util.List;
/**
 * 知识库注册 Service 接口
 *
 * @author ForgeAgent
 */
public interface IAgentKnowledgeService {

    PageResult<AgentKnowledgeVo> queryPageList(AgentKnowledgeBo bo, PageQuery pageQuery);

    AgentKnowledgeVo queryById(Long id);

    Boolean insertByBo(AgentKnowledgeBo bo);

    Boolean updateByBo(AgentKnowledgeBo bo);

    Boolean changeStatus(Long id, String status);

    Boolean deleteByIds(List<Long> ids);

    /**
     * 向量检索:对指定知识库做一次召回
     */
    List<KnowledgeSearchHitVo> search(KnowledgeSearchBo bo);

    /**
     * 向量检索(带回执):命中 + 降级原因 + 阈值口径 + 各路召回数 + 耗时
     */
    com.forgeagent.rag.service.retrieve.SearchOutcome searchOutcome(KnowledgeSearchBo bo);

    /**
     * 内部检索入口:跳过登录态权限校验,仅供工作流触发器等无登录上下文的后台链路使用;
     * 仍保留知识库存在性与状态校验。不得为该方法暴露任何 Controller 端点
     */
    List<KnowledgeSearchHitVo> searchInternal(KnowledgeSearchBo bo);

    /**
     * 异步记录知识库零命中查询(静默失败,不阻塞对话);
     * 用于运营发现知识缺口、反哺内容建设
     */
    void recordZeroHit(Long agentId, Long sessionId, Long userId,
                       String originalQuery, String rewrittenQuery, List<Long> knowledgeIds);

    /**
     * 分页查询零命中查询日志
     */
    PageResult<AgentKbZeroHitLogVo> queryZeroHitPage(Long agentId, String keyword, PageQuery pageQuery);

    /**
     * 异步记录一次检索的完整回执(命中数/首条分/口径/降级/耗时),静默失败。
     * 与零命中日志并存:零命中日志回答"什么没找到",这里回答"这次检索走成什么样"
     */
    void recordQueryLog(com.forgeagent.rag.domain.bo.KbQueryLogBo bo);

    /**
     * 分页查询检索日志(可按 Agent、关键词、是否零命中筛选)
     */
    PageResult<com.forgeagent.rag.domain.vo.AgentKbQueryLogVo> queryLogPage(Long agentId, String keyword,
                                                                           Boolean zeroHit, PageQuery pageQuery);

    /**
     * 重建知识库索引:逐文档按「当前代次 + 1」写入新代分块,成功后切指针并清理旧代;
     * 重建期间旧代照常可检索
     */
    Boolean rebuildIndex(Long id);

    /**
     * 差异重建(R2-5):只重建配置过期(config_stale=1)的文档;
     * 返回触发的文档数,0 表示没有需要重建的文档
     */
    Integer rebuildStale(Long id);

    /**
     * 查询索引重建进度(按文档状态统计:0 待处理 / 1 已完成 / 2 失败)
     */
    KbRebuildProgressVo rebuildProgress(Long id);
}
