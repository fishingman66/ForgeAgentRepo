package com.forgeagent.snailjob;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * SnailJob Server 启动程序
 *
 * @author opensnail; 改造: 智造 ForgeAgent 2026-11
 * @date 2024-05-17
 */
@SpringBootApplication
public class SnailJobServerApplication {

    /**
     * SnailJob 服务启动入口。
     *
     * @param args 启动参数
     */
    public static void main(String[] args) {
        SpringApplication.run(com.aizuda.snailjob.server.SnailJobServerApplication.class, args);
    }

}
