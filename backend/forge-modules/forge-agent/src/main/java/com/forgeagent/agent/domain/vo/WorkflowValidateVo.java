package com.forgeagent.agent.domain.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * 工作流校验结果视图对象
 *
 * @author ForgeAgent
 */
@Data
public class WorkflowValidateVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 是否通过(无阻断项)
     */
    private Boolean valid;

    /**
     * 阻断项列表
     */
    private List<Issue> blockers;

    /**
     * 警告项列表
     */
    private List<Issue> warnings;

    @Data
    public static class Issue implements Serializable {
        @Serial
        private static final long serialVersionUID = 1L;
        private String nodeId;
        private String edgeId;
        private String message;
    }
}
