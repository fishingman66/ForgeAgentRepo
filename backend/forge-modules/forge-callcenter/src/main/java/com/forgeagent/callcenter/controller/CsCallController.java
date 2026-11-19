package com.forgeagent.callcenter.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.forgeagent.callcenter.config.CallcenterProperties;
import com.forgeagent.callcenter.config.ConditionalOnCallcenterEnabled;
import com.forgeagent.callcenter.domain.CsCall;
import com.forgeagent.callcenter.domain.CsCallEvent;
import com.forgeagent.callcenter.domain.CsCallMetricDaily;
import com.forgeagent.callcenter.domain.CsCallProfile;
import com.forgeagent.callcenter.domain.CsCallSegment;
import com.forgeagent.callcenter.domain.CsCallSeatState;
import com.forgeagent.callcenter.domain.CsCallbackTask;
import com.forgeagent.callcenter.domain.vo.CsCallDetailVo;
import com.forgeagent.callcenter.domain.vo.CsCallEventVo;
import com.forgeagent.callcenter.domain.vo.CsCallMetricDailyVo;
import com.forgeagent.callcenter.domain.vo.CsCallProfileVo;
import com.forgeagent.callcenter.domain.vo.CsCallRecordingVo;
import com.forgeagent.callcenter.domain.vo.CsCallSeatStateVo;
import com.forgeagent.callcenter.domain.vo.CsCallSegmentVo;
import com.forgeagent.callcenter.domain.vo.CsCallVo;
import com.forgeagent.callcenter.domain.vo.CsCallbackTaskVo;
import com.forgeagent.callcenter.mapper.CsCallEventMapper;
import com.forgeagent.callcenter.mapper.CsCallProfileMapper;
import com.forgeagent.callcenter.mapper.CsCallbackTaskMapper;
import com.forgeagent.callcenter.mapper.CsCallMapper;
import com.forgeagent.callcenter.mapper.CsCallSeatStateMapper;
import com.forgeagent.callcenter.mapper.CsCallSegmentMapper;
import com.forgeagent.callcenter.ops.CallProfileService;
import com.forgeagent.callcenter.ops.CallbackTaskService;
import com.forgeagent.callcenter.service.CallMetricService;
import com.forgeagent.callcenter.storage.RecordingStorage;
import com.forgeagent.common.core.domain.R;
import com.forgeagent.common.satoken.utils.LoginHelper;
import com.forgeagent.common.web.core.BaseController;
import com.forgeagent.customerservice.compliance.CsComplianceService;
import com.forgeagent.customerservice.domain.CsSeat;
import com.forgeagent.customerservice.mapper.CsSeatMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.List;

