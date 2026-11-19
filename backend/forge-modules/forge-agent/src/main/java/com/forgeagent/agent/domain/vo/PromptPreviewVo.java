package com.forgeagent.agent.domain.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * Prompt 编译预览结果视图对象
 *
 * @author ForgeAgent
 */
@Data
public class PromptPreviewVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 渲染后的模板文本 */
    private String content;

    /** 渲染结果 SHA-256(派生) */
    private String contentHash;

    /** 编译层链(如 TEMPLATE / AGENT_SUPPLEMENT) */
    private List<String> layers;

    /** 估算 token 数(派生,长度/4) */
    private Integer estimatedTokens;
}
