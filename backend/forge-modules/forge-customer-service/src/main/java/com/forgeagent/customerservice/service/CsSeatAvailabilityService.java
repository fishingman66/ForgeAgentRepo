package com.forgeagent.customerservice.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.forgeagent.customerservice.domain.CsChannel;
import com.forgeagent.customerservice.domain.CsSeat;
import com.forgeagent.customerservice.mapper.CsSeatMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * A-1:转人工可行性(能不能转),与规则引擎的「要不要转」正交。
 *
 * <p>判定 = 营业时间内 && 至少一名在线且未满载坐席。查询口径与
 * {@code CsTicketService.assignWaiting()} 的 ACD 分配一致(online=1 + currentLoad<maxLoad,
 * null 分别按 0 / 5 处理),避免「引擎放行但队列无人可接」。</p>
 *
 * <p>配置为空时营业时间按 24h,只受坐席状态约束——存量渠道行为仅在无可用坐席时变化,
 * 而那种情况原本也会进 WAITING 队列长期悬挂,改为留言是改进。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CsSeatAvailabilityService {

    /** 与 CsTicketService.assignWaiting() 同默认值。 */
    private static final int DEFAULT_MAX_LOAD = 5;

    private final CsSeatMapper seatMapper;

    /** 可行性判定结果:reason 直接落 handoff 原因,便于运营归因。 */
    public record Availability(boolean transferable, String reason) {
        public static Availability ok() {
            return new Availability(true, null);
        }
    }

    /**
     * 查渠道所在空间的坐席可用性。DB 异常按「不可转」保守处理(fail-closed),
     * 避免规则引擎放行后工单进无人队列。
     */
    public Availability evaluate(CsChannel channel) {
        if (channel == null) {
            return new Availability(false, "渠道不存在,无法转人工");
        }
        if (!CsBusinessHours.isOpen(channel.getBusinessHoursJson())) {
            return new Availability(false, "当前非营业时间");
        }
        try {
            List<CsSeat> seats = seatMapper.selectList(new LambdaQueryWrapper<CsSeat>()
                .eq(CsSeat::getWorkspaceId, channel.getWorkspaceId())
                .eq(CsSeat::getOnline, "1"));
            if (seats == null || seats.isEmpty()) {
                return new Availability(false, "当前无在线坐席");
            }
            for (CsSeat seat : seats) {
                int load = seat.getCurrentLoad() == null ? 0 : seat.getCurrentLoad();
                int max = seat.getMaxLoad() == null ? DEFAULT_MAX_LOAD : seat.getMaxLoad();
                if (load < max) {
                    return Availability.ok();
                }
            }
            return new Availability(false, "在线坐席均已满载");
        } catch (Exception e) {
            log.warn("[CS] 坐席可用性查询失败 workspaceId={}: {}", channel.getWorkspaceId(), e.getMessage());
            return new Availability(false, "坐席状态查询失败");
        }
    }
}
