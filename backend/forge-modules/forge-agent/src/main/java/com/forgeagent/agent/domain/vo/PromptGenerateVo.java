package com.forgeagent.agent.domain.vo;

import lombok.Data;

import java.util.Map;

/**
 * C4 指令生成结果。
 *
 * <p>失败返回 {@code ok=false} 而非抛 500:模型超时/输出不可解析属于可预期分支,
 * 前端回退到手工编写即可。</p>
 */
@Data
public class PromptGenerateVo {

    private boolean ok;

    private String message;

    /** 拼装好的完整 system prompt(可直接写入 Agent 或发布为模板版本) */
    private String content;

    /** 结构化分段:role / task / constraints / outputFormat */
    private Map<String, Object> sections;

    private Integer estimatedTokens;

    private String modelName;
}
