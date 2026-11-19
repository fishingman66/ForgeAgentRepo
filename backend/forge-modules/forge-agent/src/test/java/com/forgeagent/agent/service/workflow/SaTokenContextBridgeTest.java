package com.forgeagent.agent.service.workflow;

import cn.dev33.satoken.context.SaHolder;
import cn.dev33.satoken.stp.StpUtil;
import com.forgeagent.common.satoken.utils.SaTokenContextBridge;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SaTokenContextBridge} 跨线程登录态传递的契约测试。
 *
 * <p>直接驱动 Sa-Token 真实上下文(不 mock StpUtil)——mockStatic 是线程局部的,
 * 无法验证子线程可见性。三条断言分别覆盖:子线程恢复、结束清理、免登录直跑。</p>
 */
@Tag("dev")
class SaTokenContextBridgeTest {

    private static final String KEY_JUST_CREATED = "JUST_CREATED_";

    @Test
    void callWithToken_carriesTokenIntoWorkerAndCleansUp() throws Exception {
        String token = "wf-login-token-123";
        AtomicReference<String> inTask = new AtomicReference<>();
        AtomicReference<String> afterTask = new AtomicReference<>();

        Thread worker = new Thread(() -> {
            SaTokenContextBridge.callWithToken(token, () -> {
                inTask.set(StpUtil.getTokenValue());
                return null;
            });
            afterTask.set(StpUtil.getTokenValue());
        });
        worker.start();
        worker.join();

        assertEquals(token, inTask.get(), "子线程应恢复主线程 token");
        assertNull(afterTask.get(), "任务结束后必须清理,防止线程池复用串号");
    }

    @Test
    void callWithToken_restoresBothStorageKeysInsideTask() throws Exception {
        // setTokenValue 会写 JUST_CREATED_ 与 JUST_CREATED_NOT_PREFIX_ 两个键;
        // 在任务内断言二者可见,证明 Sa-Token 上下文确实已初始化并恢复。
        // 任务后上下文被 clearContext 销毁,不能再读 storage —— 清理效果由
        // callWithToken_carriesTokenIntoWorkerAndCleansUp 的 afterTask==null 断言覆盖。
        String token = "wf-login-token-456";
        AtomicReference<Object> justCreated = new AtomicReference<>();
        AtomicReference<Object> notPrefix = new AtomicReference<>();

        Thread worker = new Thread(() -> SaTokenContextBridge.callWithToken(token, () -> {
            justCreated.set(SaHolder.getStorage().get(KEY_JUST_CREATED));
            notPrefix.set(SaHolder.getStorage().get("JUST_CREATED_NOT_PREFIX_"));
            return null;
        }));
        worker.start();
        worker.join();

        assertNotEquals(null, justCreated.get(), "JUST_CREATED_ 应在任务内可见");
        assertNotEquals(null, notPrefix.get(), "JUST_CREATED_NOT_PREFIX_ 应在任务内可见");
    }

    @Test
    void callWithToken_nullTokenRunsDirectly() {
        Boolean ok = SaTokenContextBridge.callWithToken(null, () -> Boolean.TRUE);
        assertTrue(Boolean.TRUE.equals(ok), "免登录链路(null token)应直接执行任务");
    }

    @Test
    void captureToken_returnsNullWithoutLogin() {
        // captureToken 内部 try/catch 了未初始化上下文，直接调用不应抛异常
        assertNull(SaTokenContextBridge.captureToken(), "无登录态应返回 null 而非抛异常");
    }
}
