package com.forgeagent.memory.service.impl;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * D1 缺陷回归:写入范围必须按 agent + user 隔离,且「键池查询」与「冲突替代」口径一致。
 *
 * @author ForgeAgent
 */
@Tag("dev")
class MemoryScopeTest {

    @BeforeAll
    static void initTableInfo() {
        TableInfoTestSupport.initAgentMemory();
    }

    @Test
    void userScopedQueryHitsOnlyThatUser() {
        String sql = MemoryScope.factKeyQuery(7L, 42L).getSqlSegment();
        assertTrue(sql.contains("user_id"), "键池必须带用户过滤");
        assertTrue(sql.contains("ORDER BY"), "候选池必须显式排序,否则 limit 会取到最旧一批");
        assertFalse(sql.contains("user_id IS NULL"), "用户记忆不得把平台级记忆算进键池");
    }

    @Test
    void platformScopedQueryUsesIsNull() {
        String sql = MemoryScope.factKeyQuery(7L, null).getSqlSegment();
        assertTrue(sql.contains("user_id IS NULL"), "平台级写入只命中 userId 为空的行");
        assertFalse(sql.contains("user_id ="), "不得带具体用户值");
    }

    @Test
    void conflictUpdateMatchesFactKeyQueryScope() {
        String userUpdate = MemoryScope.conflictUpdate(7L, "pref", 42L).getSqlSegment();
        assertTrue(userUpdate.contains("user_id"));
        assertTrue(userUpdate.contains("fact_key"));
        assertFalse(userUpdate.contains("user_id IS NULL"));

        String platformUpdate = MemoryScope.conflictUpdate(7L, "pref", null).getSqlSegment();
        assertTrue(platformUpdate.contains("user_id IS NULL"));
        // 两处口径一致:同一 userId 下,查询与更新要么都带等值条件,要么都带 IS NULL
        assertTrue(MemoryScope.factKeyQuery(7L, null).getSqlSegment().contains("user_id IS NULL")
            == platformUpdate.contains("user_id IS NULL"));
    }
}
