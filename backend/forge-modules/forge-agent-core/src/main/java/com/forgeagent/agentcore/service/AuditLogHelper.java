package com.forgeagent.agentcore.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import com.forgeagent.agentcore.domain.AgentAuditLog;
import com.forgeagent.agentcore.mapper.AgentAuditLogMapper;
import com.forgeagent.common.core.utils.ServletUtils;
import com.forgeagent.common.core.utils.StringUtils;
import com.forgeagent.common.satoken.utils.LoginHelper;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Date;
import java.util.UUID;

/**
 * 审计日志记录组件(FR-M6-03):关键操作只增不改落库,记录失败不影响主流程
 *
 * @author ForgeAgent
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AuditLogHelper {

    private final AgentAuditLogMapper auditLogMapper;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 记录一条审计事件(简易模式,无变更快照)
     *
     * @param workspaceId 所属空间(平台事件传 null)
     * @param eventType   事件类型
     * @param targetType  目标对象类型
     * @param targetId    目标对象ID
     * @param detail      事件详情描述
     */
    public void record(Long workspaceId, String eventType, String targetType, String targetId, String detail) {
        record(workspaceId, eventType, targetType, targetId, detail, null, null, "0", "INFO");
    }

    /**
     * 记录一条审计事件(带级别与结果)
     *
     * @param workspaceId 所属空间(平台事件传 null)
     * @param eventType   事件类型
     * @param targetType  目标对象类型
     * @param targetId    目标对象ID
     * @param detail      事件详情描述
     * @param result      操作结果(0成功 1失败)
     * @param severity    事件级别(INFO/WARN/RISK)
     */
    public void record(Long workspaceId, String eventType, String targetType, String targetId,
                       String detail, String result, String severity) {
        record(workspaceId, eventType, targetType, targetId, detail, null, null, result, severity);
    }

    /**
     * 记录一条审计事件(完整模式,支持变更快照)
     *
     * @param workspaceId 所属空间(平台事件传 null)
     * @param eventType   事件类型
     * @param targetType  目标对象类型
     * @param targetId    目标对象ID
     * @param detail      事件详情描述
     * @param oldValue    变更前快照对象(自动序列化 JSON)
     * @param newValue    变更后快照对象(自动序列化 JSON)
     * @param result      操作结果(0成功 1失败)
     * @param severity    事件级别(INFO/WARN/RISK)
     */
    public void record(Long workspaceId, String eventType, String targetType, String targetId,
                       String detail, Object oldValue, Object newValue, String result, String severity) {
        try {
            AgentAuditLog entity = new AgentAuditLog();
            entity.setWorkspaceId(workspaceId);
            entity.setEventType(eventType);
            entity.setTargetType(targetType == null ? "" : targetType);
            entity.setTargetId(targetId == null ? "" : String.valueOf(targetId));
            entity.setDetail(detail == null ? "" : truncate(detail, 500));
            entity.setSourceChannel(resolveSourceChannel());
            entity.setClientIp(resolveClientIp());
            entity.setUserAgent(resolveUserAgent());
            entity.setResult(StringUtils.isBlank(result) ? "0" : result);
            entity.setSeverity(StringUtils.isBlank(severity) ? "INFO" : severity);
            entity.setTraceId(resolveTraceId());
            entity.setOldValue(toJson(oldValue));
            entity.setNewValue(toJson(newValue));

            Long userId = null;
            String username = "system";
            try {
                userId = LoginHelper.getUserId();
                username = LoginHelper.getUsername();
            } catch (Exception ignored) {
                // 无登录上下文(如系统任务/Webhook)时按 system 记录
            }
            entity.setOperatorId(userId == null ? 0L : userId);
            entity.setOperatorName(username == null ? "system" : username);
            entity.setCreateTime(new Date());
            auditLogMapper.insert(entity);
        } catch (Exception e) {
            log.error("审计日志写入失败,已跳过: eventType={}, targetId={}, err={}", eventType, targetId, e.getMessage());
        }
    }

    private String toJson(Object obj) {
        if (obj == null) {
            return null;
        }
        if (obj instanceof String s) {
            return s;
        }
        try {
            String json = objectMapper.writeValueAsString(obj);
            return json.length() <= 10000 ? json : json.substring(0, 10000) + "...(已截断)";
        } catch (JsonProcessingException e) {
            return obj.toString();
        }
    }

    private String resolveClientIp() {
        try {
            HttpServletRequest req = getRequest();
            return req == null ? "" : ServletUtils.getClientIP(req);
        } catch (Exception e) {
            return "";
        }
    }

    private String resolveUserAgent() {
        try {
            HttpServletRequest req = getRequest();
            return req == null ? "" : truncate(req.getHeader("User-Agent"), 255);
        } catch (Exception e) {
            return "";
        }
    }

    private String resolveSourceChannel() {
        try {
            HttpServletRequest req = getRequest();
            if (req == null) {
                return "system";
            }
            // 仅 ak- 前缀的 API Key 调用记为 api_key, 普通 sa-token 会话(浏览器)记为 web
            String auth = req.getHeader("Authorization");
            if (StringUtils.isNotBlank(auth) && auth.toLowerCase().startsWith("bearer ak-")) {
                return "api_key";
            }
            return "web";
        } catch (Exception e) {
            return "web";
        }
    }

    private String resolveTraceId() {
        try {
            HttpServletRequest req = getRequest();
            if (req != null && StringUtils.isNotBlank(req.getHeader("X-Trace-Id"))) {
                return req.getHeader("X-Trace-Id");
            }
        } catch (Exception ignored) {
        }
        return UUID.randomUUID().toString().replace("-", "");
    }

    private HttpServletRequest getRequest() {
        try {
            ServletRequestAttributes attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            return attrs == null ? null : attrs.getRequest();
        } catch (Exception e) {
            return null;
        }
    }

    private String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max);
    }
}
