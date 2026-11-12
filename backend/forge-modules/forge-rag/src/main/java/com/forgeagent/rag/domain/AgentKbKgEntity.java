package com.forgeagent.rag.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.Date;

/**
 * 知识库分块-实体映射 agent_kb_kg_entity(R5-3)
 *
 * <p>kg 模块只存通用图谱;这里记录"哪个分块提到哪个实体",用于图路命中后回落到来源分块。
 * node_key 与 kg_node.name_key 同口径({@code KgText.nameKey})。</p>
 *
 * @author ForgeAgent
 */
@Data
@TableName("agent_kb_kg_entity")
public class AgentKbKgEntity implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private Long knowledgeId;

    private Long documentId;

    private Long chunkId;

    private String nodeKey;

    private String nodeName;

    private Date createTime;
}
