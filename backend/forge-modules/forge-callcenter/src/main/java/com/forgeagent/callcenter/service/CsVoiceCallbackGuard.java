package com.forgeagent.callcenter.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.forgeagent.callcenter.config.CallcenterProperties;
import com.forgeagent.callcenter.config.ConditionalOnCallcenterEnabled;
import com.forgeagent.callcenter.domain.CsCallChannelConfig;
import com.forgeagent.callcenter.mapper.CsCallChannelConfigMapper;
import com.forgeagent.common.core.exception.ServiceException;
import com.forgeagent.common.redis.utils.RedisUtils;
import com.forgeagent.customerservice.compliance.CsSecretCipher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RAtomicLong;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;

/**
 * 语音回调防护(V1):渠道可用性、时间窗、nonce 去重、密钥解析。
 *
 * <p>nonce 用 Redis 原子计数做 5 分钟去重,Redis 不可用时降级放行并告警
 * (与 C4 CsChannelGuard 同一口径,不让缓存故障打挂回调)。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnCallcenterEnabled
public class CsVoiceCallbackGuard {

    private static final String REDIS_PREFIX = "cc:";

    private final CsCallChannelConfigMapper channelMapper;
    private final CsSecretCipher secretCipher;
    private final CallcenterProperties properties;

    /** 渠道必须存在且启用;密钥按 CsSecretCipher 兼容存量明文。 */
    public CsCallChannelConfig require(String channelCode) {
        CsCallChannelConfig channel = channelMapper.selectOne(new LambdaQueryWrapper<CsCallChannelConfig>()
            .eq(CsCallChannelConfig::getChannelCode, channelCode).last("limit 1"));
        if (channel == null || !"0".equals(channel.getStatus())) {
            throw new ServiceException("语音渠道不可用");
        }
        return channel;
    }

    /** 渠道级共享密钥优先,为空回退全局配置;解密失败按无密钥处理。 */
    public String resolveSecret(CsCallChannelConfig channel) {
        String stored = channel.getCallbackSecret();
        if (stored == null || stored.isBlank()) {
            return properties.getCallbackSecret();
        }
        try {
            return secretCipher.decrypt(stored);
        } catch (Exception e) {
            log.warn("[CC] 渠道 {} 密钥解密失败,按存量明文处理", channel.getChannelCode());
            return stored;
        }
    }

    /** 回调时间窗:超出或非法时间戳一律拒绝。 */
    public boolean withinTolerance(String timestamp) {
        try {
            long ts = Long.parseLong(timestamp);
            return Math.abs(Instant.now().getEpochSecond() - ts)
                <= Math.max(30, properties.getCallbackToleranceSeconds());
        } catch (Exception e) {
            return false;
        }
    }

    /** nonce 去重:同一 nonce 5 分钟窗口内只放行一次;Redis 不可用降级放行。 */
    public void checkNonce(String channelCode, String nonce) {
        if (nonce == null || nonce.isBlank()) {
            throw new ServiceException("缺少 nonce");
        }
        try {
            RAtomicLong counter = RedisUtils.getClient()
                .getAtomicLong(REDIS_PREFIX + "nonce:" + channelCode + ":" + nonce);
            long value = counter.incrementAndGet();
            if (value == 1) {
                counter.expire(Duration.ofMinutes(5));
            }
            if (value > 1) {
                throw new ServiceException("重复的请求");
            }
        } catch (ServiceException e) {
            throw e;
        } catch (Exception e) {
            log.warn("[CC] Redis 计数不可用,nonce 降级放行: {}", e.getMessage());
        }
    }
}
