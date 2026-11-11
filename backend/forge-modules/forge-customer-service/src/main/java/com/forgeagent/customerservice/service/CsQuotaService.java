package com.forgeagent.customerservice.service;

import com.forgeagent.customerservice.config.ConditionalOnCustomerServiceEnabled;
import com.forgeagent.customerservice.config.CsModuleProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 配额判定(P2,差距分析 P2 第二项)。
 *
 * <p>口径:按「空间当日 token 用量」判定,数据来自 {@code agent_token_usage},
 * 包含该空间全部模型调用(不只客服)——配额的目标是控成本,不是精确归属。</p>
 *
 * <p>只实现两档:<b>WARN</b> 记日志放行、<b>BLOCK</b> 拒绝并转人工(转人工不消耗模型)。
 * <b>DEGRADE 未实现</b>——降级要切换备用模型,客服域走的是统一对话契约,
 * 换模型得在模型路由层做,不适合在客服模块里另起一套。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnCustomerServiceEnabled
public class CsQuotaService {

    public enum Level {
        ALLOW, WARN, BLOCK
    }

    /** 判定结果:used/limit 一并返回,运营页与日志都用得上。 */
    public record Decision(Level level, long used, long limit) {
        public boolean blocked() {
            return level == Level.BLOCK;
        }
    }

    private final CsCostService costService;
    private final CsModuleProperties properties;

    /** 是否启用配额。 */
    public boolean enabled() {
        return Boolean.TRUE.equals(properties.getQuotaEnabled());
    }

    /** 超额处置方式(WARN / BLOCK),运营页展示用。 */
    public String action() {
        String action = properties.getQuotaAction();
        return action == null || action.isBlank() ? "WARN" : action.toUpperCase();
    }

    /** 每次访客消息进模型前判一次;未开启或未配上限直接放行。 */
    public Decision check(Long workspaceId) {
        long limit = properties.getQuotaDailyTokenLimit() == null ? 0 : properties.getQuotaDailyTokenLimit();
        if (!Boolean.TRUE.equals(properties.getQuotaEnabled()) || limit <= 0) {
            return new Decision(Level.ALLOW, 0, limit);
        }
        long used = costService.usedToday(workspaceId);
        if (used < limit) {
            return new Decision(Level.ALLOW, used, limit);
        }
        boolean block = "BLOCK".equalsIgnoreCase(properties.getQuotaAction());
        if (!block) {
            log.warn("[CS] 空间 token 用量超限(仅告警) workspaceId={} used={} limit={}",
                workspaceId, used, limit);
        }
        return new Decision(block ? Level.BLOCK : Level.WARN, used, limit);
    }
}
