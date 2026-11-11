package com.forgeagent.callcenter.provider;

import lombok.extern.slf4j.Slf4j;

/**
 * 中继扩展 noop 实现(V1,D8):暂无 SIP 中继,ACD 按 workNo 关联,不按分机硬绑定。
 */
@Slf4j
public class NoopTrunkProvider implements TrunkProvider {

    @Override
    public void bind(String number, String trunkRef) {
        log.info("[CC] NoopTrunkProvider: 暂无中继,忽略绑定 {} <-> {}", number, trunkRef);
    }

    @Override
    public String status() {
        return "noop: 暂无 SIP 中继(D8)";
    }
}
