package com.forgeagent.callcenter.config;

import com.forgeagent.callcenter.provider.NoopNumberProvider;
import com.forgeagent.callcenter.provider.NoopTrunkProvider;
import com.forgeagent.callcenter.provider.NumberProvider;
import com.forgeagent.callcenter.provider.TrunkProvider;
import com.forgeagent.callcenter.storage.LocalFsRecordingStorage;
import com.forgeagent.callcenter.storage.LocalRecordingController;
import com.forgeagent.callcenter.storage.OssRecordingStorage;
import com.forgeagent.callcenter.storage.RecordingStorage;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 语音与呼叫中心模块装配入口(V1)。
 *
 * <p>{@code ForgeAgentApplication} 位于根包 {@code com.forgeagent},本模块
 * {@code com.forgeagent.callcenter.*} 的组件与 Mapper 已被通配扫描覆盖
 * (mybatis-plus.mapperPackage = com.forgeagent.**.mapper),无需额外 ComponentScan。</p>
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnCallcenterEnabled
@EnableConfigurationProperties(CallcenterProperties.class)
public class CallcenterConfiguration {

    /** D8:暂无号码与中继,V1 一律装配 noop;接真实 AICC 号码池时新增实现并按配置切换。 */
    @Bean
    public NumberProvider numberProvider() {
        return new NoopNumberProvider();
    }

    @Bean
    public TrunkProvider trunkProvider() {
        return new NoopTrunkProvider();
    }

    /** D6:录音存储,oss(生产,sys_oss_config) / local-fs(开发,无真实 OSS 也能跑通全链路)。 */
    @Bean
    public RecordingStorage recordingStorage(CallcenterProperties properties) {
        if ("local-fs".equalsIgnoreCase(properties.getRecordingStore())) {
            return new LocalFsRecordingStorage(properties);
        }
        return new OssRecordingStorage();
    }

    /** local-fs 模式下启用本模块签名下载端点;oss 模式走 OSS presign,不装配。 */
    @Bean
    @ConditionalOnProperty(prefix = "agent.callcenter", name = "recording-store", havingValue = "local-fs")
    public LocalRecordingController localRecordingController(LocalFsRecordingStorage storage) {
        return new LocalRecordingController(storage);
    }
}
