package com.forgeagent.customerservice.domain.vo;

import io.github.linpeilie.annotations.AutoMapper;
import lombok.Data;
import com.forgeagent.customerservice.domain.CsBadcase;

import java.io.Serial;
import java.io.Serializable;
import java.util.Date;
/**
 * badcase 视图对象
 *
 * @author ForgeAgent
 */
@Data
@AutoMapper(target = CsBadcase.class)
public class CsBadcaseVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long id;
    private Long workspaceId;
    private String source;
    private Long conversationId;
    private Long ticketId;
    private Long suggestionId;
    private String question;
    private String answer;
    private String status;
    private String operator;
    private String note;
    private String exported;
    private Date createTime;
    private Date updateTime;
}
