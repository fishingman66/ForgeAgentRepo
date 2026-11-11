package com.forgeagent.customerservice.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

/**
 * 客服死信(P2 收尾),对应表 {@code agent_cs_dead_letter}。
 *
 * <p>记录「业务动作失败但可重投」的场景:交接摘要生成失败、附件上传失败、坐席推荐生成失败。
 * 事件推送不在此列——推送走内存缓冲,失败只降级为不可续传,重投没有意义。</p>
 */
@Data
@TableName("agent_cs_dead_letter")
public class CsDeadLetter {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private Long workspaceId;
    private String channelCode;
    /** HANDOFF_SUMMARY / ATTACHMENT_UPLOAD / SEAT_ASSIST */
    private String eventType;
    private Long conversationId;
    private Long ticketId;
    private String payload;
    private String error;
    /** PENDING / REPLAYED / IGNORED / UNREPLAYABLE */
    private String status;
    private Integer retryCount;
    private Date lastRetryAt;
    private Date createTime;
    private Date updateTime;
}
