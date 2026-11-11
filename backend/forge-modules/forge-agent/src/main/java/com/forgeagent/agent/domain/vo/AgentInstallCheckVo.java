package com.forgeagent.agent.domain.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * 市场安装依赖检查结果
 *
 * @author ForgeAgent
 */
@Data
public class AgentInstallCheckVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 模型依赖是否满足(不满足则禁止安装)
     */
    private Boolean modelOk;

    /**
     * 模型配置名称
     */
    private String modelName;

    /**
     * 模型检查说明
     */
    private String modelMessage;

    /**
     * 工具依赖检查项
     */
    private List<DepItem> tools;

    /**
     * 知识库依赖检查项
     */
    private List<DepItem> knowledges;

    /**
     * 是否可安装(模型满足即可,工具/知识库缺失项将被跳过)
     */
    private Boolean installable;

    @Data
    public static class DepItem implements Serializable {
        @Serial
        private static final long serialVersionUID = 1L;
        private Long id;
        private String name;
        /**
         * 资源是否存在
         */
        private Boolean found;
        /**
         * 是否可用(0正常)
         */
        private Boolean enabled;
    }
}
