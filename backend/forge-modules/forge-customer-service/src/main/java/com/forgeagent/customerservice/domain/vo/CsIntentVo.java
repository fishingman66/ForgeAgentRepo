package com.forgeagent.customerservice.domain.vo;

import io.github.linpeilie.annotations.AutoMapper;
import lombok.Data;
import com.forgeagent.customerservice.domain.CsIntent;

import java.io.Serial;
import java.io.Serializable;
import java.util.Date;
/**
 * 意图配置视图对象
 *
 * @author ForgeAgent
 */
@Data
@AutoMapper(target = CsIntent.class)
public class CsIntentVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long id;
    private Long workspaceId;
    private String intentCode;
    private String intentName;
    private String keywords;
    private String ruleType;
    private String kbIds;
    private Integer priority;
    private String enabled;
    private Date createTime;
    private Date updateTime;
}
