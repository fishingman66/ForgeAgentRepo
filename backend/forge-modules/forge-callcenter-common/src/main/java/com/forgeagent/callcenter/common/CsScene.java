package com.forgeagent.callcenter.common;

/**
 * 会话场景取值(V0.5)。集中定义,避免三处字符串字面量各自维护。
 *
 * <p>CS 与 S0~C4 已交付的 {@code agent_session.scene='cs'} 保持一致;
 * V1 起语音域使用 VOICE,控制台使用 CONSOLE。
 * 扩展方向:外呼营销、质检复核等新场景加枚举值即可,fromCode 对未知取值返回 null 由调用方兜底。</p>
 */
public enum CsScene {

    CONSOLE("console"),
    CS("cs"),
    VOICE("voice");

    private final String code;

    CsScene(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }

    /** 未知取值返回 null,由调用方决定兜底;大小写不敏感。 */
    public static CsScene fromCode(String code) {
        if (code == null) {
            return null;
        }
        for (CsScene scene : values()) {
            if (scene.code.equalsIgnoreCase(code)) {
                return scene;
            }
        }
        return null;
    }
}
