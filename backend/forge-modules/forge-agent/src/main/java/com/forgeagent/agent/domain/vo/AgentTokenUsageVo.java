package com.forgeagent.agent.domain.vo;

import io.github.linpeilie.annotations.AutoMapper;
import lombok.Data;
import org.apache.fesod.sheet.annotation.ExcelIgnoreUnannotated;
import org.apache.fesod.sheet.annotation.ExcelProperty;
import com.forgeagent.agent.domain.AgentTokenUsage;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * Token 用量明细视图对象(兼导出)
 *
 * @author ForgeAgent
 */
@Data
@AutoMapper(target = AgentTokenUsage.class)
@ExcelIgnoreUnannotated
public class AgentTokenUsageVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long id;

    @ExcelProperty("工作空间ID")
    private Long workspaceId;

    @ExcelProperty("Agent ID")
    private Long agentId;

    /**
     * Agent 名称(报表展示用)
     */
    @ExcelProperty("Agent名称")
    private String agentName;

    private Long sessionId;

    private String roundRequestId;

    private Long userId;

    /**
     * 发起人用户名(报表展示用)
     */
    @ExcelProperty("发起人")
    private String userName;

    @ExcelProperty("来源")
    private String source;

    private Long apiKeyId;

    private Long modelId;

    @ExcelProperty("模型")
    private String modelName;

    @ExcelProperty("输入Token")
    private Integer promptTokens;

    @ExcelProperty("输出Token")
    private Integer completionTokens;

    @ExcelProperty("总Token")
    private Integer totalTokens;

    @ExcelProperty("是否估算")
    private String estimated;

    @ExcelProperty("是否成功")
    private String success;

    @ExcelProperty("调用时间")
    private LocalDateTime callTime;

    private LocalDateTime createTime;

}
