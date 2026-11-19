package com.forgeagent.customerservice.domain.vo;

import io.github.linpeilie.annotations.AutoMapper;
import lombok.Data;
import com.forgeagent.customerservice.domain.CsCsat;

import java.io.Serial;
import java.io.Serializable;
import java.util.Date;
/**
 * 满意度评价视图对象
 *
 * @author ForgeAgent
 */
@Data
@AutoMapper(target = CsCsat.class)
public class CsCsatVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long id;
    private String channelCode;
    private String comment;
    private String tags;
    private Long conversationId;
    private Long workspaceId;
    private Integer score;
    private Date invitedAt;
    private Date submittedAt;
}
