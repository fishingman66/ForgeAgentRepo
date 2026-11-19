package com.forgeagent.rag.domain.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * 知识库评测完整指标视图对象(overall + 各判定分组)
 *
 * @author ForgeAgent
 */
@Data
public class KbEvalMetricsVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 全量指标
     */
    private KbEvalGroupMetricsVo overall;

    /**
     * 各分组指标(空组已省略:span / document / legacy)
     */
    private List<KbEvalGroupMetricsVo> groups;

}
