package com.forgeagent.callcenter.storage;

import java.time.Duration;

/**
 * 录音存储抽象(V1,D6):入库 / 清理 / 签名访问三个动作统一收口。
 *
 * <p>生产走 {@code oss}(forge-common-oss,sys_oss_config);开发环境无真实 OSS 时
 * 切 {@code local-fs}(本地文件系统 + HMAC 签名下载 URL),只改
 * {@code agent.callcenter.recording-store} 一个配置,业务代码零改动。</p>
 */
public interface RecordingStorage {

    /** 保存对象字节;objectKey 由调用方按 callcenter/recording/{callId}/{recordingId}.wav 生成。 */
    void save(String objectKey, byte[] data);

    /** 删除对象;对象不存在时返回 false 而不抛异常。 */
    boolean delete(String objectKey);

    /** 短期签名访问 URL;不向调用方暴露任何存储凭据。 */
    String presignReadUrl(String objectKey, Duration ttl);
}
