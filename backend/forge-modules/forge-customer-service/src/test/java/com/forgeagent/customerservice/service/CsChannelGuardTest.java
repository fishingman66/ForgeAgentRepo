package com.forgeagent.customerservice.service;

import com.forgeagent.common.core.exception.ServiceException;
import com.forgeagent.customerservice.config.CsModuleProperties;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/** C0:访客 HMAC 令牌的签发、校验与越权/篡改拒绝。 */
@Tag("dev")
class CsChannelGuardTest {

    private CsChannelGuard guard() {
        return new CsChannelGuard(null, new CsModuleProperties(), null);
    }

    @Test
    void tokenRoundTrip() {
        String token = guard().issueVisitorToken("demo", 42L);
        assertEquals(42L, guard().verifyVisitorToken(token, "demo"));
    }

    @Test
    void rejectsTokenFromAnotherChannel() {
        String token = guard().issueVisitorToken("demo", 42L);
        assertThrows(ServiceException.class, () -> guard().verifyVisitorToken(token, "other"));
    }

    @Test
    void rejectsTamperedAndGarbageToken() {
        String token = guard().issueVisitorToken("demo", 42L);
        assertThrows(ServiceException.class, () -> guard().verifyVisitorToken(token + "x", "demo"));
        assertThrows(ServiceException.class, () -> guard().verifyVisitorToken("not-a-token", "demo"));
    }

    @Test
    void nonceFirstAcceptedThenReplayedRejected() {
        CsChannelGuard g = spy(guard());
        AtomicLong seq = new AtomicLong();
        // A-4:隔离 Redis 静态客户端,只替 incrementStrict 返回原子计数序列,checkNonce 判定本身走真代码
        doAnswer(inv -> seq.getAndIncrement() == 0 ? 1L : 2L)
            .when(g).incrementStrict(anyString(), any(Duration.class));

        assertDoesNotThrow(() -> g.checkNonce("demo", "n1"));
        ServiceException ex = assertThrows(ServiceException.class, () -> g.checkNonce("demo", "n1"));
        assertEquals("重复的请求", ex.getMessage());
        assertThrows(ServiceException.class, () -> g.checkNonce("demo", "  "));

        ArgumentCaptor<String> keys = ArgumentCaptor.forClass(String.class);
        verify(g, times(2)).incrementStrict(keys.capture(), any(Duration.class));
        assertEquals("nonce:demo:n1", keys.getAllValues().get(0), "key 必须带渠道编码做隔离");
        assertEquals("nonce:demo:n1", keys.getAllValues().get(1));
        assertEquals(2L, seq.get(), "空 nonce 不应进入计数");
    }

    @Test
    void redisFailure_rateLimitDegradesButNonceFailsClosed() {
        CsChannelGuard g = spy(guard());
        doAnswer(inv -> { throw new RuntimeException("redis down"); })
            .when(g).doIncrement(anyString(), any(Duration.class));

        // 限流:Redis 挂时降级放行(可用性优先)
        assertEquals(1L, g.increment("rate:any:1", Duration.ofMinutes(1)));
        // nonce:Redis 挂时必须拒绝,防止重放
        assertThrows(ServiceException.class, () -> g.checkNonce("demo", "n1"),
            "nonce 去重在 Redis 故障时不可静默放行");
    }
}
