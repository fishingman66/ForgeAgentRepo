package com.forgeagent.customerservice.domain.vo;

import io.github.linpeilie.annotations.AutoMapper;
import lombok.Data;
import com.forgeagent.customerservice.domain.CsKnowledgeGap;

import java.io.Serial;
import java.io.Serializable;
import java.util.Date;
/**
 * 知识盲区视图对象
 *
 * @author ForgeAgent
 */
@Data
@AutoMapper(target = CsKnowledgeGap.class)
public class CsKnowledgeGapVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long id;
    private Long workspaceId;
    private String questionHash;
    private String question;
    private String category;
    private String priority;
    private Integer hitCount;
    private String status;
    private Long answerId;
    private Date lastHitAt;
    private Date createTime;
    private Date updateTime;
}
