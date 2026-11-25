package com.forgeagent.callcenter.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.forgeagent.callcenter.config.CallcenterProperties;
import com.forgeagent.callcenter.domain.CsDidRoute;
import com.forgeagent.callcenter.mapper.CsDidRouteMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P2-2 DID 字冠路由契约测试:对齐参考项目 contains 语义 + 最长优先 + '*' 兜底 + 前缀变换。
 */
@Tag("dev")
class DidRouteResolverTest {

    private CsDidRouteMapper mapper;
    private CallcenterProperties properties;
    private DidRouteResolver resolver;

    @BeforeEach
    void setUp() {
        TableInfoHelper.initTableInfo(
            new MapperBuilderAssistant(new MybatisConfiguration(), ""), CsDidRoute.class);
        mapper = mock(CsDidRouteMapper.class);
        properties = new CallcenterProperties();
        resolver = new DidRouteResolver(mapper, properties);
    }

    private CsDidRoute rule(String routeNum, String target, String callerPrefix, String calledPrefix) {
        CsDidRoute r = new CsDidRoute();
        r.setRouteNum(routeNum);
        r.setTarget(target);
        r.setCallerPrefix(callerPrefix);
        r.setCalledPrefix(calledPrefix);
        r.setStatus("0");
        return r;
    }

    @Test
    void disabledByDefaultNoQueryAndNoTransform() {
        assertFalse(resolver.enabled());
        assertTrue(resolver.rules(1L).isEmpty());
        var d = resolver.resolve(1L, "13800001111", "02112345678");
        assertFalse(d.matched());
        assertEquals("13800001111", d.caller(), "关闭时号码原样");
        assertEquals("02112345678", d.callee());
        verify(mapper, never()).selectList(any());
    }

    @Test
    void containsSemanticsNotStartsWith() {
        // 参考 CacheService:289 called.contains(route),字冠出现在任意位置都命中
        List<CsDidRoute> rules = List.of(rule("87", "q1", null, null));
        assertEquals("q1", DidRouteResolver.match(rules, "13887000000").getTarget(),
            "87 出现在中间也命中(非 startsWith)");
        assertNull(DidRouteResolver.match(rules, "13800001111"));
    }

    @Test
    void longestRuleWinsEvenIfSqlReturnsUnsorted() {
        properties.setDidRouteEnabled(true);
        // SQL 乱序返回 [*, 87, 87000],服务须自行按长度降序 → 87000 命中
        List<CsDidRoute> unsorted = new ArrayList<>(List.of(
            rule("*", "fallback", null, null),
            rule("87", "short", null, null),
            rule("87000", "long", null, null)));
        when(mapper.selectList(any())).thenReturn(unsorted);
        var d = resolver.resolve(1L, "caller", "0218700012345");
        assertTrue(d.matched());
        assertEquals("long", d.target(), "最长字冠优先");
    }

    @Test
    void asteriskIsCatchAll() {
        List<CsDidRoute> rules = List.of(rule("*", "fallback", null, null));
        assertEquals("fallback", DidRouteResolver.match(rules, "999999").getTarget());
        // '*' 不是前缀通配:'*87' 不应被当通配
        assertNull(DidRouteResolver.match(List.of(rule("*87", "x", null, null)), "abc87def"));
    }

    @Test
    void prefixTransformUsesOriginalNumber() {
        assertEquals("008613800001111",
            DidRouteResolver.transform("0086", "13800001111"), "主叫前缀拼接");
        assertEquals("02112345678",
            DidRouteResolver.transform(null, "02112345678"), "空前缀原样");
        assertNull(DidRouteResolver.transform("0086", null), "空号码原样返回 null");

        // 完整链路:命中 87 → calledPrefix=9 → 脱敏前的原始被叫被变换
        properties.setDidRouteEnabled(true);
        when(mapper.selectList(any())).thenReturn(List.of(rule("87", "q", "0086", "9")));
        var d = resolver.resolve(1L, "13800001111", "13887000000");
        assertEquals("008613800001111", d.caller());
        assertEquals("913887000000", d.callee());
    }

    @Test
    void emptyConfigPassesThrough() {
        properties.setDidRouteEnabled(true);
        when(mapper.selectList(any())).thenReturn(List.of());
        var d = resolver.resolve(1L, "c", "v");
        assertFalse(d.matched());
        assertEquals("c", d.caller());
        assertEquals("v", d.callee());
        assertNull(d.target());
    }
}
