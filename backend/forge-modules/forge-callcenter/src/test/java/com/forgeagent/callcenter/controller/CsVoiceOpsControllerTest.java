package com.forgeagent.callcenter.controller;

import com.forgeagent.callcenter.domain.CsCallChannelConfig;
import com.forgeagent.callcenter.domain.CsCallSeatState;
import com.forgeagent.callcenter.mapper.CsCallChannelConfigMapper;
import com.forgeagent.callcenter.mapper.CsCallSeatStateMapper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

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
    private final CsVoiceOpsController controller = new CsVoiceOpsController(channelMapper, seatStateMapper);

    private CsCallChannelConfig channel(String code, String provider) {
        CsCallChannelConfig c = new CsCallChannelConfig();
        c.setChannelCode(code);
        c.setMediaProvider(provider);
        c.setWorkspaceId(1L);
        c.setAgentId(1L);
        c.setStatus("0");
        return c;
    }

    @Test
    void upsertInsertsWhenAbsent() {
        when(channelMapper.selectOne(any())).thenReturn(null);
        CsCallChannelConfig body = channel("CC_FS", "FS");

        var result = controller.upsertVoiceChannel(body);

        assertTrue(result.getCode() == 200, "upsert 成功");
        ArgumentCaptor<CsCallChannelConfig> cap = ArgumentCaptor.forClass(CsCallChannelConfig.class);
        verify(channelMapper).insert(cap.capture());
        assertNull(cap.getValue().getId(), "新增不应携带前端传入 id");
        assertNull(cap.getValue().getCallbackSecret(), "入口不写密钥");
    }

    @Test
    void upsertUpdatesWhenExists() {
        CsCallChannelConfig existing = channel("CC_FS", "FS");
        existing.setId(9L);
        when(channelMapper.selectOne(any())).thenReturn(existing);

        CsCallChannelConfig body = channel("CC_FS", "FS_MOCK");
        var result = controller.upsertVoiceChannel(body);

        assertTrue(result.getCode() == 200);
        verify(channelMapper, never()).insert(any(CsCallChannelConfig.class));
        verify(channelMapper).updateById(existing);
        assertEquals("FS_MOCK", existing.getMediaProvider());
    }

    @Test
    void upsertRejectsInvalidProviderAndMissingCode() {
        CsCallChannelConfig badProvider = channel("CC_FS", "NOT_EXIST");
        assertTrue(controller.upsertVoiceChannel(badProvider).getCode() != 200);
        CsCallChannelConfig noCode = channel(null, "FS");
        assertTrue(controller.upsertVoiceChannel(noCode).getCode() != 200);
        verify(channelMapper, never()).insert(any(CsCallChannelConfig.class));
    }

    @Test
    void voiceChannelsMaskSecret() {
        CsCallChannelConfig withSecret = channel("CC_FS", "FS");
        withSecret.setCallbackSecret("cipher-text");
        when(channelMapper.selectList(any())).thenReturn(List.of(withSecret, channel("A1", "AICC")));

        List<CsCallChannelConfig> rows = controller.voiceChannels().getData();
        assertEquals("__SET__", rows.get(0).getCallbackSecret(), "已配置密钥只回显置位标记");
        assertNull(rows.get(1).getCallbackSecret());
        assertNull(rows.get(0).getVoiceConfigJson());
    }

    @Test
    void toggleValidatesStatusValue() {
        assertTrue(controller.toggleVoiceChannel(1L, Map.of("status", "9")).getCode() != 200);
        CsCallChannelConfig channel = channel("CC_FS", "FS");
        channel.setId(1L);
        when(channelMapper.selectById(1L)).thenReturn(channel);
        assertTrue(controller.toggleVoiceChannel(1L, Map.of("status", "1")).getCode() == 200);
        assertEquals("1", channel.getStatus());
    }

    @Test
    void bindSeatInsertsAndUpdates() {
        when(seatStateMapper.selectOne(any())).thenReturn(null);
        CsCallSeatState bind = new CsCallSeatState();
        bind.setWorkNo("1001");
        bind.setExtension("1001");
        assertTrue(controller.bindSeat(bind).getCode() == 200);
        verify(seatStateMapper).insert(bind);
        assertEquals("OFFLINE", bind.getReadyState(), "新坐席默认 OFFLINE");

        CsCallSeatState existing = new CsCallSeatState();
        existing.setId(7L);
        existing.setWorkNo("1001");
        when(seatStateMapper.selectOne(any())).thenReturn(existing);
        CsCallSeatState update = new CsCallSeatState();
        update.setWorkNo("1001");
        update.setExtension("5001");
        assertTrue(controller.bindSeat(update).getCode() == 200);
        assertEquals("5001", existing.getExtension());
        verify(seatStateMapper).updateById(existing);
    }

    @Test
    void bindSeatRequiresWorkNo() {
        CsCallSeatState noWorkNo = new CsCallSeatState();
        assertTrue(controller.bindSeat(noWorkNo).getCode() != 200);
        verify(seatStateMapper, never()).insert(any(CsCallSeatState.class));
    }
}
