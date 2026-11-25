package com.forgeagent.callcenter.config;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * prod 校验器扩展契约测试(P0~P2 新增策略门禁,方案总验收标准第3条)。
 */
@Tag("dev")
class CallcenterProductionReadinessValidatorTest {

    /** dev 环境:CELL 开关即使配置不当也只告警不阻断(本地开箱可用)。 */
    private CallcenterProductionReadinessValidator validator(CallcenterProperties props, boolean prod) {
        Environment env = mock(Environment.class);
        when(env.getActiveProfiles()).thenReturn(prod ? new String[]{"prod"} : new String[]{"dev"});
        when(env.getProperty("link.thingscloud.freeswitch.esl.inbound.default-password"))
            .thenReturn("changed-password");
        return new CallcenterProductionReadinessValidator(props, env);
    }

    private CallcenterProperties base() {
        CallcenterProperties p = new CallcenterProperties();
        p.setMockEnabled(false); // 既有 V1 门禁:prod 下 mock 必须关
        p.setDevScript(false);   // 既有 V1 门禁:prod 下开发剧本必须关
        p.setCallbackSecret("a-valid-production-secret-2026"); // 长度 >=16
        p.setProvider("FS");
        CallcenterProperties.FreeswitchProperties fs = new CallcenterProperties.FreeswitchProperties();
        fs.setChannelCode("cc_freetest");
        fs.setQueueExtensions("sales:5000,support:5001");
        p.setFreeswitch(fs);
        return p;
    }

    @Test
    void baseConfigPassesProd() {
        assertDoesNotThrow(() -> validator(base(), true).validate());
    }

    @Test
    void enterpriseQuotaLowerBoundEnforced() {
        CallcenterProperties p = base();
        p.setEnterpriseEnabled(true);
        // 默认 max-seats=30 等均为正 → 通过
        assertDoesNotThrow(() -> validator(p, true).validate());

        p.setMaxSeats(0); // 配额下限:0 会让门禁恒真
        assertThrows(IllegalStateException.class, () -> validator(p, true).validate());
        // dev 放行
        assertDoesNotThrow(() -> validator(p, false).validate());
    }

    @Test
    void overflowQueueExtensionFormatValidated() {
        CallcenterProperties p = base();
        p.setOverflowEnabled(true);
        assertDoesNotThrow(() -> validator(p, true).validate());

        p.getFreeswitch().setQueueExtensions("sales:5000,broken"); // 第二段缺 :ext
        assertFalse(CallcenterProductionReadinessValidator.validQueueExtensions(
            p.getFreeswitch().getQueueExtensions()));
        assertThrows(IllegalStateException.class, () -> validator(p, true).validate());

        // 空映射合法(参考 FsChannelControl.resolveQueueExtension 把队列名当分机号)
        assertTrue(CallcenterProductionReadinessValidator.validQueueExtensions(null));
        assertTrue(CallcenterProductionReadinessValidator.validQueueExtensions(""));
    }

    @Test
    void didRouteWithDbProviderBlockedByFrRedline() {
        CallcenterProperties p = base();
        p.setDidRouteEnabled(true);
        p.setNumberProvider("db"); // 资质未到位却开 db provider
        assertThrows(IllegalStateException.class, () -> validator(p, true).validate());
        assertDoesNotThrow(() -> validator(p, false).validate());

        p.setNumberProvider("noop");
        assertDoesNotThrow(() -> validator(p, true).validate());
    }

    @Test
    void blacklistWithDbTrunkBlocked() {
        CallcenterProperties p = base();
        p.setBlacklistEnabled(true);
        p.setTrunkProvider("db");
        assertThrows(IllegalStateException.class, () -> validator(p, true).validate());
    }
}
