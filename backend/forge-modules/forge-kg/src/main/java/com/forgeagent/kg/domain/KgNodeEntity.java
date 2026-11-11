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
 * 图谱实体节点 kg_node(forge-kg / MySQL 存图实现)。
 *
 * <p>范围用通用三元组 (scope_type, scope_id, user_id) 表达,不绑定 agent_id:
 * 记忆域传 agent,客服领域可传 workspace/channel。</p>
 *
 * @author ForgeAgent
 */
@Data
@TableName("kg_node")
public class KgNodeEntity implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 范围类型 agent/workspace/channel */
    private String scopeType;

    /** 范围ID */
    private Long scopeId;

    /** 用户ID;0 表示该范围共享级 */
    private Long userId;

    /** 实体归一化键,唯一键成员 */
    private String nameKey;

    /** 展示名 */
    private String name;

    /** 实体类型 entity/person/project/tool/product */
    private String nodeType;

    private BigDecimal salience;

    private LocalDateTime lastSeen;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
