package com.forgeagent.agent.service.impl;

import cn.hutool.core.util.IdUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import com.forgeagent.agent.config.AgentChatProperties;
import com.forgeagent.agentcore.domain.AgentChatAttachment;
import com.forgeagent.agentcore.domain.AgentInfo;
import com.forgeagent.rag.domain.AgentKnowledge;
import com.forgeagent.rag.domain.AgentKnowledgeDocument;
import com.forgeagent.agentcore.domain.vo.ChatAttachmentVo;
import com.forgeagent.agentcore.mapper.AgentChatAttachmentMapper;
import com.forgeagent.agentcore.mapper.AgentInfoMapper;
import com.forgeagent.rag.mapper.AgentKnowledgeDocumentMapper;
import com.forgeagent.rag.mapper.AgentKnowledgeMapper;
import com.forgeagent.agent.service.AgentChatAttachmentService;
import com.forgeagent.rag.service.IAgentKnowledgeService;
import com.forgeagent.rag.service.KnowledgeDocProcessor;
import com.forgeagent.common.core.exception.ServiceException;
import com.forgeagent.common.core.utils.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * 会话附件 Service 实现(图片 + 文档)
 *
 * @author ForgeAgent
 */
@Slf4j
@RequiredArgsConstructor
@Service
public class AgentChatAttachmentServiceImpl implements AgentChatAttachmentService {

    /** 附件可用状态 */
    private static final String STATUS_NORMAL = "0";
    /** 图片类型 */
    private static final String KIND_IMAGE = "image";
    /** 文件类型 */
    private static final String KIND_FILE = "file";

    private static final Map<String, String> FILE_MEDIA_TYPES = Map.of(
        "pdf", "application/pdf",
        "docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "csv", "text/csv",
        "txt", "text/plain",
        "md", "text/markdown",
        "json", "application/json",
        "html", "text/html"
    );

    private final AgentChatAttachmentMapper attachmentMapper;
    private final AgentInfoMapper agentMapper;
    private final AgentKnowledgeMapper knowledgeMapper;
    private final AgentKnowledgeDocumentMapper documentMapper;
    private final KnowledgeDocProcessor docProcessor;
    private final IAgentKnowledgeService knowledgeService;
    private final AgentChatProperties properties;

    @Override
    public ChatAttachmentVo upload(MultipartFile file, Long agentId, Long sessionId) {
        if (file == null || file.isEmpty()) {
            throw new ServiceException("上传附件不能为空");
        }
        String originalName = StringUtils.defaultIfBlank(file.getOriginalFilename(), "unnamed");
        int dot = originalName.lastIndexOf('.');
        if (dot < 0 || dot == originalName.length() - 1) {
            throw new ServiceException("文件缺少扩展名,支持图片(" + properties.getImageAllowedExt()
                + ")或文档(" + properties.getFileAllowedExt() + ")");
        }
        String ext = originalName.substring(dot + 1).toLowerCase();
        if (allowedImageExtList().contains(ext)) {
            return uploadImage(file, originalName, ext, agentId, sessionId);
        }
        if (allowedFileExtList().contains(ext)) {
            return uploadFile(file, originalName, ext, agentId, sessionId);
        }
        throw new ServiceException("不支持的附件格式:" + ext + ",支持图片(" + properties.getImageAllowedExt()
            + ")或文档(" + properties.getFileAllowedExt() + ")");
    }

    private ChatAttachmentVo uploadImage(MultipartFile file, String originalName, String ext,
                                         Long agentId, Long sessionId) {
        long maxBytes = properties.getImageMaxSizeMb() * 1024L * 1024L;
        if (file.getSize() > maxBytes) {
            throw new ServiceException("单张图片不能超过 " + properties.getImageMaxSizeMb() + "MB");
        }
        BufferedImage image;
        try {
            image = ImageIO.read(file.getInputStream());
        } catch (Exception e) {
            throw new ServiceException("图片解析失败,请更换图片");
        }
        if (image == null) {
            throw new ServiceException("图片解析失败,请更换图片");
        }

        String storedName = IdUtil.fastSimpleUUID() + "." + ext;
        try {
            Path dir = Paths.get(properties.getImageDir());
            Files.createDirectories(dir);
            file.transferTo(dir.resolve(storedName));
        } catch (Exception e) {
            throw new ServiceException("图片保存失败:" + e.getMessage());
        }

        AgentChatAttachment att = new AgentChatAttachment();
        att.setStoredName(storedName);
        att.setOriginalName(originalName);
        att.setMediaType(switch (ext) {
            case "png" -> "image/png";
            case "gif" -> "image/gif";
            case "webp" -> "image/webp";
            default -> "image/jpeg";
        });
        att.setKind(KIND_IMAGE);
        att.setFileExt(ext);
        att.setParseStatus("1");
        att.setChunkCount(0);
        att.setTokenCount(0);
        att.setPageCount(0);
        att.setFileSize(file.getSize());
        att.setSessionId(sessionId);
        att.setStatus(STATUS_NORMAL);
        att.setWorkspaceId(resolveWorkspaceId(agentId));
        try {
            att.setThumb(buildThumb(image));
        } catch (Exception e) {
            log.warn("[会话附件] 缩略图生成失败: {}", e.getMessage());
        }
        attachmentMapper.insert(att);
        return toVo(att);
    }

