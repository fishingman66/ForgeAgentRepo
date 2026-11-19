package com.forgeagent.common.satoken.utils;

import cn.dev33.satoken.SaManager;
import cn.dev33.satoken.context.SaHolder;
import cn.dev33.satoken.context.SaTokenContextForThreadLocal;
import cn.dev33.satoken.context.mock.SaRequestForMock;
import cn.dev33.satoken.context.mock.SaResponseForMock;
import cn.dev33.satoken.context.mock.SaStorageForMock;
import cn.dev33.satoken.exception.SaTokenContextException;
import cn.dev33.satoken.stp.StpUtil;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import java.util.function.Supplier;

/**
 * Sa-Token 上下文跨线程传递桥。
 *
 * <p>Sa-Token 的登录态存在线程局部的 {@code SaStorage} 中，业务自建线程池的新线程
 * 既没有上下文 ModelBox、也没有 token，导致 {@link LoginHelper#getUserId()} 静默返回
 * null（{@code StpUtil.getExtra} 抛异常被吞）。本工具在主线程快照 token；子线程先用
 * Sa-Token 官方的非 Web 上下文模型（mock request/response/storage）初始化 ThreadLocal，
 * 再 {@code StpUtil.setTokenValue} 恢复 token；finally 清理 key 并清空上下文，避免
 * 有界线程池复用线程时串号。</p>
 *
 * <p>key 取自 Sa-Token 1.46.0 {@code StpLogic#setTokenValueToStorage}：无前缀写
 * {@code JUST_CREATED_}，始终写 {@code JUST_CREATED_NOT_PREFIX_}；另清理活跃超时检查缓存。
 * 仅改当前线程 Storage，不写服务端会话、不重新 login、不写 Cookie/Header。</p>
 *
 * @author ForgeAgent
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class SaTokenContextBridge {

    private static final String KEY_JUST_CREATED = "JUST_CREATED_";
    private static final String KEY_JUST_CREATED_NOT_PREFIX = "JUST_CREATED_NOT_PREFIX_";
    private static final String KEY_ACTIVE_TIMEOUT_CHECKED = "TOKEN_ACTIVE_TIMEOUT_CHECKED_KEY_";

    /**
     * 主线程快照当前 token；未登录或无上下文返回 null（后台/触发器免登录链路本就没有登录态）。
     */
    public static String captureToken() {
        try {
            return StpUtil.getTokenValue();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 子线程恢复 token 后执行任务，结束必清理当前线程 Storage 与上下文。
     *
     * @param token  主线程快照的 token，null/空表示免登录链路，直接执行不做上下文变更
     * @param action 待执行任务
     */
    public static <T> T callWithToken(String token, Supplier<T> action) {
        if (token == null || token.isEmpty()) {
            return action.get();
        }
        boolean initialized = ensureContext();
        try {
            StpUtil.setTokenValue(token);
            return action.get();
        } finally {
            SaHolder.getStorage().delete(KEY_JUST_CREATED);
            SaHolder.getStorage().delete(KEY_JUST_CREATED_NOT_PREFIX);
            SaHolder.getStorage().delete(KEY_ACTIVE_TIMEOUT_CHECKED);
            if (initialized) {
                clearContext();
            }
        }
    }

    /**
     * 确保当前线程有可用的 Sa-Token 上下文；已初始化（如 Web 请求线程）则不重复创建。
     *
     * @return true 表示本次由本方法初始化，调用方负责收尾清理
     */
    private static boolean ensureContext() {
        if (contextValid()) {
            return false;
        }
        if (SaManager.getSaTokenContext() instanceof SaTokenContextForThreadLocal ctx) {
            ctx.setContext(new SaRequestForMock(), new SaResponseForMock(), new SaStorageForMock());
            return true;
        }
        // 上下文实现不是 ThreadLocal 型（例如自定义 Servlet 上下文），无法在裸线程初始化。
        // 交由调用方的异常处理兜底：任务本身可能因无登录态降级，但不在这里伪造上下文。
        return false;
    }

    private static boolean contextValid() {
        try {
            SaHolder.getStorage();
            return true;
        } catch (SaTokenContextException e) {
            return false;
        }
    }

    private static void clearContext() {
        try {
            if (SaManager.getSaTokenContext() instanceof SaTokenContextForThreadLocal ctx) {
                ctx.clearContext();
            }
        } catch (Exception ignored) {
            // 清理失败不影响业务结果；线程池线程下次任务会再次 ensureContext 重建
        }
    }
}
