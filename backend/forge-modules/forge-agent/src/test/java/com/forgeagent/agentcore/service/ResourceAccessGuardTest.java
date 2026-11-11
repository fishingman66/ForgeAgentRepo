package com.forgeagent.agentcore.service;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 资源域双模式可见性矩阵单测(纯函数,无需 Spring 上下文)。
 */
@Tag("dev")
class ResourceAccessGuardTest {

    private static final Long WS_A = 10L;
    private static final Long WS_B = 20L;

    @Test
    void sharedResourceReadableByAllSpaces() {
        assertTrue(ResourceAccessGuard.isReadable(null, Set.of(WS_A), false, true));
        assertTrue(ResourceAccessGuard.isReadable(null, Set.of(WS_B), false, true));
        assertTrue(ResourceAccessGuard.isReadable(null, Set.of(), false, true));
    }

    @Test
    void privateResourceOnlyVisibleToOwnerSpace() {
        assertTrue(ResourceAccessGuard.isReadable(WS_A, Set.of(WS_A, WS_B), false, true));
        assertFalse(ResourceAccessGuard.isReadable(WS_A, Set.of(WS_B), false, true));
        assertFalse(ResourceAccessGuard.isReadable(WS_A, null, false, true));
    }

    @Test
    void superAdminSeesEverything() {
        assertTrue(ResourceAccessGuard.isReadable(WS_B, Set.of(WS_A), true, true));
        assertTrue(ResourceAccessGuard.isWritable(null, Set.of(), true, true));
    }

    @Test
    void sharedResourceWritableOnlyBySuperAdmin() {
        assertFalse(ResourceAccessGuard.isWritable(null, Set.of(WS_A), false, true));
        assertTrue(ResourceAccessGuard.isWritable(null, Set.of(), true, true));
    }

    @Test
    void privateResourceWritableByOwnerDeveloper() {
        assertTrue(ResourceAccessGuard.isWritable(WS_A, Set.of(WS_A), false, true));
        assertFalse(ResourceAccessGuard.isWritable(WS_A, Set.of(WS_B), false, true));
    }

    @Test
    void sharingDisabledTightensToPrivateOnly() {
        // 关闭共享后 NULL 资源不再全空间可读
        assertFalse(ResourceAccessGuard.isReadable(null, Set.of(WS_A), false, false));
        assertFalse(ResourceAccessGuard.isWritable(null, Set.of(WS_A), false, false));
        // 私有资源归属语义不变
        assertTrue(ResourceAccessGuard.isReadable(WS_A, Set.of(WS_A), false, false));
        assertFalse(ResourceAccessGuard.isShared(null, false));
    }

    @Test
    void readableBySingleCallerSpaceForHeadlessLinks() {
        // 免登录链路(触发器/API/聊天后台)以工作流归属空间为上下文
        assertTrue(ResourceAccessGuard.isReadableBy(null, WS_A, true));
        assertTrue(ResourceAccessGuard.isReadableBy(WS_A, WS_A, true));
        assertFalse(ResourceAccessGuard.isReadableBy(WS_B, WS_A, true));
        assertFalse(ResourceAccessGuard.isReadableBy(null, WS_A, false));
    }

    @Test
    void privateResourceWithNullCallerNeverLeaks() {
        assertFalse(ResourceAccessGuard.isWritable(WS_A, null, false, true));
        assertFalse(ResourceAccessGuard.isReadableBy(WS_A, null, true));
    }
}
