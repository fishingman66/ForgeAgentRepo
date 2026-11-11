package com.forgeagent.customerservice.domain;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 客服领域三元组展示行(P3 / M3 维护页用)。
 *
 * <p>{@code conflict=true} 表示同一实体对上存在反义关系(如「解决/导致」之外,
 * forge-kg 词表命中的「喜欢/不喜欢」类对),维护时应人工核对哪条是对的。</p>
 *
 * @author ForgeAgent
 */
@Data
public class CsKgRelationVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 关系行 ID,删除用 */
    private Long id;

    private String subject;

    private String relation;

    private String object;

    /** 权重(重复出现累加) */
    private BigDecimal weight;

    /** 是否与另一条关系说法相反 */
    private boolean conflict;
}
