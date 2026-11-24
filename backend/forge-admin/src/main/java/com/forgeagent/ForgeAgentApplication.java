package com.forgeagent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.metrics.buffering.BufferingApplicationStartup;

/**
 * 启动程序
 *
 * @author Lion Li; 改造: 智造 ForgeAgent 2026-11
 */

@SpringBootApplication
public class ForgeAgentApplication {

    /**
     * 应用启动入口。
     *
     * @param args 启动参数
     */
    public static void main(String[] args) {
        // 必须在 SpringApplication.run 之前：prod 下出厂默认凭据直接 fail-fast，
        // 先于数据源等任何 Bean 创建，避免用错密码的 DB 连接异常掩盖真正的问题。
        String activeProfile = System.getProperty("spring.profiles.active");
        if (activeProfile == null || activeProfile.isBlank()) {
            activeProfile = System.getenv("SPRING_PROFILES_ACTIVE");
        }
        com.forgeagent.web.ProductionCredentialsGuard.validate(activeProfile);
        SpringApplication application = new SpringApplication(ForgeAgentApplication.class);
        application.setApplicationStartup(new BufferingApplicationStartup(2048));
        application.run(args);
        System.out.println("(♥◠‿◠)ﾉﾞ  ForgeAgent启动成功   ლ(´ڡ`ლ)ﾞ");
    }

}
