package com.forgeagent.callcenter.provider;

import lombok.extern.slf4j.Slf4j;

/**
 * 号码扩展 noop 实现(V1,D8):主被叫号码由回调事件带入,不依赖号码池。
 * 需要真实号码时返回空并给出明确提示,不做静默假装。
 */
@Slf4j
public class NoopNumberProvider implements NumberProvider {

    @Override
    public String allocate(String channelCode) {
        log.info("[CC] NoopNumberProvider: 暂无号码资源,渠道 {} 的号码由回调事件带入", channelCode);
        return null;
    }

    @Override
    public void release(String number) {
        // noop:无号码池,无需归还
    }

    @Override
    public String describe(String number) {
        return "";
    }
}
