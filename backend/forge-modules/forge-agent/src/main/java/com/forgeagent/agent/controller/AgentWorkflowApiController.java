package com.forgeagent.agent.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import com.forgeagent.agent.service.IAgentWorkflowEndpointService;
import com.forgeagent.common.core.domain.R;
import com.forgeagent.common.core.exception.ServiceException;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 工作流对外调用入口(G2,免登录)
 *
 * <p>路径在 security.excludes 放行;鉴权走 Authorization: Bearer ak-...(空间 API 密钥),
 * IP 级预限流 + 端点级限流。请求体透传为工作流 start.* 变量。</p>
 *
 * @author ForgeAgent
 */
@RestController
@RequiredArgsConstructor
public class AgentWorkflowApiController {

    private final IAgentWorkflowEndpointService endpointService;

    /** 体检 R2:免登录入口请求体上限(字符) */
    private static final int MAX_BODY_CHARS = 256 * 1024;

    /**
     * 同步执行工作流并返回终态结果
     */
    @PostMapping("/workflow-api/{endpointCode}")
    public R<Map<String, Object>> invoke(@NotBlank(message = "端点编码不能为空")
                                         @PathVariable String endpointCode,
                                         @RequestHeader(value = "Authorization", required = false) String authorization,
                                         @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
                                         @RequestBody(required = false) String body,
                                         HttpServletRequest request) {
        if (body != null && body.length() > MAX_BODY_CHARS) {
            return R.fail("请求体过大,上限 " + (MAX_BODY_CHARS / 1024) + "KB");
        }
        try {
            return R.ok(endpointService.invoke(endpointCode, authorization, body, idempotencyKey, clientIp(request)));
        } catch (ServiceException e) {
            // 免登录端点:业务错误以统一 R 包装返回,不触发全局登录态处理
            return R.fail(e.getMessage());
        }
    }

    /**
     * 调用方 IP:优先 X-Forwarded-For 首段(经反代部署时),回退 remoteAddr。
     * 该值仅用于限流计数,伪造的后果是攻击者自耗 IP 桶,不影响鉴权与数据隔离。
     */
    private String clientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            return xff.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
