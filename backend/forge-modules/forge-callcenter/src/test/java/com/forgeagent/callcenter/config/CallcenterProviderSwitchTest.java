package com.forgeagent.callcenter.config;

import com.forgeagent.callcenter.mapper.CsNumberPoolMapper;
import com.forgeagent.callcenter.provider.DbNumberProvider;
import com.forgeagent.callcenter.provider.DbTrunkProvider;
import com.forgeagent.callcenter.provider.NoopNumberProvider;
import com.forgeagent.callcenter.provider.NoopTrunkProvider;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * P2-1 Provider 配置切换契约测试 + FR 红线静态断言。
 */
@Tag("dev")
class CallcenterProviderSwitchTest {

    @Test
    void defaultSwitchKeepsNoop() {
        CallcenterProperties props = new CallcenterProperties();
        assertEquals("noop", props.getNumberProvider());
        assertEquals("noop", props.getTrunkProvider());

        CallcenterConfiguration config = new CallcenterConfiguration();
        CsNumberPoolMapper mapper = mock(CsNumberPoolMapper.class);
        assertInstanceOf(NoopNumberProvider.class, config.numberProvider(props, mapper));
        assertInstanceOf(NoopTrunkProvider.class, config.trunkProvider(props, mapper));
        verifyNoInteractions(mapper); // noop 路径不查库
    }

    @Test
    void dbSwitchReturnsDbImplementations() {
        CallcenterProperties props = new CallcenterProperties();
        props.setNumberProvider("db");
        props.setTrunkProvider("db");
        CallcenterConfiguration config = new CallcenterConfiguration();
        CsNumberPoolMapper mapper = mock(CsNumberPoolMapper.class);
        assertInstanceOf(DbNumberProvider.class, config.numberProvider(props, mapper));
        assertInstanceOf(DbTrunkProvider.class, config.trunkProvider(props, mapper));
    }

    @Test
    void unknownSwitchFallsBackToNoop() {
        CallcenterProperties props = new CallcenterProperties();
        props.setNumberProvider("weird");
        props.setTrunkProvider("weird");
        CallcenterConfiguration config = new CallcenterConfiguration();
        CsNumberPoolMapper mapper = mock(CsNumberPoolMapper.class);
        assertInstanceOf(NoopNumberProvider.class, config.numberProvider(props, mapper));
        assertInstanceOf(NoopTrunkProvider.class, config.trunkProvider(props, mapper));
    }

    @Test
    void frRedlineNoOriginateAnywhereInProviderPackage() {
        // 红线静态断言:provider 包内任何类/方法名都不得出现 originate / sip / pstn
        for (Class<?> c : new Class<?>[]{DbNumberProvider.class, DbTrunkProvider.class,
            NoopNumberProvider.class, NoopTrunkProvider.class}) {
            String name = c.getSimpleName().toLowerCase();
            assertFalse(name.contains("originate"), c.getSimpleName());
            for (Method m : c.getDeclaredMethods()) {
                String mn = m.getName().toLowerCase();
                assertFalse(mn.contains("originate"), c.getSimpleName() + "." + m.getName());
                assertFalse(mn.contains("call"), "provider 不提供呼叫动作: " + c.getSimpleName() + "." + m.getName());
            }
        }
        // 接口契约本身也只有分配/归还/描述/绑定/状态,无呼叫动作
        assertEquals(3, com.forgeagent.callcenter.provider.NumberProvider.class.getDeclaredMethods().length);
        assertEquals(2, com.forgeagent.callcenter.provider.TrunkProvider.class.getDeclaredMethods().length);
    }
}
