package com.forgeagent.rag.service;

import com.forgeagent.rag.domain.vo.AgentKbChunkVo;
import com.forgeagent.rag.domain.bo.KnowledgeChunkEditBo;
import com.forgeagent.rag.domain.vo.AgentKnowledgeDocumentVo;
import com.forgeagent.common.core.domain.PageResult;
import com.forgeagent.common.mybatis.core.page.PageQuery;
import org.springframework.web.multipart.MultipartFile;
import java.util.List;
/**
 * 知识库文档 Service
 *
 * @author ForgeAgent
 */
public interface IAgentKnowledgeDocumentService {

    /**
     * 分页查询知识库文档
     *
     * @param trashed 是否查回收站(true=只看回收站;false/null=只看正常文档)
     */
    PageResult<AgentKnowledgeDocumentVo> queryPageList(Long knowledgeId, PageQuery pageQuery, Boolean trashed);

    /**
     * 上传文档并触发异步解析向量化,返回文档ID
     */
    Long upload(Long knowledgeId, MultipartFile file);

    /**
     * 失败文档重试
     */
    Boolean retry(Long id);

    /**
     * 删除文档(移入回收站,不删分块与向量;超保留期由清理任务物理删除)
     */
    Boolean deleteByIds(List<Long> ids);

    /**
     * 从回收站恢复(R5-2)
     */
    Boolean restore(List<Long> ids);

    /**
     * 文档治理设置(R5-1/R5-2):可见性 + 授权角色 + 发布态 + 生效/过期窗口
     */
    Boolean updateGovernance(Long id, com.forgeagent.rag.domain.bo.DocGovernanceBo bo);

    /**
     * 审核(R5-2):PENDING_REVIEW → PUBLISHED / REJECTED
     */
    Boolean review(Long id, Boolean approve, String reason);

    /**
     * 查询文档分块列表
     */
    List<AgentKbChunkVo> listChunks(Long documentId);

    /**
     * 编辑分块内容(同步更新 token 数并重新向量化)
     */
    Boolean updateChunkContent(KnowledgeChunkEditBo bo);

    /**
     * 启用/禁用分块(禁用后不参与检索,不删向量)
     */
    Boolean changeChunkEnabled(Long chunkId, Boolean enabled);

    /**
     * 获取文档实体(下载用)
     */
    AgentKnowledgeDocumentVo queryById(Long id);

    /**
     * 解析文档原文件路径(下载用)
     */
    java.nio.file.Path resolveFilePath(Long id);
}
