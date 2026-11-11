package com.forgeagent.agent.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import lombok.RequiredArgsConstructor;
import com.forgeagent.agentcore.domain.AgentChatAttachment;
import com.forgeagent.agentcore.domain.vo.ChatAttachmentVo;
import com.forgeagent.agent.service.AgentChatAttachmentService;
import com.forgeagent.agentcore.service.WorkspacePermissionHelper;
import com.forgeagent.common.core.domain.R;
import com.forgeagent.common.redis.annotation.RateLimiter;
import com.forgeagent.common.web.core.BaseController;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 会话图片附件 Controller
 *
 * @author ForgeAgent
 */
@Validated
@RequiredArgsConstructor
@RestController
@RequestMapping("/agent/chat/attachment")
public class AgentChatAttachmentController extends BaseController {

    private final AgentChatAttachmentService attachmentService;
    private final WorkspacePermissionHelper workspacePermissionHelper;

    /**
     * 上传附件(图片或文档)
     */
    @SaCheckPermission("agent:chat:send")
    @RateLimiter(key = "#{T(com.forgeagent.common.satoken.utils.LoginHelper).getUserId()}", time = 3600, count = 10, message = "上传太频繁，请稍后再试")
    @PostMapping("/upload")
    public R<ChatAttachmentVo> upload(@RequestPart("file") MultipartFile file,
                                      @RequestParam(value = "agentId", required = false) Long agentId,
                                      @RequestParam(value = "sessionId", required = false) Long sessionId) {
        return R.ok(attachmentService.upload(file, agentId, sessionId));
    }

    /**
     * 查询附件解析状态(前端上传后轮询)
     */
    @SaCheckPermission("agent:chat:send")
    @GetMapping("/{id}/meta")
    public R<ChatAttachmentVo> meta(@PathVariable Long id) {
        ChatAttachmentVo vo = attachmentService.getVo(id);
        AgentChatAttachment att = attachmentService.getEntity(id);
        if (att.getWorkspaceId() != null) {
            workspacePermissionHelper.checkWsMember(att.getWorkspaceId());
        }
        return R.ok(vo);
    }

    /**
     * 读取附件(按空间做权限隔离)
     */
    @SaCheckPermission("agent:chat:send")
    @GetMapping("/{id}")
    public ResponseEntity<byte[]> read(@PathVariable Long id) {
        AgentChatAttachment att = attachmentService.getEntity(id);
        if (att.getWorkspaceId() != null) {
            workspacePermissionHelper.checkWsMember(att.getWorkspaceId());
        }
        byte[] bytes = attachmentService.readBytes(att);
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_TYPE, att.getMediaType())
            .header(HttpHeaders.CACHE_CONTROL, "private, max-age=600")
            .body(bytes);
    }
}