/**
 * 坐席端通话查询(V1 最小集 → V2 完整):列表 / 详情 / 指标 / 坐席就绪态 / 录音签名访问。
 *
 * <p>安全边界:workspace 隔离(非本空间通话一律 404 语义拒绝)、
 * 录音访问全部走后端短期签名 URL(oss 模式 presignGetUrl / local-fs 模式 HMAC 下载),
 * 前端不持有任何存储凭据;每次录音访问写 RECORDING_ACCESS 审计。</p>
 *
 * <p>扩展方向:坐席侧通话中转接 / 邀请加入仍归 AICC 工作台(D7=①,本模块只读回写);
 * 后续如需通话标签、人工补挂工单(link-ticket)在详情上扩展即可。</p>
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@SaCheckLogin
@ConditionalOnCallcenterEnabled
@RequestMapping("/agent/customerservice/calls")
public class CsCallController extends BaseController {

    private final CsCallMapper callMapper;
    private final CsCallEventMapper callEventMapper;
    private final CsCallSegmentMapper callSegmentMapper;
    private final CsCallSeatStateMapper seatStateMapper;
    private final CsSeatMapper seatMapper;
    private final ObjectProvider<CsComplianceService> complianceProvider;
    private final CallcenterProperties properties;
    private final RecordingStorage recordingStorage;
    private final CallMetricService metricService;
    private final CsCallProfileMapper profileMapper;
    private final CsCallbackTaskMapper callbackTaskMapper;
    private final CallProfileService profileService;
    private final CallbackTaskService callbackTaskService;

    /** 通话列表:workspace/status/direction 过滤全部下推 SQL,不做应用层过滤。 */
    @GetMapping
    public R<List<CsCallVo>> list(@RequestParam(required = false) String status,
                                  @RequestParam(required = false) String direction) {
        LambdaQueryWrapper<CsCall> wrapper = new LambdaQueryWrapper<CsCall>()
            .eq(CsCall::getWorkspaceId, currentWorkspaceId())
            .orderByDesc(CsCall::getId)
            .last("limit 200");
        if (status != null && !status.isBlank()) {
            wrapper.eq(CsCall::getStatus, status);
        }
        if (direction != null && !direction.isBlank()) {
            wrapper.eq(CsCall::getDirection, direction);
        }
        return R.ok(toCallVoList(callMapper.selectList(wrapper)));
    }

    /** 通话详情:主记录 + 事件轨迹 + 转写时间轴,一次返回供详情抽屉渲染。 */
    @GetMapping("/{id}")
    public R<CsCallDetailVo> detail(@PathVariable Long id) {
        CsCall call = callMapper.selectById(id);
        if (call == null || !call.getWorkspaceId().equals(currentWorkspaceId())) {
            return R.fail("通话不存在或无权访问");
        }
        CsCallDetailVo data = new CsCallDetailVo();
        data.setCall(toCallVo(call));
        data.setEvents(toEventVoList(callEventMapper.selectList(new LambdaQueryWrapper<CsCallEvent>()
            .eq(CsCallEvent::getCallId, id).orderByAsc(CsCallEvent::getId))));
        data.setSegments(toSegmentVoList(callSegmentMapper.selectList(new LambdaQueryWrapper<CsCallSegment>()
            .eq(CsCallSegment::getCallId, id).orderByAsc(CsCallSegment::getId))));
        return R.ok(data);
    }

    /** 电话坐席就绪态:按 AICC 工号维护,坐席-workNo 绑定关系回填后此处自动带出坐席资料。 */
    @GetMapping("/call-seats")
    public R<List<CsCallSeatStateVo>> callSeats() {
        return R.ok(toSeatStateVoList(seatStateMapper.selectList(new LambdaQueryWrapper<CsCallSeatState>()
            .orderByDesc(CsCallSeatState::getUpdateTime).last("limit 100"))));
    }

    /** 当日通话指标:列表页顶部统计卡片直读;月度对账报表后续按同一 service 扩展。 */
    @GetMapping("/stats")
    public R<CsCallMetricDailyVo> stats() {
        return R.ok(toMetricVo(metricService.today(currentWorkspaceId())));
    }

    /** 录音短期签名访问 + RECORDING_ACCESS 审计;失败信息不区分"未就绪"与"已清理"。 */
    @GetMapping("/{id}/recording")
    public R<CsCallRecordingVo> recording(@PathVariable Long id) {
        CsCall call = callMapper.selectById(id);
        if (call == null || !call.getWorkspaceId().equals(currentWorkspaceId())) {
            return R.fail("通话不存在或无权访问");
        }
        if (call.getRecordingOssKey() == null || call.getRecordingOssKey().isBlank()) {
            return R.fail("录音尚未就绪或已被留存清理");
        }
        int ttl = Math.max(30, properties.getRecordingPresignTtlSeconds());
        String url = recordingStorage.presignReadUrl(call.getRecordingOssKey(), Duration.ofSeconds(ttl));
        CsComplianceService compliance = complianceProvider.getIfAvailable();
        if (compliance != null) {
            compliance.record("RECORDING_ACCESS", "user", String.valueOf(LoginHelper.getUserId()),
                call.getWorkspaceId(), call.getChannelCode(),
                "callId=" + call.getCallId() + ",key=" + call.getRecordingOssKey() + ",ttl=" + ttl + "s");
        }
        CsCallRecordingVo data = new CsCallRecordingVo();
        data.setUrl(url);
        data.setOssKey(call.getRecordingOssKey());
        data.setExpireSeconds(ttl);
        return R.ok(data);
    }

    /**
     * 通话画像(C8):有则直读,无则按洞察/质检现算一份(历史通话同样可看)。
     */
    @GetMapping("/{id}/profile")
    public R<CsCallProfileVo> profile(@PathVariable Long id) {
        CsCall call = callMapper.selectById(id);
        if (call == null || !call.getWorkspaceId().equals(currentWorkspaceId())) {
            return R.fail("通话不存在或无权访问");
        }
        CsCallProfile existing = profileMapper.selectOne(new LambdaQueryWrapper<CsCallProfile>()
            .eq(CsCallProfile::getCallId, id).last("limit 1"));
        return R.ok(toProfileVo(existing != null ? existing : profileService.build(call)));
    }

    /** 呼损回拨清单(C8):默认全部状态,可按状态过滤。 */
    @GetMapping("/callbacks")
    public R<List<CsCallbackTaskVo>> callbacks(@RequestParam(required = false) String status) {
        return R.ok(toCallbackVoList(callbackTaskService.list(currentWorkspaceId(), status)));
    }

    /** 回拨单状态流转(C8):非法流转返回可读原因,不抛 500。 */
    @PostMapping("/callbacks/{id}/status")
    public R<CsCallbackTaskVo> markCallback(@PathVariable Long id,
                                            @RequestParam String status,
                                            @RequestParam(required = false) String remark) {
        CsCallbackTask task = callbackTaskMapper.selectById(id);
        if (task == null || !task.getWorkspaceId().equals(currentWorkspaceId())) {
            return R.fail("回拨单不存在或无权访问");
        }
        try {
            return R.ok(toCallbackVo(callbackTaskService.mark(id, status, LoginHelper.getUserId(), remark)));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return R.fail(e.getMessage());
        }
    }

    /** 数据边界:坐席行缺失时返回 -1(空空间),保证跨空间数据零可见。 */
    private Long currentWorkspaceId() {
        CsSeat seat = seatMapper.selectOne(new LambdaQueryWrapper<CsSeat>()
            .eq(CsSeat::getUserId, LoginHelper.getUserId()).last("limit 1"));
        return seat == null ? -1L : seat.getWorkspaceId();
    }

    /*
     * 实体 → Vo 转换。
     *
     * <p>刻意手写映射而不走 MapstructUtils:后者的静态初始化会调 SpringUtils.getBean,
     * 纯 Mockito 单测里类加载即失败(见 docs/12 第八节)。
     * 抽成 protected 方法,单测可用 spy 覆写,避免测试依赖 MapStruct 生成代码。</p>
     */

    protected CsCallVo toCallVo(CsCall c) {
        if (c == null) {
            return null;
        }
        CsCallVo vo = new CsCallVo();
        vo.setId(c.getId());
        vo.setCallId(c.getCallId());
        vo.setMediaProvider(c.getMediaProvider());
        vo.setChannelCode(c.getChannelCode());
        vo.setWorkspaceId(c.getWorkspaceId());
        vo.setAgentId(c.getAgentId());
        vo.setConversationId(c.getConversationId());
        vo.setTicketId(c.getTicketId());
        vo.setDirection(c.getDirection());
        vo.setCallerNumber(c.getCallerNumber());
        vo.setCalleeNumber(c.getCalleeNumber());
        vo.setAni(c.getAni());
        vo.setDnis(c.getDnis());
        vo.setStatus(c.getStatus());
        vo.setBotHandled(c.getBotHandled());
        vo.setWorkNo(c.getWorkNo());
        vo.setRingAt(c.getRingAt());
        vo.setAnswerAt(c.getAnswerAt());
        vo.setHangupAt(c.getHangupAt());
        vo.setDurationSec(c.getDurationSec());
        vo.setBillSec(c.getBillSec());
        vo.setHangupCause(c.getHangupCause());
        vo.setHangupBy(c.getHangupBy());
        vo.setSipCode(c.getSipCode());
        vo.setRecordingOssKey(c.getRecordingOssKey());
        vo.setRecordingId(c.getRecordingId());
        vo.setRecordingReadyAt(c.getRecordingReadyAt());
        vo.setRecordingRetainUntil(c.getRecordingRetainUntil());
        vo.setIvrPath(c.getIvrPath());
        vo.setDtmfDigits(c.getDtmfDigits());
        vo.setTransferFrom(c.getTransferFrom());
        vo.setTransferTo(c.getTransferTo());
        vo.setCreateTime(c.getCreateTime());
        vo.setUpdateTime(c.getUpdateTime());
        return vo;
    }

    protected List<CsCallVo> toCallVoList(List<CsCall> rows) {
        return rows == null ? List.of() : rows.stream().map(this::toCallVo).toList();
    }

    protected CsCallEventVo toEventVo(CsCallEvent e) {
        if (e == null) {
            return null;
        }
        CsCallEventVo vo = new CsCallEventVo();
        vo.setId(e.getId());
        vo.setCallId(e.getCallId());
        vo.setEventSeq(e.getEventSeq());
        vo.setEventType(e.getEventType());
        vo.setFromStatus(e.getFromStatus());
        vo.setToStatus(e.getToStatus());
        vo.setActorType(e.getActorType());
        vo.setPayload(e.getPayload());
        vo.setCreateTime(e.getCreateTime());
        return vo;
    }

    protected List<CsCallEventVo> toEventVoList(List<CsCallEvent> rows) {
        return rows == null ? List.of() : rows.stream().map(this::toEventVo).toList();
    }

    protected CsCallSegmentVo toSegmentVo(CsCallSegment s) {
        if (s == null) {
            return null;
        }
        CsCallSegmentVo vo = new CsCallSegmentVo();
        vo.setId(s.getId());
        vo.setCallId(s.getCallId());
        vo.setSpeaker(s.getSpeaker());
        vo.setText(s.getText());
        vo.setStartMs(s.getStartMs());
        vo.setEndMs(s.getEndMs());
        vo.setAsrConfidence(s.getAsrConfidence());
        vo.setIsFinal(s.getIsFinal());
        vo.setSegmentKind(s.getSegmentKind());
        vo.setCreateTime(s.getCreateTime());
        return vo;
    }

    protected List<CsCallSegmentVo> toSegmentVoList(List<CsCallSegment> rows) {
        return rows == null ? List.of() : rows.stream().map(this::toSegmentVo).toList();
    }

    protected CsCallSeatStateVo toSeatStateVo(CsCallSeatState s) {
        if (s == null) {
            return null;
        }
        CsCallSeatStateVo vo = new CsCallSeatStateVo();
        vo.setId(s.getId());
        vo.setSeatId(s.getSeatId());
        vo.setUserId(s.getUserId());
        vo.setWorkspaceId(s.getWorkspaceId());
        vo.setWorkNo(s.getWorkNo());
        vo.setExtension(s.getExtension());
        vo.setReadyState(s.getReadyState());
        vo.setPauseReason(s.getPauseReason());
        vo.setLoginAt(s.getLoginAt());
        vo.setLogoutAt(s.getLogoutAt());
        vo.setCreateTime(s.getCreateTime());
        vo.setUpdateTime(s.getUpdateTime());
        return vo;
    }

    protected List<CsCallSeatStateVo> toSeatStateVoList(List<CsCallSeatState> rows) {
        return rows == null ? List.of() : rows.stream().map(this::toSeatStateVo).toList();
    }

    protected CsCallMetricDailyVo toMetricVo(CsCallMetricDaily m) {
        if (m == null) {
            return null;
        }
        CsCallMetricDailyVo vo = new CsCallMetricDailyVo();
        vo.setId(m.getId());
        vo.setStatDate(m.getStatDate());
        vo.setWorkspaceId(m.getWorkspaceId());
        vo.setCallCount(m.getCallCount());
        vo.setAnsweredCount(m.getAnsweredCount());
        vo.setAnswerRate(m.getAnswerRate());
        vo.setAvgDurationSec(m.getAvgDurationSec());
        vo.setBotResolveRate(m.getBotResolveRate());
        vo.setRecordingCoverage(m.getRecordingCoverage());
        vo.setTransferCount(m.getTransferCount());
        vo.setTransferRate(m.getTransferRate());
        vo.setAbandonCount(m.getAbandonCount());
        vo.setAbandonRate(m.getAbandonRate());
        vo.setAvgWaitSec(m.getAvgWaitSec());
        vo.setNegativeCalls(m.getNegativeCalls());
        vo.setNegativeRate(m.getNegativeRate());
        vo.setQualityAvgScore(m.getQualityAvgScore());
        vo.setCreateTime(m.getCreateTime());
        vo.setUpdateTime(m.getUpdateTime());
        return vo;
    }

    protected CsCallProfileVo toProfileVo(CsCallProfile p) {
        if (p == null) {
            return null;
        }
        CsCallProfileVo vo = new CsCallProfileVo();
        vo.setId(p.getId());
        vo.setCallId(p.getCallId());
        vo.setWorkspaceId(p.getWorkspaceId());
        vo.setChannelCode(p.getChannelCode());
        vo.setCallerNumber(p.getCallerNumber());
        vo.setIntentCode(p.getIntentCode());
        vo.setSentimentLabel(p.getSentimentLabel());
        vo.setSentimentScore(p.getSentimentScore());
        vo.setQualityScore(p.getQualityScore());
        vo.setDurationSec(p.getDurationSec());
        vo.setTurnCount(p.getTurnCount());
        vo.setTransferFlag(p.getTransferFlag());
        vo.setResolvedFlag(p.getResolvedFlag());
        vo.setTags(p.getTags());
        vo.setSummary(p.getSummary());
        vo.setPayload(p.getPayload());
        vo.setCreateTime(p.getCreateTime());
        vo.setUpdateTime(p.getUpdateTime());
        return vo;
    }

    protected CsCallbackTaskVo toCallbackVo(CsCallbackTask t) {
        if (t == null) {
            return null;
        }
        CsCallbackTaskVo vo = new CsCallbackTaskVo();
        vo.setId(t.getId());
        vo.setCallId(t.getCallId());
        vo.setWorkspaceId(t.getWorkspaceId());
        vo.setChannelCode(t.getChannelCode());
        vo.setCallerNumber(t.getCallerNumber());
        vo.setReason(t.getReason());
        vo.setStatus(t.getStatus());
        vo.setAttemptCount(t.getAttemptCount());
        vo.setLastAttemptAt(t.getLastAttemptAt());
        vo.setOperatorId(t.getOperatorId());
        vo.setRemark(t.getRemark());
        vo.setCreateTime(t.getCreateTime());
        vo.setUpdateTime(t.getUpdateTime());
        return vo;
    }

    protected List<CsCallbackTaskVo> toCallbackVoList(List<CsCallbackTask> rows) {
        return rows == null ? List.of() : rows.stream().map(this::toCallbackVo).toList();
    }
}
