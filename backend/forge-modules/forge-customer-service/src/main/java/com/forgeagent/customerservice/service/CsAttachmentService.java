package com.forgeagent.customerservice.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.forgeagent.agent.service.AgentChatAttachmentService;
import com.forgeagent.agentcore.domain.AgentChatAttachment;
import com.forgeagent.agentcore.domain.vo.ChatAttachmentVo;
import com.forgeagent.agentcore.mapper.AgentChatAttachmentMapper;
import com.forgeagent.common.core.exception.ServiceException;
import com.forgeagent.customerservice.config.ConditionalOnCustomerServiceEnabled;
import com.forgeagent.customerservice.domain.CsChannel;
import com.forgeagent.customerservice.domain.CsConversation;
import com.forgeagent.customerservice.event.CsBusinessFailureEvent;
import com.forgeagent.customerservice.mapper.CsConversationMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 访客附件(P2 收尾):上传、归属校验、坐席查看。
 *
 * <p><b>版本约定:附件不进模型上下文。</b>访客上传的内容直接塞进模型等于开放 prompt injection 面,
 * 还要补 OCR / 文档解析与上下文预算;而座席真正需要的是「能看到客户发了什么」。
 * 所以这里只做落盘 + 关联会话,AI 回复时不带附件——纯附件消息直接转人工。
 * 后续若要进模型,入口在 {@link #upload} 的返回值(已有 parseStatus / tokenCount / summary 字段)。</p>
 *
 * <p>安全口径:类型按扩展名白名单(图片 / PDF / 文本 / Office)、单文件 10MB、
 * 归属用「空间 + 会话的 agent_session_id」双重校验,避免 A 会话引用 B 会话的附件。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnCustomerServiceEnabled
public class CsAttachmentService {

    /** 允许的扩展名(小写)。刻意不收 exe/js/html 这类可执行或可注入脚本的格式。 */
    private static final Set<String> ALLOWED_EXT = Set.of(
        "jpg", "jpeg", "png", "gif", "webp", "bmp", "pdf", "txt", "csv", "docx", "xlsx");
    /** 单文件上限 10MB:客服场景的截图与单据远小于此,再大就该走人工渠道。 */
    private static final long MAX_SIZE = 10L * 1024 * 1024;

    private final AgentChatAttachmentService attachmentService;
    private final AgentChatAttachmentMapper attachmentMapper;
    private final CsConversationMapper conversationMapper;
    private final ApplicationEventPublisher eventPublisher;

    /** 访客上传:校验会话归属与文件类型后落盘。 */
    public ChatAttachmentVo upload(CsChannel channel, Long conversationId, Long visitorId, MultipartFile file) {
        CsConversation conv = requireVisitorConversation(channel, conversationId, visitorId);
        validate(file);
        try {
            ChatAttachmentVo vo = attachmentService.upload(file, channel.getAgentId(), conv.getAgentSessionId());
            log.info("[CS] 访客附件已上传 conversationId={} attachmentId={} name={}",
                conv.getId(), vo.getId(), vo.getName());
            return vo;
        } catch (Exception e) {
            // 附件失败要能补查:字节已经丢了,但至少让运营知道有客户传失败
            eventPublisher.publishEvent(new CsBusinessFailureEvent(channel.getWorkspaceId(),
                channel.getChannelCode(), "ATTACHMENT_UPLOAD", conv.getId(), conv.getTicketId(), e.getMessage()));
            throw new ServiceException("附件上传失败，请稍后重试或改用文字描述");
        }
    }

    /** 坐席查看:按会话列附件(不含缩略图,避免列表响应过大)。 */
    public List<Map<String, Object>> list(Long workspaceId, Long conversationId) {
        CsConversation conv = requireConversation(workspaceId, conversationId);
        List<AgentChatAttachment> rows = attachmentMapper.selectList(
            new LambdaQueryWrapper<AgentChatAttachment>()
                .eq(AgentChatAttachment::getSessionId, conv.getAgentSessionId())
                .eq(AgentChatAttachment::getStatus, "0")
                .orderByAsc(AgentChatAttachment::getId));
        List<Map<String, Object>> result = new ArrayList<>();
        for (AgentChatAttachment row : rows) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", row.getId());
            item.put("name", row.getOriginalName());
            item.put("mediaType", row.getMediaType());
            item.put("kind", row.getKind());
            item.put("fileExt", row.getFileExt());
            item.put("fileSize", row.getFileSize());
            item.put("parseStatus", row.getParseStatus());
            item.put("summary", row.getSummary());
            result.add(item);
        }
        return result;
    }

    /** 单张查看:带缩略图(图片)或解析摘要(文档)。 */
    public ChatAttachmentVo detail(Long workspaceId, Long conversationId, Long attachmentId) {
        CsConversation conv = requireConversation(workspaceId, conversationId);
        AgentChatAttachment row = attachmentMapper.selectById(attachmentId);
        if (row == null || !conv.getAgentSessionId().equals(row.getSessionId())) {
            throw new ServiceException("附件不存在或不属于该会话");
        }
        return attachmentService.getVo(attachmentId);
    }

    /**
     * 校验附件归属:消息里引用的每个附件都必须属于当前会话。
     *
     * <p>不校验就能拿别人的附件 id 挂到自己的消息上,坐席看到的会是另一个客户的单据。</p>
     */
    public void assertOwned(Long workspaceId, Long conversationId, List<Long> attachmentIds) {
        if (attachmentIds == null || attachmentIds.isEmpty()) {
            return;
        }
        CsConversation conv = requireConversation(workspaceId, conversationId);
        Long count = attachmentMapper.selectCount(new LambdaQueryWrapper<AgentChatAttachment>()
            .in(AgentChatAttachment::getId, attachmentIds)
            .eq(AgentChatAttachment::getSessionId, conv.getAgentSessionId())
            .eq(AgentChatAttachment::getStatus, "0"));
        if (count == null || count != attachmentIds.stream().distinct().count()) {
            throw new ServiceException("附件不属于当前会话");
        }
    }

    private CsConversation requireConversation(Long workspaceId, Long conversationId) {
        CsConversation conv = conversationMapper.selectById(conversationId);
        if (conv == null || !conv.getWorkspaceId().equals(workspaceId)) {
            throw new ServiceException("会话不存在或无权访问");
        }
        return conv;
    }

    /**
     * 访客侧会话校验:自己查而不是复用 CsVisitorService——
     * 那个服务要反向依赖本类做附件归属校验,注入回来就是构造器循环依赖。
     */
    private CsConversation requireVisitorConversation(CsChannel channel, Long conversationId, Long visitorId) {
        CsConversation conv = conversationMapper.selectById(conversationId);
        if (conv == null || !conv.getWorkspaceId().equals(channel.getWorkspaceId())
            || !conv.getVisitorId().equals(visitorId)) {
            throw new ServiceException("会话不存在或无权访问");
        }
        return conv;
    }

    private void validate(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new ServiceException("请选择要上传的文件");
        }
        if (file.getSize() > MAX_SIZE) {
            throw new ServiceException("单个文件不能超过 10MB");
        }
        String name = file.getOriginalFilename();
        int dot = name == null ? -1 : name.lastIndexOf('.');
        String ext = dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
        if (!ALLOWED_EXT.contains(ext)) {
            throw new ServiceException("不支持的文件类型：" + (ext.isEmpty() ? "未知" : ext));
        }
        // 扩展名可伪造,再用声明的 mediaType 兜一层:声明成可执行类型的一律拒
        String mediaType = file.getContentType();
        if (mediaType != null && (mediaType.contains("javascript") || mediaType.contains("html")
            || mediaType.contains("x-sh"))) {
            throw new ServiceException("不支持的文件类型");
        }
    }
}
