package com.forgeagent.callcenter.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.forgeagent.callcenter.config.ConditionalOnCallcenterEnabled;
import com.forgeagent.callcenter.domain.CsCallChannelConfig;
import com.forgeagent.callcenter.domain.CsCallSeatState;
import com.forgeagent.callcenter.domain.bo.CsCallChannelConfigBo;
import com.forgeagent.callcenter.domain.bo.CsCallChannelStatusBo;
import com.forgeagent.callcenter.domain.bo.CsCallSeatBindingBo;
import com.forgeagent.callcenter.domain.vo.CsCallChannelConfigVo;
import com.forgeagent.callcenter.gateway.VoiceProvider;
import com.forgeagent.callcenter.mapper.CsCallChannelConfigMapper;
import com.forgeagent.callcenter.mapper.CsCallSeatStateMapper;
import com.forgeagent.common.core.domain.R;
import com.forgeagent.common.core.validate.AddGroup;
import com.forgeagent.common.web.core.BaseController;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Date;
import java.util.List;

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
    public R<List<CsCallChannelConfigVo>> voiceChannels() {
        List<CsCallChannelConfig> rows = channelMapper.selectList(
            new LambdaQueryWrapper<CsCallChannelConfig>().orderByAsc(CsCallChannelConfig::getId));
        return R.ok(rows.stream().map(this::toChannelVo).toList());
    }

    /**
     * 语音渠道 upsert(channelCode 唯一键):新增或更新基础字段;
     * callbackSecret/voiceConfigJson 不经本入口修改。
     */
    @PostMapping("/voice-channels")
    public R<Void> upsertVoiceChannel(@Validated(AddGroup.class) @RequestBody CsCallChannelConfigBo bo) {
        if (bo.getChannelCode() == null || bo.getChannelCode().isBlank()) {
            return R.fail("channelCode 不能为空");
        }
        if (VoiceProvider.fromCode(bo.getMediaProvider()) == null) {
            return R.fail("mediaProvider 非法: " + bo.getMediaProvider());
        }
        if (bo.getWorkspaceId() == null || bo.getAgentId() == null) {
            return R.fail("workspaceId / agentId 不能为空");
        }
        Date now = new Date();
        CsCallChannelConfig existing = channelMapper.selectOne(new LambdaQueryWrapper<CsCallChannelConfig>()
            .eq(CsCallChannelConfig::getChannelCode, bo.getChannelCode()).last("limit 1"));
        if (existing == null) {
            CsCallChannelConfig row = new CsCallChannelConfig();
            // 新增不从入参取 id:前端可能把编辑态 id 一起带过来
            row.setChannelCode(bo.getChannelCode());
            row.setMediaProvider(bo.getMediaProvider());
            row.setServiceNumber(bo.getServiceNumber());
            row.setWorkspaceId(bo.getWorkspaceId());
            row.setAgentId(bo.getAgentId());
            row.setStatus(bo.getStatus());
            row.setCallbackSecret(null);
            row.setVoiceConfigJson(null);
            row.setCreateTime(now);
            row.setUpdateTime(now);
            channelMapper.insert(row);
            log.info("[CC-OPS] 语音渠道已创建 code={} provider={}", row.getChannelCode(), row.getMediaProvider());
        } else {
            existing.setMediaProvider(bo.getMediaProvider());
            existing.setServiceNumber(bo.getServiceNumber());
            existing.setWorkspaceId(bo.getWorkspaceId());
            existing.setAgentId(bo.getAgentId());
            existing.setStatus(bo.getStatus() == null ? existing.getStatus() : bo.getStatus());
            existing.setUpdateTime(now);
            channelMapper.updateById(existing);
            log.info("[CC-OPS] 语音渠道已更新 code={} provider={} status={}",
                existing.getChannelCode(), existing.getMediaProvider(), existing.getStatus());
        }
        return R.ok();
    }

    /** 渠道启停(0 启用 / 1 停用);停用后 ESL 事件按「渠道不可用」拒绝。 */
    @PostMapping("/voice-channels/{id}/status")
    public R<Void> toggleVoiceChannel(@PathVariable Long id,
                                      @Validated @RequestBody CsCallChannelStatusBo bo) {
        String status = bo.getStatus();
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
    public R<Void> bindSeat(@Validated(AddGroup.class) @RequestBody CsCallSeatBindingBo bo) {
        if (bo.getWorkNo() == null || bo.getWorkNo().isBlank()) {
            return R.fail("workNo 不能为空");
        }
        if (bo.getExtension() != null && bo.getExtension().length() > 32) {
            return R.fail("extension 过长");
        }
        Date now = new Date();
        CsCallSeatState existing = seatStateMapper.selectOne(new LambdaQueryWrapper<CsCallSeatState>()
            .eq(CsCallSeatState::getWorkNo, bo.getWorkNo()).last("limit 1"));
        if (existing == null) {
            CsCallSeatState row = new CsCallSeatState();
            row.setWorkNo(bo.getWorkNo());
            row.setExtension(bo.getExtension());
            row.setSeatId(bo.getSeatId());
            row.setWorkspaceId(bo.getWorkspaceId());
            row.setReadyState("OFFLINE");
            row.setCreateTime(now);
            row.setUpdateTime(now);
            seatStateMapper.insert(row);
        } else {
            existing.setExtension(bo.getExtension());
            existing.setSeatId(bo.getSeatId());
            existing.setWorkspaceId(bo.getWorkspaceId() == null ? existing.getWorkspaceId() : bo.getWorkspaceId());
            existing.setUpdateTime(now);
            seatStateMapper.updateById(existing);
        }
        log.info("[CC-OPS] 坐席绑定已保存 workNo={} extension={}", bo.getWorkNo(), bo.getExtension());
        return R.ok();
    }

    /**
     * 渠道实体 → Vo。密钥不回显:清空与置位标记二选一,前端只展示「已配置/未配置」;
     * voiceConfigJson 属接入细节,一律不下发。
     */
    private CsCallChannelConfigVo toChannelVo(CsCallChannelConfig row) {
        if (row == null) {
            return null;
        }
        CsCallChannelConfigVo vo = new CsCallChannelConfigVo();
        vo.setId(row.getId());
        vo.setChannelCode(row.getChannelCode());
        vo.setMediaProvider(row.getMediaProvider());
        vo.setServiceNumber(row.getServiceNumber());
        vo.setWorkspaceId(row.getWorkspaceId());
        vo.setAgentId(row.getAgentId());
        vo.setCallbackSecret(row.getCallbackSecret() == null || row.getCallbackSecret().isBlank()
            ? null : "__SET__");
        vo.setRateLimit(row.getRateLimit());
        vo.setStatus(row.getStatus());
        vo.setCreateTime(row.getCreateTime());
        vo.setUpdateTime(row.getUpdateTime());
        return vo;
    }
}
