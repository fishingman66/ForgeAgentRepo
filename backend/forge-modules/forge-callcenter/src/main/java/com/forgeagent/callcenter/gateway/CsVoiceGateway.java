package com.forgeagent.callcenter.gateway;

import java.util.List;

/**
 * 语音供应商网关(V1,中立适配层):验签、报文解析、录音取回三个厂商差异点全部收口在此。
 *
 * <p>业务层与状态机只依赖本接口与 {@link CsVoiceEvent};
 * 换真实供应商(V0-real)只换实现,不动业务断言(方案 13.7)。</p>
 */
public interface CsVoiceGateway {

    /**
     * 本实现服务的供应商标识(URL provider 段)。调度器按此路由到唯一实现;
     * 一个实现可服务多个标识(如 AICC 与 AICC_MOCK 共用字段映射)。
     */
    default boolean supports(String provider) {
        return false;
    }

    /**
     * 验签。实现必须使用常量时间比较,失败原因不区分,避免泄露渠道是否存在。
     */
    boolean verify(String secret, String timestamp, String nonce, String rawBody, String signature);

    /**
     * 解析回调报文为归一化事件列表;未知事件类型跳过(记 warn),不抛异常。
     */
    List<CsVoiceEvent> parse(String provider, String channelCode, String rawBody);

    /**
     * 按录音地址取回录音字节(AICC_MOCK 下返回固定 WAV,不发网络请求);
     * 真实供应商实现放 V0-real。
     */
    byte[] fetchRecording(String recordingUrl);
}
