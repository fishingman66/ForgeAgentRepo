package com.forgeagent.customerservice.domain.vo;

import io.github.linpeilie.annotations.AutoMapper;
import lombok.Data;
import com.forgeagent.customerservice.domain.CsSensitiveWord;

import java.io.Serial;
import java.io.Serializable;
import java.util.Date;
/**
 * 敏感词视图对象
 *
 * @author ForgeAgent
 */
@Data
@AutoMapper(target = CsSensitiveWord.class)
public class CsSensitiveWordVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long id;
    private Long workspaceId;
    private String word;
    private String severity;
    private String remark;
    private String enabled;
    private Date createTime;
}
