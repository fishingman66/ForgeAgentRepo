package com.forgeagent.agent.domain.vo;

import lombok.Data;
import org.apache.fesod.sheet.annotation.ExcelIgnoreUnannotated;
import org.apache.fesod.sheet.annotation.ExcelProperty;

import java.io.Serial;
import java.io.Serializable;
import java.util.Date;

/**
 * 消息反馈视图对象(UX-02 反馈管理列表/导出)
 *
 * @author ForgeAgent
 */
@Data
@ExcelIgnoreUnannotated
public class AgentFeedbackVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 消息ID
     */
    private Long messageId;

    /**
     * 会话ID
     */
    private Long sessionId;

    /**
     * Agent ID
     */
    private Long agentId;

    /**
     * Agent 名称
     */
    @ExcelProperty(value = "Agent")
    private String agentName;

    /**
     * 反馈人用户ID
     */
    private Long userId;

    /**
     * 反馈人昵称
     */
    @ExcelProperty(value = "反馈人")
    private String userName;

    /**
     * 反馈类型(like/dislike)
     */
    @ExcelProperty(value = "反馈类型")
    private String feedback;

    /**
     * 反馈标签(逗号分隔)
     */
    @ExcelProperty(value = "反馈标签")
    private String feedbackTags;

    /**
     * 反馈备注
     */
    @ExcelProperty(value = "反馈备注")
    private String feedbackComment;

    /**
     * 反馈时间
     */
    @ExcelProperty(value = "反馈时间")
    private Date feedbackTime;

    /**
     * 助手消息快照(截断 200 字)
     */
    @ExcelProperty(value = "消息快照")
    private String msgSnapshot;

    /**
     * 消息时间
     */
    private java.time.LocalDateTime msgTime;

}
