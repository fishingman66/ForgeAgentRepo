package com.forgeagent.customerservice.domain.vo;

import io.github.linpeilie.annotations.AutoMapper;
import lombok.Data;
import com.forgeagent.customerservice.domain.CsQualityCheck;

import java.io.Serial;
import java.io.Serializable;
import java.util.Date;
/**
 * 质检结果视图对象
 *
 * @author ForgeAgent
 */
@Data
@AutoMapper(target = CsQualityCheck.class)
public class CsQualityCheckVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long id;
    private String hitRules;
    private String source;
    private String reviewStatus;
    private Long messageId;
    private Long ticketId;
    private Long workspaceId;
    private Integer score;
    private Date createTime;
}
