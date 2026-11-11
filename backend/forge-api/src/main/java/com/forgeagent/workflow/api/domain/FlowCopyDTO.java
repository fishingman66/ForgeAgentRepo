package com.forgeagent.workflow.api.domain;

/**
 * 抄送
 *
 * @param userId   抄送用户 ID
 * @param nickName 抄送用户昵称
 * @author may; 改造: 智造 ForgeAgent 2026-11
 */
public record FlowCopyDTO(
    Long userId,
    String nickName
) {
}
