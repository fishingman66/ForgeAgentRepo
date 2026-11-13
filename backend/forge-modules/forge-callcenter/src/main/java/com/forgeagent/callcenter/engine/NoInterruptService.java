package com.forgeagent.callcenter.engine;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.forgeagent.callcenter.config.CallcenterProperties;
import com.forgeagent.callcenter.domain.CsVoiceNoInterrupt;
import com.forgeagent.callcenter.mapper.CsVoiceNoInterruptMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 不允许打断词组服务(C2):DB 表 agent_cc_voice_no_interrupt(+ properties 兜底词表)
 * 组装 {@link NoInterruptMatcher};60s TTL 缓存。
 *
 * <p>mapper 缺失(MyBatis 未就绪/单测)时退化为 properties 兜底词表——
 * barge-in 豁免是体验优化,任何装配失败都不能影响通话主链路。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NoInterruptService {

    private static final long CACHE_TTL_MS = 60_000L;

    private final CallcenterProperties properties;
    private final ObjectProvider<CsVoiceNoInterruptMapper> mapperProvider;
    private volatile NoInterruptMatcher cached;
    private volatile long loadedAt;

    /** 取当前匹配器;缓存 60s,DB 不可达时退化为配置兜底词表。 */
    public NoInterruptMatcher matcher() {
        long now = System.currentTimeMillis();
        NoInterruptMatcher snapshot = cached;
        if (snapshot != null && now - loadedAt < CACHE_TTL_MS) {
            return snapshot;
        }
        synchronized (this) {
            snapshot = cached;
            if (snapshot != null && now - loadedAt < CACHE_TTL_MS) {
                return snapshot;
            }
            List<String> exact = new ArrayList<>(properties.getNoInterruptWords());
            List<String> fuzzy = new ArrayList<>();
            CsVoiceNoInterruptMapper mapper = mapperProvider.getIfAvailable();
            if (mapper != null) {
                try {
                    List<CsVoiceNoInterrupt> rows = mapper.selectList(new LambdaQueryWrapper<CsVoiceNoInterrupt>()
                        .eq(CsVoiceNoInterrupt::getEnabled, "1")
                        .eq(CsVoiceNoInterrupt::getDelFlag, 0L));
                    for (CsVoiceNoInterrupt row : rows) {
                        if ("exact".equalsIgnoreCase(row.getMatchMode())) {
                            exact.add(row.getWord());
                        } else {
                            fuzzy.add(row.getWord());
                        }
                    }
                } catch (Exception e) {
                    // 词表查询失败只影响豁免精度,不影响链路:降级配置兜底
                    log.debug("[CC] 不打断词表查询降级: {}", e.getMessage());
                }
            }
            snapshot = new NoInterruptMatcher(exact, fuzzy);
            cached = snapshot;
            loadedAt = now;
            return snapshot;
        }
    }
}
