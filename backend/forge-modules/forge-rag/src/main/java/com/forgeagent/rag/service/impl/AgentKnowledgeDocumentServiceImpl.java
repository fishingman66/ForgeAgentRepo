package com.forgeagent.rag.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.forgeagent.agentcore.service.WorkspacePermissionHelper;
import com.forgeagent.agentcore.service.AuditLogHelper;
import com.forgeagent.agentcore.utils.ChatTexts;
import com.forgeagent.rag.service.document.TokenEstimator;
import com.forgeagent.rag.domain.bo.KnowledgeChunkEditBo;
import java.util.Map;
import org.springframework.util.DigestUtils;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import com.forgeagent.rag.config.KnowledgeProperties;
import com.forgeagent.rag.domain.AgentKbChunk;
import com.forgeagent.rag.domain.AgentKnowledge;
import com.forgeagent.rag.domain.AgentKnowledgeDocument;
import com.forgeagent.rag.domain.vo.AgentKbChunkVo;
import com.forgeagent.rag.domain.vo.AgentKnowledgeDocumentVo;
import com.forgeagent.rag.mapper.AgentKbChunkMapper;
import com.forgeagent.rag.mapper.AgentKbDocAclMapper;
import com.forgeagent.rag.mapper.AgentKnowledgeDocumentMapper;
import com.forgeagent.rag.mapper.AgentKnowledgeMapper;
import com.forgeagent.rag.service.IAgentKnowledgeDocumentService;
import com.forgeagent.rag.service.KnowledgeDocProcessor;
import com.forgeagent.rag.service.document.DocumentParseGuard;
import com.forgeagent.rag.service.document.DocumentParserFactory;
import com.forgeagent.rag.service.embedding.EmbeddingFactory;
import com.forgeagent.rag.service.vector.VectorStore;
import com.forgeagent.common.core.domain.PageResult;
import com.forgeagent.common.core.exception.ServiceException;
import com.forgeagent.common.core.utils.MapstructUtils;
import com.forgeagent.common.core.utils.StringUtils;
import com.forgeagent.common.mybatis.core.page.PageQuery;
import com.forgeagent.common.satoken.utils.LoginHelper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
/**
 * 知识库文档 Service 实现
 *
 * @author ForgeAgent
 */
@Slf4j
@RequiredArgsConstructor
@Service
public class AgentKnowledgeDocumentServiceImpl implements IAgentKnowledgeDocumentService {

    private final AgentKnowledgeMapper knowledgeMapper;
    private final AgentKnowledgeDocumentMapper documentMapper;
    private final AgentKbChunkMapper chunkMapper;
    private final AgentKbDocAclMapper aclMapper;
    private final KnowledgeDocProcessor docProcessor;
    private final org.springframework.context.ApplicationEventPublisher eventPublisher;
    private final VectorStore vectorStore;
    private final KnowledgeProperties properties;
    private final EmbeddingFactory embeddingFactory;
    private final AuditLogHelper auditLogHelper;
    private final WorkspacePermissionHelper permissionHelper;

    @Override
    public PageResult<AgentKnowledgeDocumentVo> queryPageList(Long knowledgeId, PageQuery pageQuery, Boolean trashed) {
        AgentKnowledge kb = getKbOrThrow(knowledgeId);
        checkWsReadable(kb.getWorkspaceId());
        boolean inTrash = Boolean.TRUE.equals(trashed);
        LambdaQueryWrapper<AgentKnowledgeDocument> wrapper = new LambdaQueryWrapper<AgentKnowledgeDocument>()
            .eq(AgentKnowledgeDocument::getKnowledgeId, knowledgeId)
            .eq(AgentKnowledgeDocument::getTrashed, inTrash ? 1 : 0)
            .orderByDesc(AgentKnowledgeDocument::getId);
        Page<AgentKnowledgeDocumentVo> page = documentMapper.selectVoPage(pageQuery.build(), wrapper);
        return PageResult.build(page.getRecords(), page.getTotal());
    }

