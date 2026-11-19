package com.forgeagent.customerservice.domain.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 评测回流采样配置视图对象(CsEvalFeedbackService.sampleInfo)
 *
 * @author ForgeAgent
 */
@Data
public class CsEvalSampleInfoVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 采样率(0-1) */
    private Double sampleRate;

    /** 最小访客轮数门槛 */
    private Integer minVisitorTurns;

    private String datasetName;
}
