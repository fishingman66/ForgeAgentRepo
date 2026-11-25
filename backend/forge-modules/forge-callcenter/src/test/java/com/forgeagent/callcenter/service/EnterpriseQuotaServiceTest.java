package com.forgeagent.callcenter.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import com.forgeagent.callcenter.config.CallcenterProperties;
import com.forgeagent.callcenter.domain.CsEnterprise;
import com.forgeagent.callcenter.mapper.CsEnterpriseMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 企业域配额/到期门禁契约测试(P0-1)。
 *
 * <p>覆盖方案三项验收:①超配额拒绝 ②子企业继承父配额 ③跨企业空间零可见;
 * 外加零回退(默认关闭/无企业行)与到期/停用拦截。</p>
 */
@Tag("dev")
class EnterpriseQuotaServiceTest {

    private CsEnterpriseMapper mapper;
    private CallcenterProperties properties;
    private EnterpriseQuotaService service;

    @BeforeEach
    void setUp() {
        // MP lambda 查询需预热 TableInfo,否则纯 Mockito 下 TableInfoHelper 找不到表元数据
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), CsEnterprise.class);
        mapper = mock(CsEnterpriseMapper.class);
        properties = new CallcenterProperties();
        service = new EnterpriseQuotaService(mapper, properties);
    }

    private CsEnterprise enterprise(long id, long ws, String code, String path) {
        CsEnterprise e = new CsEnterprise();
        e.setId(id);
        e.setWorkspaceId(ws);
        e.setCode(code);
        e.setIdPath(path);
        e.setStatus("0");
        return e;
    }

    // ---------------- 零回退 ----------------

    @Test
    void disabledByDefaultNoDbQueryAndAllAllow() {
        assertFalse(service.enabled(), "默认关闭");
        assertTrue(service.checkSeat(1L, 999).allowed());
        assertTrue(service.checkChannel(1L, 999).allowed());
        assertTrue(service.checkConcurrent(1L, 999).allowed());
        assertTrue(service.checkExpiry(1L).allowed());
        verify(mapper, never()).selectOne(any());
        assertNull(service.effective(1L).enterpriseId());
    }

    @Test
    void enabledButNoEnterpriseRowAllows() {
        properties.setEnterpriseEnabled(true);
        when(mapper.selectOne(any())).thenReturn(null);
        assertTrue(service.checkSeat(7L, 999).allowed(), "无企业行按默认行为放行");
        assertEquals(30, service.effective(7L).seatLimit(), "走配置默认值");
    }

    // ---------------- ① 超配额拒绝 ----------------

    @Test
    void overQuotaDeniedAndUnderQuotaAllowed() {
        properties.setEnterpriseEnabled(true);
        CsEnterprise e = enterprise(1L, 10L, "ACME", "");
        e.setSeatLimit(3);
        e.setChannelLimit(2);
        e.setConcurrentLimit(5);
        when(mapper.selectOne(any())).thenReturn(e);

        assertFalse(service.checkSeat(10L, 3).allowed(), "current>=limit 拒绝");
        assertTrue(service.checkSeat(10L, 2).allowed(), "未达上限放行");
        assertFalse(service.checkChannel(10L, 2).allowed());
        assertFalse(service.checkConcurrent(10L, 5).allowed());
        verify(mapper, never()).selectList(any());
    }

    // ---------------- ② 子企业继承父配额 ----------------

    @Test
    void childInheritsNearestAncestorQuota() {
        properties.setEnterpriseEnabled(true);
        CsEnterprise root = enterprise(1L, 100L, "ROOT", "");
        root.setSeatLimit(50);
        root.setGroupLimit(9);
        CsEnterprise mid = enterprise(5L, 105L, "MID", "/1/");
        mid.setSeatLimit(20); // 近祖先覆盖远祖先
        CsEnterprise child = enterprise(9L, 109L, "CHILD", "/1/5/");
        child.setSeatLimit(null); // 自身空 -> 继承 mid=20

        when(mapper.selectOne(any())).thenReturn(child);
        when(mapper.selectList(any())).thenReturn(List.of(root, mid));

        assertEquals(20, service.effective(109L).seatLimit(), "取近祖先 MID 的 20");
        assertEquals(9, service.effective(109L).groupLimit(), "MID 为空则继续取 ROOT 的 9");
        assertTrue(service.checkSeat(109L, 20).allowed() == false, "继承上限 20 生效");
    }

    @Test
    void allAncestorsNullFallsBackToProperties() {
        properties.setEnterpriseEnabled(true);
        CsEnterprise child = enterprise(9L, 109L, "CHILD", "/1/");
        when(mapper.selectOne(any())).thenReturn(child);
        when(mapper.selectList(any())).thenReturn(List.of(enterprise(1L, 100L, "ROOT", "")));
        assertEquals(30, service.effective(109L).seatLimit());
    }

    // ---------------- ③ 跨企业空间零可见 ----------------

    @Test
    void crossWorkspaceQueryIsExactNoLeak() {
        properties.setEnterpriseEnabled(true);
        CsEnterprise own = enterprise(1L, 10L, "OWN", "");
        own.setSeatLimit(2);
        when(mapper.selectOne(any())).thenReturn(own);
        when(mapper.selectList(any())).thenReturn(List.of());

        assertEquals("OWN", service.effective(10L).code(), "本空间命中本企业");
        assertEquals(2, service.effective(10L).seatLimit());
        // mapper 按 workspace_id 精确查询:返回 null 时该企业不可见(由 mapper 契约保证)
        when(mapper.selectOne(any())).thenReturn(null);
        assertNull(service.findByWorkspace(999L), "其他空间查不到本企业行");
        assertTrue(service.checkSeat(999L, 999).allowed(), "其他空间不受本企业配额影响");
    }

    // ---------------- 到期/停用 ----------------

    @Test
    void expiredAndDisabledDeniedButHistoryReadUnaffected() {
        properties.setEnterpriseEnabled(true);
        CsEnterprise e = enterprise(1L, 10L, "ACME", "");
        e.setExpireTime(new Date(System.currentTimeMillis() - 86400000));
        when(mapper.selectOne(any())).thenReturn(e);
        assertFalse(service.checkExpiry(10L).allowed(), "过期拒绝写入");

        e.setExpireTime(null);
        e.setStatus("1");
        assertFalse(service.checkExpiry(10L).allowed(), "停用拒绝写入");

        e.setStatus("0");
        assertTrue(service.checkExpiry(10L).allowed());
    }
}
