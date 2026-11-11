package com.forgeagent.customerservice.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.forgeagent.common.core.exception.ServiceException;
import com.forgeagent.customerservice.config.ConditionalOnCustomerServiceEnabled;
import com.forgeagent.customerservice.domain.CsDeadLetter;
import com.forgeagent.customerservice.event.CsBusinessFailureEvent;
import com.forgeagent.customerservice.mapper.CsDeadLetterMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.List;

/**
 * 客服死信(P2 收尾):把「失败但可重投」的业务动作记下来,运营能补跑。
 *
 * <p>与流式事件推送的区别:H5 的 SSE 走内存缓冲,推送失败只降级为"不可续传",
 * 重投没有意义,所以不进死信。进死信的是三件有明确重投语义的事——</p>
 * <ul>
 *   <li>{@code HANDOFF_SUMMARY} 交接摘要生成失败:重投 = 重新生成;</li>
 *   <li>{@code SEAT_ASSIST} 坐席推荐生成失败:重投 = 重新推荐;</li>
 *   <li>{@code ATTACHMENT_UPLOAD} 附件上传失败:字节已丢,标 <b>不可重投</b>,只能让客户重传。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnCustomerServiceEnabled
public class CsDeadLetterService {

    private static final int ERROR_LIMIT = 500;
    private static final String TYPE_SUMMARY = "HANDOFF_SUMMARY";

    private final CsDeadLetterMapper deadLetterMapper;
    private final CsHandoffSummaryService summaryService;

    /** 失败方发布事件,这里只负责落库(同会话同类型 24h 内有 PENDING 则跳过,避免刷屏)。 */
    @EventListener
    public void onFailure(CsBusinessFailureEvent event) {
        if (event == null || event.workspaceId() == null || event.eventType() == null) {
            return;
        }
        try {
            if (existsRecentPending(event)) {
                return;
            }
            CsDeadLetter row = new CsDeadLetter();
            row.setWorkspaceId(event.workspaceId());
            row.setChannelCode(event.channelCode());
            row.setEventType(event.eventType());
            row.setConversationId(event.conversationId());
            row.setTicketId(event.ticketId());
            row.setError(truncate(event.error()));
            row.setStatus("PENDING");
            row.setRetryCount(0);
            row.setCreateTime(new Date());
            row.setUpdateTime(new Date());
            deadLetterMapper.insert(row);
            log.warn("[CS] 业务失败已入死信 type={} conversationId={}", event.eventType(), event.conversationId());
        } catch (Exception e) {
            // 死信自身失败不能再抛,否则会把主链路的异常处理搅乱
            log.warn("[CS] 死信落库失败 type={}: {}", event.eventType(), e.getMessage());
        }
    }

    /** 重投:目前只有摘要支持自动重投。 */
    public CsDeadLetter replay(Long id, Long workspaceId) {
        CsDeadLetter row = require(id, workspaceId);
        if (!"PENDING".equals(row.getStatus())) {
            throw new ServiceException("只有待处理记录可重投");
        }
        if ("ATTACHMENT_UPLOAD".equals(row.getEventType())) {
            row.setStatus("UNREPLAYABLE");
            row.setUpdateTime(new Date());
            deadLetterMapper.updateById(row);
            throw new ServiceException("附件字节已随请求丢弃，无法重投，只能请客户重新上传");
        }
        if (!TYPE_SUMMARY.equals(row.getEventType())) {
            throw new ServiceException("该类型暂不支持自动重投");
        }
        if (row.getConversationId() == null) {
            throw new ServiceException("缺少会话ID，无法重投");
        }
        try {
            summaryService.generate(row.getWorkspaceId(), row.getConversationId(), row.getTicketId());
        } catch (Exception e) {
            row.setRetryCount((row.getRetryCount() == null ? 0 : row.getRetryCount()) + 1);
            row.setLastRetryAt(new Date());
            row.setError(truncate(e.getMessage()));
            row.setUpdateTime(new Date());
            deadLetterMapper.updateById(row);
            throw new ServiceException("重投仍失败：" + e.getMessage());
        }
        row.setStatus("REPLAYED");
        row.setRetryCount((row.getRetryCount() == null ? 0 : row.getRetryCount()) + 1);
        row.setLastRetryAt(new Date());
        row.setUpdateTime(new Date());
        deadLetterMapper.updateById(row);
        return row;
    }

    public boolean ignore(Long id, Long workspaceId) {
        CsDeadLetter row = require(id, workspaceId);
        row.setStatus("IGNORED");
        row.setUpdateTime(new Date());
        return deadLetterMapper.updateById(row) > 0;
    }

    public List<CsDeadLetter> list(Long workspaceId, String status, int limit) {
        LambdaQueryWrapper<CsDeadLetter> query = new LambdaQueryWrapper<CsDeadLetter>()
            .eq(CsDeadLetter::getWorkspaceId, workspaceId);
        if (status != null && !status.isBlank()) {
            query.eq(CsDeadLetter::getStatus, status);
        }
        return deadLetterMapper.selectList(query
            .orderByDesc(CsDeadLetter::getId)
            .last("limit " + Math.max(1, Math.min(200, limit))));
    }

    private boolean existsRecentPending(CsBusinessFailureEvent event) {
        LambdaQueryWrapper<CsDeadLetter> query = new LambdaQueryWrapper<CsDeadLetter>()
            .eq(CsDeadLetter::getWorkspaceId, event.workspaceId())
            .eq(CsDeadLetter::getEventType, event.eventType())
            .eq(CsDeadLetter::getStatus, "PENDING")
            .gt(CsDeadLetter::getCreateTime, new Date(System.currentTimeMillis() - 86_400_000L));
        if (event.conversationId() != null) {
            query.eq(CsDeadLetter::getConversationId, event.conversationId());
        }
        Long count = deadLetterMapper.selectCount(query);
        return count != null && count > 0;
    }

    private CsDeadLetter require(Long id, Long workspaceId) {
        CsDeadLetter row = deadLetterMapper.selectById(id);
        if (row == null || !row.getWorkspaceId().equals(workspaceId)) {
            throw new ServiceException("死信记录不存在");
        }
        return row;
    }

    private static String truncate(String text) {
        if (text == null) {
            return null;
        }
        return text.length() <= ERROR_LIMIT ? text : text.substring(0, ERROR_LIMIT);
    }
}
