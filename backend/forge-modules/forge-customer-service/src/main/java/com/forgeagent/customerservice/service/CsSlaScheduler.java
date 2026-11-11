package com.forgeagent.customerservice.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.forgeagent.customerservice.config.ConditionalOnCustomerServiceEnabled;
import com.forgeagent.customerservice.config.CsModuleProperties;
import com.forgeagent.customerservice.domain.CsTicket;
import com.forgeagent.customerservice.mapper.CsTicketMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Date;
import java.util.List;

/**
 * 工单 SLA 定时处置(P0,差距分析 4.3)。
 *
 * <p>此前 `sla-first-response-ms` 只用于看板算达标率,工单超时无人知道,
 * `WAITING_CONFIRM` 客户不点会永远挂着。本类补上两条:</p>
 * <ul>
 *   <li><b>只告警</b>：WAITING_AGENT / PROCESSING 超时 → 写 SLA_BREACH 事件(1 小时内不重复);</li>
 *   <li><b>自动推进</b>(可关)：WAITING_CONFIRM 超时自动确认、RESOLVED 超时自动关闭。</li>
 * </ul>
 *
 * <p>并发安全：告警靠事件去重,自动推进靠 {@code CsTicketService.transition} 的跃迁表校验——
 * 工单已被别人处理时会抛异常被忽略,不会造成重复流转。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnCustomerServiceEnabled
public class CsSlaScheduler {

    /** 单轮最多处理多少条,避免积压时一次拉爆。 */
    private static final int BATCH = 50;

    private final CsTicketMapper ticketMapper;
    private final CsTicketService ticketService;
    private final CsModuleProperties properties;

    /** 每分钟扫一次;要关闭扫描请把 sla-enabled 置 false(周期 0 会让定时任务注册直接失败)。 */
    @Scheduled(fixedDelayString = "${agent.customer-service.sla-scan-ms:60000}")
    public void scan() {
        if (Boolean.FALSE.equals(properties.getSlaEnabled())) {
            return;
        }
        Long configured = properties.getSlaScanMs();
        if (configured == null || configured <= 0) {
            // 防御:间隔配成 0/负数时定时任务本身已注册失败,这里只兜住"运行期被改成非法值"
            return;
        }
        Date now = new Date();
        alertStale(now, "WAITING_AGENT", minutes(properties.getSlaWaitingAgentMinutes(), 10));
        alertStale(now, "PROCESSING", minutes(properties.getSlaProcessingMinutes(), 120));
        if (Boolean.FALSE.equals(properties.getSlaAutoAdvance())) {
            return;
        }
        autoConfirm(now, minutes(properties.getSlaConfirmHours(), 24) * 60);
        autoClose(now, minutes(properties.getSlaCloseHours(), 72) * 60);
    }

    private void alertStale(Date now, String status, int minutes) {
        if (minutes <= 0) {
            return;
        }
        List<CsTicket> stale = ticketMapper.selectList(new LambdaQueryWrapper<CsTicket>()
            .eq(CsTicket::getStatus, status)
            .lt(CsTicket::getUpdateTime, new Date(now.getTime() - minutes * 60_000L))
            .orderByAsc(CsTicket::getUpdateTime)
            .last("limit " + BATCH));
        int alerted = 0;
        for (CsTicket ticket : stale) {
            if (ticketService.markSlaBreach(ticket.getId(), status + " 超时 " + minutes + " 分钟")) {
                alerted++;
            }
        }
        if (alerted > 0) {
            log.warn("[CS] SLA 告警写入 {} 条 status={}", alerted, status);
        }
    }

    private void autoConfirm(Date now, int minutes) {
        if (minutes <= 0) {
            return;
        }
        List<CsTicket> stale = ticketMapper.selectList(new LambdaQueryWrapper<CsTicket>()
            .eq(CsTicket::getStatus, "WAITING_CONFIRM")
            .lt(CsTicket::getUpdateTime, new Date(now.getTime() - minutes * 60_000L))
            .orderByAsc(CsTicket::getUpdateTime)
            .last("limit " + BATCH));
        int done = 0;
        for (CsTicket ticket : stale) {
            try {
                ticketService.transition(ticket.getId(), "RESOLVED", "SLA_AUTO_CONFIRM", "SYSTEM", null,
                    "客户超时未确认,系统自动确认");
                done++;
            } catch (Exception e) {
                log.debug("[CS] SLA 自动确认跳过 ticketId={}: {}", ticket.getId(), e.getMessage());
            }
        }
        if (done > 0) {
            log.info("[CS] SLA 自动确认 {} 条", done);
        }
    }

    private void autoClose(Date now, int minutes) {
        if (minutes <= 0) {
            return;
        }
        List<CsTicket> stale = ticketMapper.selectList(new LambdaQueryWrapper<CsTicket>()
            .eq(CsTicket::getStatus, "RESOLVED")
            .lt(CsTicket::getUpdateTime, new Date(now.getTime() - minutes * 60_000L))
            .orderByAsc(CsTicket::getUpdateTime)
            .last("limit " + BATCH));
        int done = 0;
        for (CsTicket ticket : stale) {
            try {
                ticketService.transition(ticket.getId(), "CLOSED", "SLA_AUTO_CLOSE", "SYSTEM", null,
                    "结案超时,系统自动关闭");
                done++;
            } catch (Exception e) {
                log.debug("[CS] SLA 自动关闭跳过 ticketId={}: {}", ticket.getId(), e.getMessage());
            }
        }
        if (done > 0) {
            log.info("[CS] SLA 自动关闭 {} 条", done);
        }
    }

    private static int minutes(Integer value, int fallback) {
        return value == null ? fallback : value;
    }
}