    @Override
    public Long upload(Long knowledgeId, MultipartFile file) {
        AgentKnowledge kb = knowledgeMapper.selectById(knowledgeId);
        if (kb == null) {
            throw new ServiceException("知识库不存在");
        }
        checkWsWritable(kb.getWorkspaceId());
        if (!"0".equals(kb.getStatus())) {
            throw new ServiceException("知识库已停用,无法上传文档");
        }
        if (file == null || file.isEmpty()) {
            throw new ServiceException("上传文件不能为空");
        }
        String originalName = StringUtils.defaultString(file.getOriginalFilename(), "unnamed");
        int dot = originalName.lastIndexOf('.');
        if (dot < 0 || dot == originalName.length() - 1) {
            throw new ServiceException("文件缺少扩展名,支持 " + String.join("/", DocumentParserFactory.supportedExtensions()));
        }
        String ext = originalName.substring(dot + 1).toLowerCase();
        if (!DocumentParserFactory.supportedExtensions().contains(ext)) {
            throw new ServiceException("不支持的文档格式:" + ext + ",支持 "
                + String.join("/", DocumentParserFactory.supportedExtensions()));
        }
        long maxBytes = properties.getMaxFileSizeMb() * 1024L * 1024L;
        if (file.getSize() > maxBytes) {
            throw new ServiceException("文件大小超过上限(" + properties.getMaxFileSizeMb() + "MB)");
        }
        try {
            byte[] bytes = file.getBytes();
            // 安全闸门:内容与扩展名必须一致(防改名的可执行文件),OOXML 还要过压缩包安全检查
            DocumentParseGuard.verifyMagic(ext, bytes);
            if (DocumentParseGuard.needsZipCheck(ext)) {
                DocumentParseGuard.verifyZipSafety(bytes);
            }
            // 按内容 MD5 去重:同一知识库内重复上传同一文件只会产生重复分块,直接拒绝
            String fileMd5 = DigestUtils.md5DigestAsHex(bytes);
            AgentKnowledgeDocument dup = documentMapper.selectOne(new LambdaQueryWrapper<AgentKnowledgeDocument>()
                .eq(AgentKnowledgeDocument::getKnowledgeId, knowledgeId)
                .eq(AgentKnowledgeDocument::getFileMd5, fileMd5)
                .eq(AgentKnowledgeDocument::getTrashed, 0)
                .last("limit 1"));
            if (dup != null) {
                throw new ServiceException("文档内容与已上传的「" + dup.getFileName() + "」完全重复,无需重复上传");
            }
            Path dir = Path.of(properties.getFileDir(), String.valueOf(knowledgeId));
            Files.createDirectories(dir);
            Path target = dir.resolve(UUID.randomUUID() + "." + ext);
            Files.write(target, bytes);

            AgentKnowledgeDocument doc = new AgentKnowledgeDocument();
            doc.setKnowledgeId(knowledgeId);
            doc.setFileName(originalName.length() > 200 ? originalName.substring(originalName.length() - 200) : originalName);
            doc.setFileExt(ext);
            doc.setFileSize(file.getSize());
            doc.setFileMd5(fileMd5);
            doc.setFilePath(target.toString());
            doc.setStatus("0");
            doc.setChunkCount(0);
            // R5-2:库级审核开关打开时新文档落待审,审核通过才参与检索
            doc.setPublishStatus(Integer.valueOf(1).equals(kb.getReviewRequired())
                ? "PENDING_REVIEW" : "PUBLISHED");
            doc.setVisibility("INHERIT");
            doc.setTrashed(0);
            documentMapper.insert(doc);
            refreshDocCount(knowledgeId);
            docProcessor.processAsync(doc.getId());
            auditLogHelper.record(null, "kb.doc.upload", "knowledge_document", String.valueOf(doc.getId()),
                "上传文档[" + doc.getFileName() + "]至知识库[" + kb.getName() + "]",
                null,
                Map.of("fileName", doc.getFileName(), "fileSize", doc.getFileSize(),
                    "knowledgeId", knowledgeId, "knowledgeName", kb.getName()),
                "0", "WARN");
            return doc.getId();
        } catch (ServiceException e) {
            throw e;
        } catch (Exception e) {
            throw new ServiceException("文件保存失败:" + e.getMessage());
        }
    }

    @Override
    public Boolean retry(Long id) {
        AgentKnowledgeDocument doc = documentMapper.selectById(id);
        if (doc == null) {
            throw new ServiceException("文档不存在");
        }
        AgentKnowledge kb = getKbOrThrow(doc.getKnowledgeId());
        checkWsWritable(kb.getWorkspaceId());
        if (!"2".equals(doc.getStatus())) {
            throw new ServiceException("仅失败的文档支持重试");
        }
        AgentKnowledgeDocument update = new AgentKnowledgeDocument();
        update.setId(id);
        update.setStatus("0");
        update.setFailReason(null);
        documentMapper.updateById(update);
        docProcessor.processAsync(id);
        return true;
    }

