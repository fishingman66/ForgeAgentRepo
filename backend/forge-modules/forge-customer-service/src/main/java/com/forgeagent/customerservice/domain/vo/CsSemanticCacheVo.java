package com.forgeagent.customerservice.domain.vo;

import io.github.linpeilie.annotations.AutoMapper;
import lombok.Data;
import com.forgeagent.customerservice.domain.CsSemanticCache;

import java.io.Serial;
import java.io.Serializable;
import java.util.Date;
/**
 * 语义缓存视图对象
 *
 * @author ForgeAgent
 */
@Data
@AutoMapper(target = CsSemanticCache.class)
public class CsSemanticCacheVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long id;
    private Long workspaceId;
    private String channelCode;
    private String questionHash;
    private String question;
    private String answer;
    private String intent;
    private Integer hits;
    private Date createTime;
    private Date updateTime;
    private Date lastHitAt;
}
