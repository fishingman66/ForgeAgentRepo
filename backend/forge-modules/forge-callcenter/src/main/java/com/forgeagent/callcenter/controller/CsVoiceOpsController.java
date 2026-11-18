package com.forgeagent.callcenter.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.forgeagent.callcenter.config.ConditionalOnCallcenterEnabled;
import com.forgeagent.callcenter.domain.CsCallChannelConfig;
import com.forgeagent.callcenter.domain.CsCallSeatState;
import com.forgeagent.callcenter.gateway.VoiceProvider;
import com.forgeagent.callcenter.mapper.CsCallChannelConfigMapper;
import com.forgeagent.callcenter.mapper.CsCallSeatStateMapper;
import com.forgeagent.common.core.domain.R;
import com.forgeagent.common.web.core.BaseController;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * 语音运营维护端点(P3 收尾):此前渠道记录与坐席-分机绑定只能 SQL 维护,
 * 本控制器补齐运营侧入口——语音渠道 upsert/启停、坐席 workNo→分机绑定。
 *
 * <p>安全边界:类级 @SaCheckLogin;callbackSecret 不在本入口暴露(继续走配置加密,
 * 由 SQL/初始化维护);mediaProvider 白名单校验防脏数据;
 * ESL 事件按 channelCode 路由,改编码前须确认 FS 侧配置同步。</p>
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@SaCheckLogin
@ConditionalOnCallcenterEnabled
@RequestMapping("/agent/customerservice/cc-ops")
public class CsVoiceOpsController extends BaseController {

    private final CsCallChannelConfigMapper channelMapper;
    private final CsCallSeatStateMapper seatStateMapper;

    /** 语音渠道列表(全量,量小;密钥等敏感列不下发)。 */
    @GetMapping("/voice-channels")
    public R<List<CsCallChannelConfig>> voiceChannels() {
        List<CsCallChannelConfig> rows = channelMapper.selectList(
            new LambdaQueryWrapper<CsCallChannelConfig>().orderByAsc(CsCallChannelConfig::getId));
        rows.forEach(row -> {
            // 密钥不回显:清空与置位标记二选一,前端只展示「已配置/未配置」
            row.setCallbackSecret(row.getCallbackSecret() == null || row.getCallbackSecret().isBlank()
                ? null : "__SET__");
            row.setVoiceConfigJson(null);
        });
        return R.ok(rows);
    }

    /**
     * 语音渠道 upsert(channelCode 唯一键):新增或更新基础字段;
     * callbackSecret/voiceConfigJson 不经本入口修改。
     */
    @PostMapping("/voice-channels")
    public R<Void> upsertVoiceChannel(@RequestBody CsCallChannelConfig body) {
        if (body.getChannelCode() == null || body.getChannelCode().isBlank()) {
            return R.fail("channelCode 不能为空");
        }
        if (VoiceProvider.fromCode(body.getMediaProvider()) == null) {
            return R.fail("mediaProvider 非法: " + body.getMediaProvider());
        }
        if (body.getWorkspaceId() == null || body.getAgentId() == null) {
            return R.fail("workspaceId / agentId 不能为空");
        }
        Date now = new Date();
        CsCallChannelConfig existing = channelMapper.selectOne(new LambdaQueryWrapper<CsCallChannelConfig>()
            .eq(CsCallChannelConfig::getChannelCode, body.getChannelCode()).last("limit 1"));
        if (existing == null) {
            body.setId(null);
            body.setCallbackSecret(null);
            body.setVoiceConfigJson(null);
            body.setCreateTime(now);
            body.setUpdateTime(now);
            channelMapper.insert(body);
            log.info("[CC-OPS] 语音渠道已创建 code={} provider={}", body.getChannelCode(), body.getMediaProvider());
        } else {
            existing.setMediaProvider(body.getMediaProvider());
            existing.setServiceNumber(body.getServiceNumber());
            existing.setWorkspaceId(body.getWorkspaceId());
            existing.setAgentId(body.getAgentId());
            existing.setStatus(body.getStatus() == null ? existing.getStatus() : body.getStatus());
            existing.setUpdateTime(now);
            channelMapper.updateById(existing);
            log.info("[CC-OPS] 语音渠道已更新 code={} provider={} status={}",
                existing.getChannelCode(), existing.getMediaProvider(), existing.getStatus());
        }
        return R.ok();
    }

    /** 渠道启停(0 启用 / 1 停用);停用后 ESL 事件按「渠道不可用」拒绝。 */
    @PostMapping("/voice-channels/{id}/status")
    public R<Void> toggleVoiceChannel(@PathVariable Long id, @RequestBody Map<String, String> body) {
        String status = body.get("status");
        if (!"0".equals(status) && !"1".equals(status)) {
            return R.fail("status 仅允许 0(启用)/1(停用)");
        }
        CsCallChannelConfig channel = channelMapper.selectById(id);
        if (channel == null) {
            return R.fail("渠道不存在");
        }
        channel.setStatus(status);
        channel.setUpdateTime(new Date());
        channelMapper.updateById(channel);
        return R.ok();
    }

    /**
     * 坐席绑定 upsert(workNo 业务唯一键):维护 workNo→分机映射;
     * seatId 可选(绑文本域坐席后,坐席侧展示与路由可联动)。
     */
    @PostMapping("/seat-bindings")
    public R<Void> bindSeat(@RequestBody CsCallSeatState body) {
        if (body.getWorkNo() == null || body.getWorkNo().isBlank()) {
            return R.fail("workNo 不能为空");
        }
        if (body.getExtension() != null && body.getExtension().length() > 32) {
            return R.fail("extension 过长");
        }
        Date now = new Date();
        CsCallSeatState existing = seatStateMapper.selectOne(new LambdaQueryWrapper<CsCallSeatState>()
            .eq(CsCallSeatState::getWorkNo, body.getWorkNo()).last("limit 1"));
        if (existing == null) {
            body.setId(null);
            if (body.getReadyState() == null || body.getReadyState().isBlank()) {
                body.setReadyState("OFFLINE");
            }
            body.setCreateTime(now);
            body.setUpdateTime(now);
            seatStateMapper.insert(body);
        } else {
            existing.setExtension(body.getExtension());
            existing.setSeatId(body.getSeatId());
            existing.setWorkspaceId(body.getWorkspaceId() == null ? existing.getWorkspaceId() : body.getWorkspaceId());
            existing.setUpdateTime(now);
            seatStateMapper.updateById(existing);
        }
        log.info("[CC-OPS] 坐席绑定已保存 workNo={} extension={}", body.getWorkNo(), body.getExtension());
        return R.ok();
    }
}
