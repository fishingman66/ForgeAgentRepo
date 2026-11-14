package com.forgeagent.callcenter.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.forgeagent.callcenter.config.CallcenterProperties;
import com.forgeagent.callcenter.config.ConditionalOnCallcenterEnabled;
import com.forgeagent.callcenter.domain.CsCall;
import com.forgeagent.callcenter.domain.CsCallEvent;
import com.forgeagent.callcenter.domain.CsCallProfile;
import com.forgeagent.callcenter.domain.CsCallMetricDaily;
import com.forgeagent.callcenter.domain.CsCallSegment;
import com.forgeagent.callcenter.domain.CsCallSeatState;
import com.forgeagent.callcenter.domain.CsCallbackTask;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
    public R<List<CsCall>> list(@RequestParam(required = false) String status,
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
        return R.ok(callMapper.selectList(wrapper));
    }

    /** 通话详情:主记录 + 事件轨迹 + 转写时间轴,一次返回供详情抽屉渲染。 */
    @GetMapping("/{id}")
    public R<Map<String, Object>> detail(@PathVariable Long id) {
        CsCall call = callMapper.selectById(id);
        if (call == null || !call.getWorkspaceId().equals(currentWorkspaceId())) {
            return R.fail("通话不存在或无权访问");
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("call", call);
        data.put("events", callEventMapper.selectList(new LambdaQueryWrapper<CsCallEvent>()
            .eq(CsCallEvent::getCallId, id).orderByAsc(CsCallEvent::getId)));
        data.put("segments", callSegmentMapper.selectList(new LambdaQueryWrapper<CsCallSegment>()
            .eq(CsCallSegment::getCallId, id).orderByAsc(CsCallSegment::getId)));
        return R.ok(data);
    }

    /** 电话坐席就绪态:按 AICC 工号维护,坐席-workNo 绑定关系回填后此处自动带出坐席资料。 */
    @GetMapping("/call-seats")
    public R<List<CsCallSeatState>> callSeats() {
        return R.ok(seatStateMapper.selectList(new LambdaQueryWrapper<CsCallSeatState>()
            .orderByDesc(CsCallSeatState::getUpdateTime).last("limit 100")));
    }

    /** 当日通话指标:列表页顶部统计卡片直读;月度对账报表后续按同一 service 扩展。 */
    @GetMapping("/stats")
    public R<CsCallMetricDaily> stats() {
        return R.ok(metricService.today(currentWorkspaceId()));
    }

    /** 录音短期签名访问 + RECORDING_ACCESS 审计;失败信息不区分"未就绪"与"已清理"。 */
    @GetMapping("/{id}/recording")
    public R<Map<String, Object>> recording(@PathVariable Long id) {
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
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("url", url);
        data.put("ossKey", call.getRecordingOssKey());
        data.put("expireSeconds", ttl);
        return R.ok(data);
    }

    /**
     * 通话画像(C8):有则直读,无则按洞察/质检现算一份(历史通话同样可看)。
     */
    @GetMapping("/{id}/profile")
    public R<CsCallProfile> profile(@PathVariable Long id) {
        CsCall call = callMapper.selectById(id);
        if (call == null || !call.getWorkspaceId().equals(currentWorkspaceId())) {
            return R.fail("通话不存在或无权访问");
        }
        CsCallProfile existing = profileMapper.selectOne(new LambdaQueryWrapper<CsCallProfile>()
            .eq(CsCallProfile::getCallId, id).last("limit 1"));
        return existing != null ? R.ok(existing) : R.ok(profileService.build(call));
    }

    /** 呼损回拨清单(C8):默认全部状态,可按状态过滤。 */
    @GetMapping("/callbacks")
    public R<List<CsCallbackTask>> callbacks(@RequestParam(required = false) String status) {
        return R.ok(callbackTaskService.list(currentWorkspaceId(), status));
    }

    /** 回拨单状态流转(C8):非法流转返回可读原因,不抛 500。 */
    @PostMapping("/callbacks/{id}/status")
    public R<CsCallbackTask> markCallback(@PathVariable Long id,
                                          @RequestParam String status,
                                          @RequestParam(required = false) String remark) {
        CsCallbackTask task = callbackTaskMapper.selectById(id);
        if (task == null || !task.getWorkspaceId().equals(currentWorkspaceId())) {
            return R.fail("回拨单不存在或无权访问");
        }
        try {
            return R.ok(callbackTaskService.mark(id, status, LoginHelper.getUserId(), remark));
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
}
