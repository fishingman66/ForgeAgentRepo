package com.forgeagent.callcenter.controller;

import com.forgeagent.callcenter.domain.CsCallChannelConfig;
import com.forgeagent.callcenter.domain.CsCallSeatState;
import com.forgeagent.callcenter.domain.bo.CsCallChannelConfigBo;
import com.forgeagent.callcenter.domain.bo.CsCallChannelStatusBo;
import com.forgeagent.callcenter.domain.bo.CsCallSeatBindingBo;
import com.forgeagent.callcenter.domain.vo.CsCallChannelConfigVo;
import com.forgeagent.callcenter.mapper.CsCallChannelConfigMapper;
import com.forgeagent.callcenter.mapper.CsCallSeatStateMapper;
import com.forgeagent.callcenter.service.EnterpriseQuotaService;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 语音运营维护端点单测(P3 收尾):渠道 upsert 双向、provider 白名单、密钥不回显、
 * 坐席绑定 upsert。mapper 全 mock,不起 Spring 容器。
 */
@Tag("dev")
class CsVoiceOpsControllerTest {

    private final CsCallChannelConfigMapper channelMapper = mock(CsCallChannelConfigMapper.class);
    private final CsCallSeatStateMapper seatStateMapper = mock(CsCallSeatStateMapper.class);
    private final EnterpriseQuotaService quotaService = mock(EnterpriseQuotaService.class);
    private final CsVoiceOpsController controller = new CsVoiceOpsController(channelMapper, seatStateMapper, quotaService);

    private CsCallChannelConfig channel(String code, String provider) {
        CsCallChannelConfig c = new CsCallChannelConfig();
        c.setChannelCode(code);
        c.setMediaProvider(provider);
        c.setWorkspaceId(1L);
        c.setAgentId(1L);
        c.setStatus("0");
        return c;
    }

    private CsCallChannelConfigBo channelBo(String code, String provider) {
        CsCallChannelConfigBo bo = new CsCallChannelConfigBo();
        bo.setChannelCode(code);
        bo.setMediaProvider(provider);
        bo.setWorkspaceId(1L);
        bo.setAgentId(1L);
        bo.setStatus("0");
        return bo;
    }

    private CsCallChannelStatusBo statusBo(String status) {
        CsCallChannelStatusBo bo = new CsCallChannelStatusBo();
        bo.setStatus(status);
        return bo;
    }

    @Test
    void upsertInsertsWhenAbsent() {
        when(channelMapper.selectOne(any())).thenReturn(null);
        CsCallChannelConfigBo body = channelBo("CC_FS", "FS");

        var result = controller.upsertVoiceChannel(body);

        assertTrue(result.getCode() == 200, "upsert 成功");
        ArgumentCaptor<CsCallChannelConfig> cap = ArgumentCaptor.forClass(CsCallChannelConfig.class);
        verify(channelMapper).insert(cap.capture());
        assertNull(cap.getValue().getId(), "新增不应携带前端传入 id");
        assertNull(cap.getValue().getCallbackSecret(), "入口不写密钥");
        assertEquals("FS", cap.getValue().getMediaProvider(), "入参必须落到实体");
    }

    @Test
    void upsertUpdatesWhenExists() {
        CsCallChannelConfig existing = channel("CC_FS", "FS");
        existing.setId(9L);
        when(channelMapper.selectOne(any())).thenReturn(existing);

        CsCallChannelConfigBo body = channelBo("CC_FS", "FS_MOCK");
        var result = controller.upsertVoiceChannel(body);

        assertTrue(result.getCode() == 200);
        verify(channelMapper, never()).insert(any(CsCallChannelConfig.class));
        verify(channelMapper).updateById(existing);
        assertEquals("FS_MOCK", existing.getMediaProvider());
    }

    @Test
    void upsertRejectsInvalidProviderAndMissingCode() {
        CsCallChannelConfigBo badProvider = channelBo("CC_FS", "NOT_EXIST");
        assertTrue(controller.upsertVoiceChannel(badProvider).getCode() != 200);
        CsCallChannelConfigBo noCode = channelBo(null, "FS");
        assertTrue(controller.upsertVoiceChannel(noCode).getCode() != 200);
        verify(channelMapper, never()).insert(any(CsCallChannelConfig.class));
    }

