package com.forgeagent.callcenter.provider;

/**
 * 中继扩展接口(V1,D8 暂无 SIP 中继,方案 v2 第十二章)。
 */
public interface TrunkProvider {

    /** 绑定号码与中继引用;noop 实现直接忽略。 */
    void bind(String number, String trunkRef);

    /** 中继状态描述,无中继时返回 noop 说明。 */
    String status();
}