    @Transactional(rollbackFor = Exception.class)
    @Override
    public Boolean deleteByIds(List<Long> ids) {
        List<AgentKnowledgeDocument> docs = documentMapper.selectByIds(ids);
        if (docs.isEmpty()) {
            return false;
        }
        for (AgentKnowledgeDocument doc : docs) {
            AgentKnowledge kb = getKbOrThrow(doc.getKnowledgeId());
            checkWsWritable(kb.getWorkspaceId());
            // R5-2:软删除入回收站——只改数据库行,不碰向量库(检索由治理谓词排除),
            // 保留期内可恢复,超期由 DocTrashCleanupService 物理清理
            documentMapper.update(null, new LambdaUpdateWrapper<AgentKnowledgeDocument>()
                .eq(AgentKnowledgeDocument::getId, doc.getId())
                .set(AgentKnowledgeDocument::getTrashed, 1)
                .set(AgentKnowledgeDocument::getTrashedAt, new java.util.Date()));
            refreshDocCount(doc.getKnowledgeId());
            auditLogHelper.record(kb.getWorkspaceId(), "kb.doc.trash", "knowledge_document", String.valueOf(doc.getId()),
                "移入回收站[" + doc.getFileName() + "]",
                (Object) Map.of("fileName", doc.getFileName(), "fileSize", doc.getFileSize(),
                    "knowledgeId", doc.getKnowledgeId()),
                null, "0", "RISK");
        }
        return true;
    }

    @Transactional(rollbackFor = Exception.class)
    @Override
    public Boolean restore(List<Long> ids) {
        List<AgentKnowledgeDocument> docs = documentMapper.selectByIds(ids);
        if (docs.isEmpty()) {
            return false;
        }
        for (AgentKnowledgeDocument doc : docs) {
            AgentKnowledge kb = getKbOrThrow(doc.getKnowledgeId());
            checkWsWritable(kb.getWorkspaceId());
            documentMapper.update(null, new LambdaUpdateWrapper<AgentKnowledgeDocument>()
                .eq(AgentKnowledgeDocument::getId, doc.getId())
                .set(AgentKnowledgeDocument::getTrashed, 0)
                .set(AgentKnowledgeDocument::getTrashedAt, null));
            refreshDocCount(doc.getKnowledgeId());
            auditLogHelper.record(kb.getWorkspaceId(), "kb.doc.restore", "knowledge_document", String.valueOf(doc.getId()),
                "从回收站恢复[" + doc.getFileName() + "]", null,
                Map.of("fileName", doc.getFileName(), "knowledgeId", doc.getKnowledgeId()), "0", "WARN");
        }
        return true;
    }

    @Transactional(rollbackFor = Exception.class)
    @Override
    public Boolean updateGovernance(Long id, com.forgeagent.rag.domain.bo.DocGovernanceBo bo) {
        AgentKnowledgeDocument doc = documentMapper.selectById(id);
        if (doc == null) {
            throw new ServiceException("文档不存在");
        }
        AgentKnowledge kb = getKbOrThrow(doc.getKnowledgeId());
        checkWsWritable(kb.getWorkspaceId());
        String visibility = StringUtils.isBlank(bo.getVisibility()) ? null : bo.getVisibility().toUpperCase();
        if (visibility != null && !List.of("INHERIT", "RESTRICTED").contains(visibility)) {
            throw new ServiceException("可见性只支持 INHERIT / RESTRICTED");
        }
        String publishStatus = StringUtils.isBlank(bo.getPublishStatus()) ? null : bo.getPublishStatus().toUpperCase();
        if (publishStatus != null && !List.of("DRAFT", "PENDING_REVIEW", "PUBLISHED", "REJECTED").contains(publishStatus)) {
            throw new ServiceException("发布态只支持 DRAFT / PENDING_REVIEW / PUBLISHED / REJECTED");
        }
        if (bo.getEffectiveAt() != null && bo.getExpiresAt() != null
            && !bo.getExpiresAt().after(bo.getEffectiveAt())) {
            throw new ServiceException("过期时间必须晚于生效时间");
        }
        LambdaUpdateWrapper<AgentKnowledgeDocument> update = new LambdaUpdateWrapper<AgentKnowledgeDocument>()
            .eq(AgentKnowledgeDocument::getId, id)
            .set(visibility != null, AgentKnowledgeDocument::getVisibility, visibility)
            .set(publishStatus != null, AgentKnowledgeDocument::getPublishStatus, publishStatus);
        if (Boolean.TRUE.equals(bo.getClearTimeWindow())) {
            update.set(AgentKnowledgeDocument::getEffectiveAt, null)
                .set(AgentKnowledgeDocument::getExpiresAt, null);
        } else {
            update.set(bo.getEffectiveAt() != null, AgentKnowledgeDocument::getEffectiveAt, bo.getEffectiveAt())
                .set(bo.getExpiresAt() != null, AgentKnowledgeDocument::getExpiresAt, bo.getExpiresAt());
        }
        documentMapper.update(null, update);
        // ACL 行全量覆盖(RESTRICTED 才有意义;改回 INHERIT 时清空授权)
        if (visibility != null) {
            aclMapper.delete(new LambdaQueryWrapper<com.forgeagent.rag.domain.AgentKbDocAcl>()
                .eq(com.forgeagent.rag.domain.AgentKbDocAcl::getDocumentId, id));
            if ("RESTRICTED".equals(visibility) && bo.getRoleIds() != null) {
                for (Long roleId : bo.getRoleIds().stream().filter(java.util.Objects::nonNull).distinct().toList()) {
                    com.forgeagent.rag.domain.AgentKbDocAcl acl = new com.forgeagent.rag.domain.AgentKbDocAcl();
                    acl.setDocumentId(id);
                    acl.setRoleId(roleId);
                    acl.setCreateTime(new java.util.Date());
                    aclMapper.insert(acl);
                }
            }
        }
        auditLogHelper.record(kb.getWorkspaceId(), "kb.doc.governance", "knowledge_document", String.valueOf(id),
            "调整文档治理[" + doc.getFileName() + "]",
            (Object) Map.of("visibility", String.valueOf(visibility), "publishStatus", String.valueOf(publishStatus),
                "roleIds", bo.getRoleIds() == null ? List.of() : bo.getRoleIds()),
            null, "0", "WARN");
        return true;
    }