    private ChatAttachmentVo uploadFile(MultipartFile file, String originalName, String ext,
                                       Long agentId, Long sessionId) {
        long maxBytes = properties.getFileMaxSizeMb() * 1024L * 1024L;
        if (file.getSize() > maxBytes) {
            throw new ServiceException("单个文件不能超过 " + properties.getFileMaxSizeMb() + "MB,请拆分后上传");
        }
        String fileMd5;
        String storedName = IdUtil.fastSimpleUUID() + "." + ext;
        try {
            fileMd5 = org.springframework.util.DigestUtils.md5DigestAsHex(file.getInputStream());
            Path dir = Paths.get(properties.getFileDir());
            Files.createDirectories(dir);
            file.transferTo(dir.resolve(storedName));
        } catch (Exception e) {
            throw new ServiceException("文件保存失败:" + e.getMessage());
        }

        // 为每个文件创建会话临时索引,复用知识库的解析/分块/向量/混合检索链路
        AgentKnowledge kb = new AgentKnowledge();
        kb.setName("会话附件-" + IdUtil.fastSimpleUUID());
        kb.setDescription("会话上传文件临时索引");
        kb.setKbScope("temp");
        kb.setSessionId(sessionId);
        kb.setStatus("0");
        kb.setEmbeddingModel("local-hash");
        kb.setVectorBackend("milvus");
        kb.setChunkSize(500);
        kb.setChunkOverlap(50);
        kb.setDocCount(1);
        knowledgeMapper.insert(kb);

        AgentKnowledgeDocument doc = new AgentKnowledgeDocument();
        doc.setKnowledgeId(kb.getId());
        doc.setFileName(originalName.length() > 200 ? originalName.substring(originalName.length() - 200) : originalName);
        doc.setFileExt(ext);
        doc.setFileSize(file.getSize());
        doc.setFileMd5(fileMd5);
        doc.setFilePath(Paths.get(properties.getFileDir(), storedName).toString());
        doc.setStatus("0");
        doc.setChunkCount(0);
        documentMapper.insert(doc);

        AgentChatAttachment att = new AgentChatAttachment();
        att.setStoredName(storedName);
        att.setOriginalName(originalName);
        att.setMediaType(FILE_MEDIA_TYPES.getOrDefault(ext, "application/octet-stream"));
        att.setKind(KIND_FILE);
        att.setFileExt(ext);
        att.setFileMd5(fileMd5);
        att.setParseStatus("0");
        att.setChunkCount(0);
        att.setTokenCount(0);
        att.setPageCount(0);
        att.setFileSize(file.getSize());
        att.setSessionId(sessionId);
        att.setStatus(STATUS_NORMAL);
        att.setWorkspaceId(resolveWorkspaceId(agentId));
        att.setKnowledgeId(kb.getId());
        try {
            attachmentMapper.insert(att);
        } catch (Exception e) {
            // 附件落库失败时回滚已创建的临时索引与文件,避免留下孤儿数据
            try {
                knowledgeService.deleteByIds(List.of(kb.getId()));
            } catch (Exception ex) {
                log.warn("[会话附件] 清理临时索引 {} 失败: {}", kb.getId(), ex.getMessage());
            }
            throw new ServiceException("附件保存失败:" + e.getMessage());
        }

        docProcessor.processAsync(doc.getId());
        return toVo(att);
    }

    @Override
    public ChatAttachmentVo getVo(Long id) {
        return toVo(getEntity(id));
    }

    @Override
    public AgentChatAttachment getEntity(Long id) {
        if (id == null) {
            throw new ServiceException("附件不存在或已被清理");
        }
        AgentChatAttachment att = attachmentMapper.selectById(id);
        if (att == null || !STATUS_NORMAL.equals(att.getStatus())) {
            throw new ServiceException("附件不存在或已被清理");
        }
        return att;
    }

    @Override
    public List<AgentChatAttachment> listByIds(Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        return attachmentMapper.selectList(Wrappers.<AgentChatAttachment>lambdaQuery()
            .in(AgentChatAttachment::getId, ids)
            .eq(AgentChatAttachment::getStatus, STATUS_NORMAL));
    }

