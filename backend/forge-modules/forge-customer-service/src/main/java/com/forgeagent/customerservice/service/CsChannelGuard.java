package com.forgeagent.customerservice.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.forgeagent.common.core.exception.ServiceException;
import com.forgeagent.common.redis.utils.RedisUtils;
import com.forgeagent.customerservice.compliance.CsSecretCipher;
import com.forgeagent.customerservice.config.ConditionalOnCustomerServiceEnabled;
import com.forgeagent.customerservice.config.CsModuleProperties;
import com.forgeagent.customerservice.domain.CsChannel;
import com.forgeagent.customerservice.mapper.CsChannelMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RAtomicLong;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;

/**
 * 渠道守卫(C0/C4):渠道解析、Redis 多实例限流、nonce 去重、Origin 白名单、访客 HMAC 令牌。
 *
 * <p>C4 起限流改走 Redis 原子计数(key 带分钟数 + 2 分钟 TTL),多实例共享同一计数;
 * Redis 不可用时降级放行并告警,不阻断对话。渠道密钥在库内加密,读取时解密到内存对象,
 * 不回写数据库,也不出现在日志里。</p>
 *
 * <p>扩展方向:限流维度加访客级 / 坐席级令牌桶;nonce 去重窗口可配;
 * 新渠道(小程序 / 视频)接入时只需要新增适配器并复用本类验签与限流口径。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnCustomerServiceEnabled
public class CsChannelGuard {

    private static final String REDIS_PREFIX = "forge:cs:";

    private final CsChannelMapper channelMapper;
    private final CsModuleProperties properties;
    private final CsSecretCipher secretCipher;

    public CsChannel require(String channelCode) {
        CsChannel channel = channelMapper.selectOne(new LambdaQueryWrapper<CsChannel>()
            .eq(CsChannel::getChannelCode, channelCode).last("limit 1"));
        if (channel == null || !"0".equals(channel.getStatus())) {
            throw new ServiceException("渠道不可用");
        }
        if (secretCipher != null && channel.getChannelSecret() != null) {
            channel.setChannelSecret(secretCipher.decrypt(channel.getChannelSecret()));
        }
        return channel;
    }

    /** 鉴权前 IP 预限流 + 鉴权后端点级限流,失败尝试同样计数。 */
    public void checkRateLimit(CsChannel channel, String clientIp) {
        String ip = clientIp == null ? "unknown" : clientIp;
        int ipLimit = Math.max(60, (channel.getRateLimit() == null ? 60 : channel.getRateLimit()) * 2);
        check("rate:ip:" + channel.getChannelCode() + ":" + ip, ipLimit);
    }

    public void checkQuota(CsChannel channel) {
        int limit = channel.getRateLimit() == null
            ? properties.getChannelRateLimitPerMinute() : channel.getRateLimit();
        check("rate:ch:" + channel.getChannelCode(), limit);
    }

    private void check(String key, int limit) {
        long minute = Instant.now().getEpochSecond() / 60;
        if (increment(key + ":" + minute, Duration.ofMinutes(2)) > limit) {
            throw new ServiceException("请求过于频繁,请稍后再试");
        }
    }

    /** 渠道回调 nonce 去重:同一 nonce 在 5 分钟窗口内只允许一次。 */
    public void checkNonce(String channelCode, String nonce) {
        if (nonce == null || nonce.isBlank()) {
            throw new ServiceException("缺少 nonce");
        }
        if (increment("nonce:" + channelCode + ":" + nonce, Duration.ofMinutes(5)) > 1) {
            throw new ServiceException("重复的请求");
        }
    }

    /** 跨域挂件白名单:未配置白名单或无 Origin(服务端调用)时放行。 */
    public void checkOrigin(CsChannel channel, String origin) {
        String allow = channel.getCorsAllowlist();
        if (allow == null || allow.isBlank() || origin == null || origin.isBlank()) {
            return;
        }
        String host;
        try {
            host = java.net.URI.create(origin).getHost();
        } catch (Exception e) {
            throw new ServiceException("请求来源不合法");
        }
        if (host == null) {
            host = origin;
        }
        for (String item : allow.split("[,;]")) {
            String rule = item.trim().toLowerCase();
            if (!rule.isEmpty()
                && (host.equalsIgnoreCase(rule) || host.toLowerCase().endsWith("." + rule))) {
                return;
            }
        }
        throw new ServiceException("请求来源不在白名单内");
    }

    private long increment(String key, Duration ttl) {
        try {
            RAtomicLong counter = RedisUtils.getClient().getAtomicLong(REDIS_PREFIX + key);
            long value = counter.incrementAndGet();
            if (value == 1) {
                counter.expire(ttl);
            }
            return value;
        } catch (Exception e) {
            log.warn("[CS] Redis 计数不可用,降级放行 key={}: {}", key, e.getMessage());
            return 1L;
        }
    }

    /** 签发访客令牌:base64(channelCode|visitorId|exp|hmac)。 */
    public String issueVisitorToken(String channelCode, Long visitorId) {
        long exp = Instant.now().getEpochSecond() + properties.getVisitorTokenTtlSeconds();
        String body = channelCode + "|" + visitorId + "|" + exp;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
            (body + "|" + hmac(body)).getBytes(StandardCharsets.UTF_8));
    }

    /** 校验并返回 visitorId;渠道编码不匹配、过期或签名错误一律拒绝。 */
    public Long verifyVisitorToken(String token, String channelCode) {
        try {
            String raw = new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8);
            String[] parts = raw.split("\\|", 4);
            if (parts.length != 4 || !channelCode.equals(parts[0])) {
                throw new ServiceException("访客令牌无效");
            }
            if (!hmac(channelCode + "|" + parts[1] + "|" + parts[2]).equals(parts[3])) {
                throw new ServiceException("访客令牌无效");
            }
            if (Long.parseLong(parts[2]) < Instant.now().getEpochSecond()) {
                throw new ServiceException("访客令牌已过期");
            }
            return Long.parseLong(parts[1]);
        } catch (ServiceException e) {
            throw e;
        } catch (Exception e) {
            throw new ServiceException("访客令牌无效");
        }
    }

    private String hmac(String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(properties.getVisitorTokenSecret().getBytes(StandardCharsets.UTF_8),
                "HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("访客令牌签名失败", e);
        }
    }
}
