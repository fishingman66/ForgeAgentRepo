package com.forgeagent.callcenter.engine;

import com.forgeagent.callcenter.config.CallcenterProperties;
import com.forgeagent.callcenter.domain.CsCall;
import com.forgeagent.callcenter.engine.ivr.IvrSessionDriver;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * FsIvrSessionCoordinator 单测(P3):开关判定、会话启动与挂机退出。
 * 用真实 MockMediaStreamProvider + stub driver(记录 hangupFlag 引用),不连真实 FS。
 */
@Tag("dev")
class FsIvrSessionCoordinatorTest {

    @SuppressWarnings("unchecked")
    private ObjectProvider<MediaStreamProvider> provider(MediaStreamProvider p) {
        ObjectProvider<MediaStreamProvider> mock = mock(ObjectProvider.class);
        when(mock.getIfAvailable()).thenReturn(p);
        return mock;
    }

    private CallcenterProperties properties(boolean autodrive) {
        CallcenterProperties p = new CallcenterProperties();
        p.setIvrAutodrive(autodrive);
        p.getFreeswitch().setIvrRouteCode("demo_inbound");
        return p;
    }

    private CsCall call(String callId) {
        CsCall c = new CsCall();
        c.setCallId(callId);
        c.setWorkspaceId(1L);
        c.setCallerNumber("13800000000");
        c.setCalleeNumber("1001");
        return c;
    }

    /** stub driver:记录传入的 hangupFlag,可由测试置位模拟挂机。 */
    static final class StubDriver extends IvrSessionDriver {
        AtomicBoolean lastFlag;
        volatile boolean block;

        StubDriver() {
            super(null, null, null, null, new CallcenterProperties(), null);
        }

        @Override
        public String drive(Long workspaceId, String routeCode, String callId, String caller,
                            String callee, MediaStream stream, ChannelControl channel,
                            AtomicBoolean hangupFlag) {
            this.lastFlag = hangupFlag;
            while (block) {
                try {
                    Thread.sleep(20);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return "interrupted";
                }
            }
            return "completed";
        }
    }

    @Test
    void skipsWhenAutodriveOff() {
        StubDriver driver = new StubDriver();
        FsIvrSessionCoordinator coordinator = new FsIvrSessionCoordinator(
            provider(new MockMediaStreamProvider()), driver, properties(false));

        coordinator.onAnswered("FS", call("CALL-1"), new MockChannelControl("CALL-1", 0));
        assertNull(driver.lastFlag);
    }

    @Test
    void skipsNonFsProvider() {
        StubDriver driver = new StubDriver();
        FsIvrSessionCoordinator coordinator = new FsIvrSessionCoordinator(
            provider(new MockMediaStreamProvider()), driver, properties(true));

        coordinator.onAnswered("AICC", call("CALL-2"), new MockChannelControl("CALL-2", 0));
        assertNull(driver.lastFlag);
    }

    @Test
    void drivesSessionAndExitsOnHangup() {
        StubDriver driver = new StubDriver();
        driver.block = true;
        FsIvrSessionCoordinator coordinator = new FsIvrSessionCoordinator(
            provider(new MockMediaStreamProvider()), driver, properties(true));
        MockChannelControl channel = new MockChannelControl("CALL-3", 0);

        coordinator.onAnswered("FS", call("CALL-3"), channel);
        // 异步启动:等待 flag 被记录
        org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(2))
            .until(() -> driver.lastFlag != null);
        assertNotNull(driver.lastFlag);

        coordinator.onEnded("CALL-3");
        assertTrue(driver.lastFlag.get(), "挂机后 hangupFlag 应置位");

        // 解除阻塞,会话自然结束
        driver.block = false;
        org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(2))
            .until(() -> !driver.block);
    }

    @Test
    void duplicateAnswerIgnored() {
        StubDriver driver = new StubDriver();
        driver.block = true;
        FsIvrSessionCoordinator coordinator = new FsIvrSessionCoordinator(
            provider(new MockMediaStreamProvider()), driver, properties(true));

        coordinator.onAnswered("FS", call("CALL-4"), new MockChannelControl("CALL-4", 0));
        org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(2))
            .until(() -> driver.lastFlag != null);
        // 重复接通事件:不重复提交会话
        coordinator.onAnswered("FS", call("CALL-4"), new MockChannelControl("CALL-4", 0));
        driver.block = false;
    }
}
