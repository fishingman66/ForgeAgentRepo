package com.forgeagent.agentcore.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.Arrays;

/**
 * 工作空间内角色
 *
 * @author ForgeAgent
 */
@Getter
@AllArgsConstructor
public enum WorkspaceRole {

    /**
     * 空间管理员:空间设置、成员管理、资源全量操作
     */
    ADMIN("admin", "空间管理员"),

    /**
     * 开发者:创建/编辑/调试 Agent 与资源配置
     */
    DEVELOPER("developer", "开发者"),

    /**
     * 运行者:仅可使用已发布 Agent,只读查看
     */
    RUNNER("runner", "运行者");

    private final String code;
    private final String label;

    public static boolean isValid(String code) {
        return Arrays.stream(values()).anyMatch(r -> r.code.equals(code));
    }

    public static String labelOf(String code) {
        return Arrays.stream(values()).filter(r -> r.code.equals(code)).findFirst()
            .map(WorkspaceRole::getLabel).orElse(code);
    }
}
