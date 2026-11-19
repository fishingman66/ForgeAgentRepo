package com.forgeagent.rag.domain.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 知识库重建门禁判决视图对象(R3-4)
 *
 * @author ForgeAgent
 */
@Data
public class KbEvalGateVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 判决状态:PASSED / BLOCKED / LOG_ONLY
     */
    private String state;

    /**
     * 判决原因
     */
    private String reason;

    /**
     * 实际使用的容差 ε
     */
    private Double epsilon;

    /**
     * 候选侧相对基线的指标变化量
     */
    private Double delta;

    /**
     * 判决的可读描述(拦截时用于提示)
     */
    private String description;

}
