package com.forgeagent.customerservice.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.forgeagent.common.core.exception.ServiceException;
import com.forgeagent.customerservice.config.ConditionalOnCustomerServiceEnabled;
import com.forgeagent.customerservice.domain.CsConversation;
import com.forgeagent.customerservice.domain.CsCsat;
import com.forgeagent.customerservice.mapper.CsConversationMapper;
import com.forgeagent.customerservice.mapper.CsCsatMapper;
import com.forgeagent.callcenter.common.CsEventEnvelope;
import com.forgeagent.callcenter.common.CsRealtimeTransport;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 会话满意度(C2)。结案时写邀请记录作为回收率分母,访客提交后更新评分。
 */
@Service
@RequiredArgsConstructor
@ConditionalOnCustomerServiceEnabled
public class CsatService {

    private final CsCsatMapper csatMapper;
    private final CsConversationMapper conversationMapper;
    private final CsRealtimeTransport transport;

    /** 幂等邀请:同一会话只记录一次邀请时间。 */
    public CsCsat invite(Long conversationId, Long workspaceId, String channelCode) {
        CsCsat existing = csatMapper.selectOne(new LambdaQueryWrapper<CsCsat>()
            .eq(CsCsat::getConversationId, conversationId).last("limit 1"));
        if (existing != null) {
            return existing;
        }
        CsCsat csat = new CsCsat();
        csat.setConversationId(conversationId);
        csat.setWorkspaceId(workspaceId);
        csat.setChannelCode(channelCode);
        csat.setInvitedAt(new Date());
        csatMapper.insert(csat);
        transport.push(CsEventEnvelope.of(CsEventEnvelope.CSAT_INVITE, conversationId, null,
            Map.of("message", "请为本次服务评分")));
        return csat;
    }

    /** 访客提交评分:校验会话归属,重复提交按更新处理。 */
    public CsCsat submit(String channelCode, Long conversationId, Long visitorId,
                         Integer score, String comment, String tags) {
        if (score == null || score < 1 || score > 5) {
            throw new ServiceException("评分须为 1 到 5 分");
        }
        CsConversation conv = conversationMapper.selectById(conversationId);
        if (conv == null || !channelCode.equals(conv.getChannelCode())
            || !visitorId.equals(conv.getVisitorId())) {
            throw new ServiceException("会话不存在或无权评价");
        }
        CsCsat csat = csatMapper.selectOne(new LambdaQueryWrapper<CsCsat>()
            .eq(CsCsat::getConversationId, conversationId).last("limit 1"));
        if (csat == null) {
            csat = new CsCsat();
            csat.setConversationId(conversationId);
            csat.setWorkspaceId(conv.getWorkspaceId());
            csat.setChannelCode(channelCode);
            csat.setInvitedAt(new Date());
        }
        csat.setScore(score);
        csat.setComment(comment);
        csat.setTags(tags);
        csat.setSubmittedAt(new Date());
        if (csat.getId() == null) {
            csatMapper.insert(csat);
        } else {
            csatMapper.updateById(csat);
        }
        return csat;
    }

    public List<CsCsat> list(Long workspaceId) {
        return csatMapper.selectList(new LambdaQueryWrapper<CsCsat>()
            .eq(CsCsat::getWorkspaceId, workspaceId)
            .orderByDesc(CsCsat::getInvitedAt));
    }

    /** 平均分与邀请回收率,供运营看板直读。 */
    public Map<String, Object> stats(Long workspaceId, Date from, Date to) {
        List<CsCsat> all = csatMapper.selectList(new LambdaQueryWrapper<CsCsat>()
            .eq(CsCsat::getWorkspaceId, workspaceId)
            .ge(from != null, CsCsat::getInvitedAt, from)
            .lt(to != null, CsCsat::getInvitedAt, to));
        long submitted = all.stream().filter(c -> c.getScore() != null).count();
        double avg = all.stream().filter(c -> c.getScore() != null)
            .mapToInt(CsCsat::getScore).average().orElse(0);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("invited", all.size());
        data.put("submitted", submitted);
        data.put("avgScore", Math.round(avg * 100) / 100.0);
        data.put("responseRate", all.isEmpty() ? 0 : Math.round(submitted * 10000.0 / all.size()) / 100.0);
        return data;
    }
}
