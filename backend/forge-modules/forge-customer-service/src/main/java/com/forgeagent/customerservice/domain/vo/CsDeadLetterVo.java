package com.forgeagent.customerservice.domain.vo;

import io.github.linpeilie.annotations.AutoMapper;
import lombok.Data;
import com.forgeagent.customerservice.domain.CsDeadLetter;

import java.io.Serial;
import java.io.Serializable;
import java.util.Date;
/**
 * 死信视图对象
 *
 * @author ForgeAgent
 */
@Data
@AutoMapper(target = CsDeadLetter.class)
public class CsDeadLetterVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long id;
    private Long workspaceId;
    private String channelCode;
    private String eventType;
    private Long conversationId;
    private Long ticketId;
    private String payload;
    private String error;
    private String status;
    private Integer retryCount;
    private Date lastRetryAt;
    private Date createTime;
    private Date updateTime;
}
