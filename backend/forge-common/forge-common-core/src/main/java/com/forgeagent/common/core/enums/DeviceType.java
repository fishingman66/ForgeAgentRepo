package com.forgeagent.common.core.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 登录设备类型枚举。
 *
 * @author Lion Li; 改造: 智造 ForgeAgent 2026-11
 */
@Getter
@AllArgsConstructor
public enum DeviceType {

    /**
     * pc端
     */
    PC("pc"),

    /**
     * app端
     */
    APP("app"),

    /**
     * 小程序端
     */
    XCX("xcx"),

    /**
     * 第三方社交登录平台
     */
    SOCIAL("social");

    /**
     * 设备标识
     */
    private final String device;
}
