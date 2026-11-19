package com.forgeagent.agent.domain.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.Map;

/**
 * 两版配置逐字段对比结果视图对象
 *
 * @author ForgeAgent
 */
@Data
public class VersionDiffVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private int from;

    private int to;

    /** 变更字段数(派生) */
    private int changedFields;

    /** key=快照字段名,value=变更前后值 */
    private Map<String, FieldChange> changes;

    /**
     * 单字段变更(skillRefs 已人类可读化为"名称 vN"串)
     */
    @Data
    public static class FieldChange implements Serializable {

        @Serial
        private static final long serialVersionUID = 1L;

        private String from;

        private String to;
    }
}
