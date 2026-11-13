package com.forgeagent.callcenter.gateway;

import com.forgeagent.callcenter.config.ConditionalOnCallcenterEnabled;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * FreeSWITCH Mock 网关(C1 / 方案 docs/07 §三.4,V-5):FS_MOCK 供应商标识的事件源实现。
 *
 * <p>验签沿用 AICC 同一 HMAC 算法(内网共享密钥);报文为 ESL 风格事件序列
 * ({"events": [...]}),归一化走 {@link FsEventNormalizer}——与 C1-real 的
 * FsGateway 真实现共用同一映射表,Mock 只替换数据源(fixture 回放 vs ESL 连接)。</p>
 *
 * <p>仅 dev/test 生效:prod 下 provider=FS_MOCK 由就绪校验器拒绝启动;
 * 录音取回复用 AICC Mock 的固定 WAV 生成(同规格 16044 字节)。</p>
 */
@Slf4j
@Component
@ConditionalOnCallcenterEnabled
public class FsMockGateway implements CsVoiceGateway {

    @Override
    public boolean supports(String provider) {
        // 仅服务 FS_MOCK;FS 标识需要 C1-real 的真实 FsGateway,未部署时路由失败并拒绝
        return VoiceProvider.FS_MOCK.code().equalsIgnoreCase(provider);
    }

    @Override
    public boolean verify(String secret, String timestamp, String nonce, String rawBody, String signature) {
        return CsHmacSignatures.verify(secret, timestamp, nonce, rawBody, signature);
    }

    @Override
    public List<CsVoiceEvent> parse(String provider, String channelCode, String rawBody) {
        return FsEventNormalizer.parse(provider, channelCode, rawBody);
    }

    @Override
    public byte[] fetchRecording(String recordingUrl) {
        if (recordingUrl == null || !recordingUrl.startsWith("mock://")) {
            throw new UnsupportedOperationException(
                "FS 真实录音读取属 C1-real 范围(本地文件/OSS),当前仅支持 mock:// 录音地址: " + recordingUrl);
        }
        return AiccGateway.mockRecordingBytes();
    }
}
