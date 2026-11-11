package com.forgeagent.kg.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 图谱关系边 kg_edge(forge-kg / MySQL 存图实现)。
 *
 * @author ForgeAgent
 */
@Data
@TableName("kg_edge")
public class KgEdgeEntity implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private String scopeType;

    private Long scopeId;

    /** 用户ID;0 表示该范围共享级 */
    private Long userId;

    private String sourceKey;

    private String relation;

    private String targetKey;

    private BigDecimal weight;

    /** 溯源引用(记忆ID / 工单ID / FAQ ID) */
    private Long evidenceRef;

    private LocalDateTime lastSeen;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
