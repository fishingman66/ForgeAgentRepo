package com.forgeagent.rag.domain.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * 知识库评测逐用例判定视图对象(运行结果明细行)
 *
 * @author ForgeAgent
 */
@Data
public class KbEvalCaseResultVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 用例ID
     */
    private Long caseId;

    /**
     * 评测问题
     */
    private String question;

    /**
     * 用例状态(ACTIVE / EVIDENCE_STALE / DEPRECATED)
     */
    private String status;

    /**
     * 是否旧口径用例(无证据标注)
     */
    private Boolean legacy;

    /**
     * 是否命中
     */
    private Boolean hit;

    /**
     * 首个完整命中时的名次(1 起;未命中为 null)
     */
    private Integer rank;

    /**
     * 命中用例的首中相关度分
     */
    private Double score;

    /**
     * 各条证据的覆盖度(0~1)
     */
    private List<Double> evidenceCoverages;

    /**
     * 本次检索是否降级
     */
    private Boolean degraded;

    /**
     * 期望命中文档名(legacy 口径)
     */
    private String expectedDocName;

    /**
     * 期望关键词(legacy 口径)
     */
    private String expectedKeywords;

}
