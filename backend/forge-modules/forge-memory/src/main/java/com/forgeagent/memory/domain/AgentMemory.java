package com.forgeagent.memory.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import com.forgeagent.common.mybatis.core.domain.BaseEntity;

import java.io.Serial;

/**
 * Agent 长期记忆对象 agent_memory
 *
 * <p>跨会话沉淀的用户偏好与事实,按 Agent+用户 维度存储;
 * 开启记忆的 Agent 对话时自动注入,并在对话后异步提取新记忆。</p>
 *
 * @author ForgeAgent
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("agent_memory")
public class AgentMemory extends BaseEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 主键
     */
    @TableId(value = "id")
    private Long id;

    /**
     * AgentID
     */
    private Long agentId;

    /**
     * 工作空间ID
     */
    private Long workspaceId;

    /**
     * 用户ID
     */
    private Long userId;

    /**
     * 记忆内容
     */
    private String content;

    /**
     * P3 / M2':float32 小端向量(MySQL BLOB)。
     * 不进默认 SELECT,只在向量检索时显式取,避免分页列表被 1.5KB/行 撑爆
     */
    @com.baomidou.mybatisplus.annotation.TableField(select = false)
    private byte[] embedding;

    /** 向量维度,与当前 embedding 模型不一致时跳过 */
    private Integer embeddingDim;

    /** 向量化模型标识,换模型后旧向量失效 */
    private String embeddingModel;

    /**
     * 事实归一化键(P2 / C3:同一事实再次出现时覆盖旧值,不产生重复记忆)
     */
    private String factKey;

    /**
     * 重要性 1-10(参与注入排序)
     */
    private Integer importance;

    /**
     * 注入命中次数(参与注入排序)
     */
    private Integer hitCount;

    /**
     * 最近一次注入时间
     */
    private java.time.LocalDateTime lastHitAt;

    /**
     * P3 / M4:TTL 过期时间(NULL=永久);注入过滤 + 治理任务归档
     */
    private java.util.Date expireAt;

    /**
     * 来源会话ID(手动添加为空)
     */
    private Long sourceSessionId;

    /**
     * 删除标志(0存在 其他删除)
     */
    @TableLogic
    private Long delFlag;

}
