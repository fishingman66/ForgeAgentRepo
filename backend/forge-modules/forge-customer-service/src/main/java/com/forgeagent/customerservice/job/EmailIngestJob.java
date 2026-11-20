package com.forgeagent.customerservice.job;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.forgeagent.common.core.utils.StringUtils;
import com.forgeagent.common.redis.utils.RedisUtils;
import com.forgeagent.customerservice.channel.MailIngestService;
import com.forgeagent.customerservice.config.ConditionalOnCustomerServiceEnabled;
import com.forgeagent.customerservice.config.CsModuleProperties;
import com.forgeagent.customerservice.domain.CsChannel;
import com.forgeagent.customerservice.mapper.CsChannelMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * P3 / B5:邮件渠道轮询任务。
 *
 * <p>三个守则:</p>
 * <ol>
 *   <li><b>默认关闭</b>({@code email-ingest-enabled=false}):没有邮件渠道的部署不该去连 IMAP;</li>
 *   <li><b>Redis 锁</b>按渠道粒度,多实例部署不会重复拉同一批邮件(即便重复,幂等键也兜底);</li>
 *   <li><b>异常不外抛</b>:单个渠道/单封邮件失败只记日志,任务本身不能被一次故障打死。</li>
 * </ol>
 *
 * @author ForgeAgent
 */
@Slf4j
@Component
@ConditionalOnCustomerServiceEnabled
@RequiredArgsConstructor
public class EmailIngestJob {

    private final CsChannelMapper channelMapper;
    private final MailIngestService mailIngestService;
    private final CsModuleProperties properties;

    @Scheduled(fixedDelayString = "${agent.customer-service.email-poll-interval-ms:60000}",
        initialDelayString = "${agent.customer-service.email-poll-initial-delay-ms:30000}")
    public void poll() {
        if (!Boolean.TRUE.equals(properties.getEmailIngestEnabled())) {
            return;
        }
        List<CsChannel> channels;
        try {
            channels = channelMapper.selectList(new LambdaQueryWrapper<CsChannel>()
                .eq(CsChannel::getChannelType, "email")
                .eq(CsChannel::getStatus, "0"));
        } catch (Exception e) {
            log.warn("[Mail] 查询邮件渠道失败: {}", e.getMessage());
            return;
        }
        if (channels == null || channels.isEmpty()) {
            return;
        }
        for (CsChannel channel : channels) {
            if (StringUtils.isBlank(channel.getChannelConfig())) {
                continue;
            }
            RLock lock = null;
            try {
                lock = RedisUtils.getClient().getLock("forge:cs:mail-ingest:" + channel.getId());
                // 不等待:拿不到说明别的实例正在处理,本轮跳过
                if (!lock.tryLock(0, 180, TimeUnit.SECONDS)) {
                    continue;
                }
                int handled = mailIngestService.pollChannel(channel);
                if (handled > 0) {
                    log.info("[Mail] 渠道 {} 处理邮件 {} 封", channel.getChannelCode(), handled);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                log.warn("[Mail] 渠道 {} 轮询失败: {}", channel.getChannelCode(), e.getMessage());
            } finally {
                if (lock != null && lock.isHeldByCurrentThread()) {
                    lock.unlock();
                }
            }
        }
    }
}