    @Test
    void voiceChannelsMaskSecret() {
        CsCallChannelConfig withSecret = channel("CC_FS", "FS");
        withSecret.setCallbackSecret("cipher-text");
        when(channelMapper.selectList(any())).thenReturn(List.of(withSecret, channel("A1", "AICC")));

        List<CsCallChannelConfigVo> rows = controller.voiceChannels().getData();
        assertEquals("__SET__", rows.get(0).getCallbackSecret(), "已配置密钥只回显置位标记");
        assertNull(rows.get(1).getCallbackSecret());
    }

    @Test
    void toggleValidatesStatusValue() {
        assertTrue(controller.toggleVoiceChannel(1L, statusBo("9")).getCode() != 200);
        CsCallChannelConfig channel = channel("CC_FS", "FS");
        channel.setId(1L);
        when(channelMapper.selectById(1L)).thenReturn(channel);
        assertTrue(controller.toggleVoiceChannel(1L, statusBo("1")).getCode() == 200);
        assertEquals("1", channel.getStatus());
    }

    @Test
    void bindSeatInsertsAndUpdates() {
        when(seatStateMapper.selectOne(any())).thenReturn(null);
        CsCallSeatBindingBo bind = new CsCallSeatBindingBo();
        bind.setWorkNo("1001");
        bind.setExtension("1001");
        assertTrue(controller.bindSeat(bind).getCode() == 200);
        ArgumentCaptor<CsCallSeatState> inserted = ArgumentCaptor.forClass(CsCallSeatState.class);
        verify(seatStateMapper).insert(inserted.capture());
        assertEquals("OFFLINE", inserted.getValue().getReadyState(), "新坐席默认 OFFLINE");
        assertEquals("1001", inserted.getValue().getExtension());

        CsCallSeatState existing = new CsCallSeatState();
        existing.setId(7L);
        existing.setWorkNo("1001");
        when(seatStateMapper.selectOne(any())).thenReturn(existing);
        CsCallSeatBindingBo update = new CsCallSeatBindingBo();
        update.setWorkNo("1001");
        update.setExtension("5001");
        assertTrue(controller.bindSeat(update).getCode() == 200);
        assertEquals("5001", existing.getExtension());
        verify(seatStateMapper).updateById(existing);
    }

    @Test
    void bindSeatRequiresWorkNo() {
        CsCallSeatBindingBo noWorkNo = new CsCallSeatBindingBo();
        assertTrue(controller.bindSeat(noWorkNo).getCode() != 200);
        verify(seatStateMapper, never()).insert(any(CsCallSeatState.class));
    }

    @Test
    void quotaDisabledKeepsLegacyBehavior() {
        // 默认 enabled=false:既有 upsert/bindSeat 行为不变,不触发配额查询
        when(channelMapper.selectOne(any())).thenReturn(null);
        assertTrue(controller.upsertVoiceChannel(channelBo("CC_NEW", "FS")).getCode() == 200);
        verify(channelMapper, never()).selectCount(any());

        CsCallSeatBindingBo bind = new CsCallSeatBindingBo();
        bind.setWorkNo("2002");
        bind.setExtension("2002");
        bind.setWorkspaceId(1L);
        when(seatStateMapper.selectOne(any())).thenReturn(null);
        assertTrue(controller.bindSeat(bind).getCode() == 200);
        verify(seatStateMapper, never()).selectCount(any());
    }

    @Test
    void quotaEnabledRejectsSeatAndChannelWhenFull() {
        when(quotaService.enabled()).thenReturn(true);
        when(quotaService.checkExpiry(any())).thenReturn(EnterpriseQuotaService.Decision.allow(-1, -1));
        when(quotaService.checkChannel(any(), org.mockito.ArgumentMatchers.anyLong()))
            .thenReturn(EnterpriseQuotaService.Decision.deny("渠道满", 1L, 1L));
        when(channelMapper.selectOne(any())).thenReturn(null);
        assertTrue(controller.upsertVoiceChannel(channelBo("CC_NEW", "FS")).getCode() != 200,
            "渠道配额满时拒绝新增");
        verify(channelMapper, never()).insert(any(CsCallChannelConfig.class));

        when(quotaService.checkSeat(any(), org.mockito.ArgumentMatchers.anyLong()))
            .thenReturn(EnterpriseQuotaService.Decision.deny("坐席满", 1L, 1L));
        when(seatStateMapper.selectOne(any())).thenReturn(null);
        CsCallSeatBindingBo bind = new CsCallSeatBindingBo();
        bind.setWorkNo("3003");
        bind.setWorkspaceId(1L);
        assertTrue(controller.bindSeat(bind).getCode() != 200, "坐席配额满时拒绝新增");
        verify(seatStateMapper, never()).insert(any(CsCallSeatState.class));
    }
}