    @Override
    public byte[] readBytes(AgentChatAttachment att) {
        String dir = KIND_FILE.equals(att.getKind()) ? properties.getFileDir() : properties.getImageDir();
        Path path = Paths.get(dir, att.getStoredName());
        if (!Files.exists(path)) {
            throw new ServiceException("附件不存在或已被清理");
        }
        try {
            return Files.readAllBytes(path);
        } catch (Exception e) {
            throw new ServiceException("附件读取失败:" + e.getMessage());
        }
    }

    @Override
    public void bindMessages(List<AgentChatAttachment> atts, Long sessionId, Long messageId) {
        if (atts == null || atts.isEmpty()) {
            return;
        }
        List<Long> ids = atts.stream().map(AgentChatAttachment::getId).toList();
        attachmentMapper.update(null, Wrappers.<AgentChatAttachment>lambdaUpdate()
            .set(sessionId != null, AgentChatAttachment::getSessionId, sessionId)
            .set(messageId != null, AgentChatAttachment::getMessageId, messageId)
            .in(AgentChatAttachment::getId, ids));
        if (sessionId != null) {
            List<Long> kbIds = atts.stream().map(AgentChatAttachment::getKnowledgeId)
                .filter(java.util.Objects::nonNull).toList();
            if (!kbIds.isEmpty()) {
                knowledgeMapper.update(null, Wrappers.<AgentKnowledge>lambdaUpdate()
                    .set(AgentKnowledge::getSessionId, sessionId)
                    .in(AgentKnowledge::getId, kbIds));
            }
        }
    }

    @Override
    public void cleanupBySession(Long sessionId) {
        if (sessionId == null) {
            return;
        }
        List<AgentChatAttachment> atts = attachmentMapper.selectList(Wrappers.<AgentChatAttachment>lambdaQuery()
            .eq(AgentChatAttachment::getSessionId, sessionId));
        if (atts.isEmpty()) {
            return;
        }
        List<Long> kbIds = new ArrayList<>();
        for (AgentChatAttachment att : atts) {
            if (KIND_FILE.equals(att.getKind())) {
                if (att.getKnowledgeId() != null) {
                    kbIds.add(att.getKnowledgeId());
                }
            } else {
                try {
                    Files.deleteIfExists(Paths.get(properties.getImageDir(), att.getStoredName()));
                } catch (Exception e) {
                    log.warn("[会话附件] 删除图片 {} 失败: {}", att.getStoredName(), e.getMessage());
                }
            }
        }
        attachmentMapper.delete(Wrappers.<AgentChatAttachment>lambdaQuery()
            .eq(AgentChatAttachment::getSessionId, sessionId));
        if (!kbIds.isEmpty()) {
            try {
                knowledgeService.deleteByIds(kbIds);
            } catch (Exception e) {
                log.warn("[会话附件] 清理临时索引失败: {}", e.getMessage());
            }
        }
    }

    /**
     * 取 Agent 所属工作空间ID(取不到返回 null)
     */
    private Long resolveWorkspaceId(Long agentId) {
        if (agentId == null) {
            return null;
        }
        AgentInfo agent = agentMapper.selectById(agentId);
        return agent == null ? null : agent.getWorkspaceId();
    }

    private ChatAttachmentVo toVo(AgentChatAttachment att) {
        ChatAttachmentVo vo = new ChatAttachmentVo();
        vo.setId(att.getId());
        vo.setName(att.getOriginalName());
        vo.setMediaType(att.getMediaType());
        vo.setKind(att.getKind());
        vo.setFileExt(att.getFileExt());
        vo.setParseStatus(att.getParseStatus());
        vo.setChunkCount(att.getChunkCount());
        vo.setTokenCount(att.getTokenCount());
        vo.setSummary(att.getSummary());
        vo.setFailReason(att.getFailReason());
        vo.setPageCount(att.getPageCount());
        vo.setSize(att.getFileSize());
        vo.setThumb(att.getThumb());
        return vo;
    }

    /**
     * 生成白底等比缩略图(data URI)
     */
    private String buildThumb(BufferedImage image) throws Exception {
        int longSide = Math.max(image.getWidth(), image.getHeight());
        int maxSide = properties.getImageThumbSize();
        double scale = longSide <= maxSide ? 1.0 : (double) maxSide / longSide;
        int width = Math.max(1, (int) Math.round(image.getWidth() * scale));
        int height = Math.max(1, (int) Math.round(image.getHeight() * scale));
        BufferedImage thumb = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = thumb.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, width, height);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(image, 0, 0, width, height, null);
        g.dispose();
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(thumb, "jpg", baos);
        return "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(baos.toByteArray());
    }

    private List<String> allowedImageExtList() {
        return splitExt(properties.getImageAllowedExt());
    }

    private List<String> allowedFileExtList() {
        return splitExt(properties.getFileAllowedExt());
    }

    private List<String> splitExt(String raw) {
        return List.of(raw.split(",")).stream()
            .map(s -> s.trim().toLowerCase())
            .filter(s -> !s.isEmpty())
            .toList();
    }
}