    @Override
    public Boolean review(Long id, Boolean approve, String reason) {
        AgentKnowledgeDocument doc = documentMapper.selectById(id);
        if (doc == null) {
            throw new ServiceException("文档不存在");
        }
        if (!"PENDING_REVIEW".equals(doc.getPublishStatus())) {
            throw new ServiceException("仅待审文档可审核");
        }
        com.forgeagent.rag.domain.bo.DocGovernanceBo bo = new com.forgeagent.rag.domain.bo.DocGovernanceBo();
        bo.setPublishStatus(Boolean.TRUE.equals(approve) ? "PUBLISHED" : "REJECTED");
        Boolean ok = updateGovernance(id, bo);
        // 驳回原因写进处理提示,前端文档列表直接可见
        if (!Boolean.TRUE.equals(approve) && StringUtils.isNotBlank(reason)) {
            documentMapper.update(null, new LambdaUpdateWrapper<AgentKnowledgeDocument>()
                .eq(AgentKnowledgeDocument::getId, id)
                .set(AgentKnowledgeDocument::getNotice, "审核驳回:" + StringUtils.substring(reason, 0, 300)));
        }
        return ok;
    }

    @Override
    public List<AgentKbChunkVo> listChunks(Long documentId) {
        AgentKnowledgeDocument doc = documentMapper.selectById(documentId);
        if (doc == null) {
            throw new ServiceException("文档不存在");
        }
        AgentKnowledge kb = getKbOrThrow(doc.getKnowledgeId());
        checkWsReadable(kb.getWorkspaceId());
        return chunkMapper.selectVoList(new LambdaQueryWrapper<AgentKbChunk>()
            .eq(AgentKbChunk::getDocumentId, documentId)
            .orderByAsc(AgentKbChunk::getChunkIndex));
    }

