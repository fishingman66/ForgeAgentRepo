package com.forgeagent.agent.domain.bo;

import lombok.Data;

/**
 * C4 指令自动生成入参:一句业务描述 → 结构化 system prompt 草稿。
 *
 * <p>workspaceId 用于模型可读性校验,不接受前端越界取用其它空间的模型。</p>
 */
@Data
public class PromptGenerateBo {

    /** 归属空间(必填) */
    private Long workspaceId;

    /** 生成所用模型(必填,须对 workspaceId 可读) */
    private Long modelId;

    /** Agent 名称 */
    private String agentName;

    /** 业务用途描述(必填) */
    private String purpose;

    /** 语气风格,如"专业简洁" */
    private String tone;

    /** 边界与禁止事项 */
    private String boundaries;

    /** 生成内容长度上限,默认 3000,最大 5000 */
    private Integer maxChars;
}
