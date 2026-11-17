package com.forgeagent.callcenter.gateway;

import com.forgeagent.callcenter.config.CallcenterProperties;
import com.forgeagent.callcenter.config.ConditionalOnCallcenterEnabled;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.List;

/**
 * FreeSWITCH 真实网关(C1-real):provider=FS 的事件源实现。
 *
 * <p>与 {@link FsMockGateway} 共用 {@link FsEventNormalizer} 唯一映射表——
 * Mock 从 fixture 读 ESL 风格事件,本类从真实 ESL 连接读(由 FsEslEventHandler
 * 把 EslEvent 转成同格式报文后交给本类 parse),归一化层零差异。</p>
 *
 * <p>验签口径:ESL 是内网长连接,认证由 starter 在建连时用 ESL 密码完成
 * ({@code link.thingscloud.freeswitch.esl.inbound.servers[*].password}),
 * 不复用 HTTP 回调的 HMAC。{@link #verify} 因此返回 true,仅供接口契约完整;
 * 真实事件入口走 {@code CallEventDispatcher.dispatchTrusted} 跳过 HMAC 三关。</p>
 *
 * <p>录音取回:FS 的 {@code uuid_record} / record_session 把 wav 落在云主机本地,
 * recordingUrl 即本地文件路径(或 file:// 前缀);OSS 场景交给既有 RecordingStorage。
 * 路径做白名单校验,禁止目录穿越。</p>
 */
@Slf4j
@Component
@ConditionalOnCallcenterEnabled
@RequiredArgsConstructor
public class FsGateway implements CsVoiceGateway {

    private final CallcenterProperties properties;

    @Override
    public boolean supports(String provider) {
        return VoiceProvider.FS.code().equalsIgnoreCase(provider);
    }

    /**
     * ESL 连接级认证已由 starter 完成,此处不重复 HMAC 校验。
     * 真实事件入口走 dispatchTrusted,本方法不会被那条链路调用。
     */
    @Override
    public boolean verify(String secret, String timestamp, String nonce, String rawBody, String signature) {
        return true;
    }

    @Override
    public List<CsVoiceEvent> parse(String provider, String channelCode, String rawBody) {
        return FsEventNormalizer.parse(provider, channelCode, rawBody);
    }

    /**
     * 取回 FS 本地录音字节。支持三种地址:
     * <ul>
     *   <li>{@code file:///abs/path.wav} 或裸绝对路径 —— 本地文件系统</li>
     *   <li>{@code mock://...} —— 兼容 FS_MOCK 测试,复用固定 WAV</li>
     * </ul>
     * 本地路径限定在 {@code agent.callcenter.freeswitch.recording-dir} 之下,防目录穿越;
     * 未配置目录时按绝对路径读取(部署期由校验器要求显式配置)。
     */
    @Override
    public byte[] fetchRecording(String recordingUrl) {
        if (recordingUrl == null || recordingUrl.isBlank()) {
            throw new IllegalArgumentException("录音地址为空");
        }
        if (recordingUrl.startsWith("mock://")) {
            return AiccGateway.mockRecordingBytes();
        }
        String pathPart = recordingUrl.startsWith("file://")
            ? recordingUrl.substring("file://".length()) : recordingUrl;
        try {
            Path target = Path.of(pathPart).toAbsolutePath().normalize();
            String baseDir = properties.getFreeswitch() == null ? null
                : properties.getFreeswitch().getRecordingDir();
            if (baseDir != null && !baseDir.isBlank()) {
                Path base = Path.of(baseDir).toAbsolutePath().normalize();
                if (!target.startsWith(base)) {
                    throw new IllegalArgumentException("录音路径越界: " + recordingUrl);
                }
            }
            if (!Files.isRegularFile(target)) {
                throw new IOException("录音文件不存在: " + target);
            }
            return Files.readAllBytes(target);
        } catch (InvalidPathException e) {
            throw new IllegalArgumentException("非法录音路径: " + recordingUrl, e);
        } catch (IOException e) {
            throw new IllegalStateException("读取录音失败: " + recordingUrl, e);
        }
    }
}