    @Transactional(rollbackFor = Exception.class)
    @Override
    public Boolean updateChunkContent(KnowledgeChunkEditBo bo) {
        AgentKbChunk chunk = chunkMapper.selectById(bo.getId());
        if (chunk == null) {
            throw new ServiceException("分块不存在");
        }
        AgentKnowledge kb = knowledgeMapper.selectById(chunk.getKnowledgeId());
        if (kb == null) {
            throw new ServiceException("所属知识库不存在");
        }
        checkWsWritable(kb.getWorkspaceId());
        String oldContent = chunk.getContent();
        // 顺序:算向量 → 落库 → 写向量库。
        // 反过来(先写向量后落库)一旦落库失败,就会留下"文本是旧的、向量是新的"的永久不一致,
        // 且没有任何机制能发现;这里失败直接抛出让事务回滚,两者一起回到旧状态
        List<float[]> vectors = embeddingFactory.forKnowledge(kb).embed(List.of(bo.getContent()));
        AgentKbChunk update = new AgentKbChunk();
        update.setId(chunk.getId());
        update.setContent(bo.getContent());
        update.setTokenCount(TokenEstimator.estimate(bo.getContent()));
        if (chunkMapper.updateById(update) <= 0) {
            return false;
        }
        try {
            vectorStore.upsert(chunk.getKnowledgeId(), chunk.getDocumentId(), List.of(chunk.getId()), vectors);
        } catch (Exception e) {
            throw new ServiceException("分块向量写入失败,内容已回滚未修改,请重试:" + e.getMessage());
        }
        auditLogHelper.record(kb.getWorkspaceId(), "kb.chunk.edit", "knowledge_chunk", String.valueOf(chunk.getId()),
            "编辑知识库[" + kb.getName() + "]分块#" + chunk.getChunkIndex(),
            Map.of("content", ChatTexts.truncate(oldContent, 200)),
            Map.of("content", ChatTexts.truncate(bo.getContent(), 200)),
            "0", "WARN");
        return true;
    }
    @Override
    public Boolean changeChunkEnabled(Long chunkId, Boolean enabled) {
        AgentKbChunk chunk = chunkMapper.selectById(chunkId);
        if (chunk == null) {
            throw new ServiceException("分块不存在");
        }
        AgentKnowledge kb = knowledgeMapper.selectById(chunk.getKnowledgeId());
        if (kb == null) {
            throw new ServiceException("所属知识库不存在");
        }
        checkWsWritable(kb.getWorkspaceId());
        AgentKbChunk update = new AgentKbChunk();
        update.setId(chunkId);
        update.setEnabled(Boolean.TRUE.equals(enabled) ? 1 : 0);
        boolean ok = chunkMapper.updateById(update) > 0;
        if (ok) {
            String action = Boolean.TRUE.equals(enabled) ? "启用" : "禁用";
            auditLogHelper.record(kb.getWorkspaceId(), "kb.chunk.toggle", "knowledge_chunk", String.valueOf(chunkId),
                action + "知识库[" + kb.getName() + "]分块#" + chunk.getChunkIndex(),
                Map.of("enabled", String.valueOf(chunk.getEnabled())),
                Map.of("enabled", String.valueOf(update.getEnabled())),
                "0", "WARN");
        }
        return ok;
    }


    @Override
    public AgentKnowledgeDocumentVo queryById(Long id) {
        AgentKnowledgeDocument doc = documentMapper.selectById(id);
        if (doc == null) {
            throw new ServiceException("文档不存在");
        }
        AgentKnowledge kb = getKbOrThrow(doc.getKnowledgeId());
        checkWsReadable(kb.getWorkspaceId());
        return MapstructUtils.convert(doc, AgentKnowledgeDocumentVo.class);
    }

    @Override
    public Path resolveFilePath(Long id) {
        AgentKnowledgeDocument doc = documentMapper.selectById(id);
        if (doc == null) {
            throw new ServiceException("文档不存在");
        }
        AgentKnowledge kb = getKbOrThrow(doc.getKnowledgeId());
        checkWsReadable(kb.getWorkspaceId());
        return Path.of(doc.getFilePath());
    }

    private AgentKnowledge getKbOrThrow(Long knowledgeId) {
        AgentKnowledge kb = knowledgeMapper.selectById(knowledgeId);
        if (kb == null) {
            throw new ServiceException("知识库不存在");
        }
        return kb;
    }

    private void checkWsReadable(Long workspaceId) {
        if (LoginHelper.isSuperAdmin()) {
            return;
        }
        if (workspaceId == null) {
            return;
        }
        permissionHelper.checkWsMember(workspaceId);
    }

    private void checkWsWritable(Long workspaceId) {
        if (LoginHelper.isSuperAdmin()) {
            return;
        }
        if (workspaceId == null) {
            throw new ServiceException("仅超级管理员可管理全局知识库");
        }
        permissionHelper.checkWsDeveloper(workspaceId);
    }

    private void refreshDocCount(Long knowledgeId) {
        // 回收站中的文档不计入知识库文档数
        long count = documentMapper.selectCount(new LambdaQueryWrapper<AgentKnowledgeDocument>()
            .eq(AgentKnowledgeDocument::getKnowledgeId, knowledgeId)
            .eq(AgentKnowledgeDocument::getTrashed, 0));
        AgentKnowledge update = new AgentKnowledge();
        update.setId(knowledgeId);
        update.setDocCount((int) count);
        knowledgeMapper.updateById(update);
    }
}
