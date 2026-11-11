package com.forgeagent.rag.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.io.Serial;
import java.io.Serializable;
import java.util.Date;
/**
 * 知识库文档分块对象 agent_kb_chunk(与 Milvus 向量按 id 一一对应)
 *
 * @author ForgeAgent
 */
@Data
@TableName("agent_kb_chunk")
public class AgentKbChunk implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 主键(同时作为 Milvus 向量主键)
     */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 所属知识库ID
     */
    private Long knowledgeId;

    /**
     * 所属文档ID
     */
    private Long documentId;

    /**
     * 分块序号(从 0 开始)
     */
    private Integer chunkIndex;

    /**
     * 分块文本内容
     */
    private String content;

    /**
     * 是否启用(1启用 0禁用,禁用不参与检索)
     */
    private Integer enabled;

    /**
     * 估算 token 数(中文按字、英文按词)
     */
    private Integer tokenCount;

    /**
     * 累计检索命中次数
     */
    private Integer hitCount;

    /**
     * 分块角色(c=检索子块 p=父块上下文块,父块不参与嵌入与召回)
     */
    private String chunkRole;

    /**
     * 父块ID(chunk_role=c 时指向所属父块)
     */
    private Long parentId;

    /**
     * 来源定位标记(如 page=3/sheet=销售表,取分块内首个结构标记;便于前端跳转原文位置)
     */
    private String pageLabel;

    /**
     * 页码(PDF/Excel 页标记解析出的数字;无结构标记为 null)
     */
    private Integer pageNo;

    /**
     * 工作表名(Excel)
     */
    private String sheetName;

    /**
     * 所属章节路径(如 `部署 > 数据库`);无 Markdown 标题时为 null
     */
    private String headingPath;

    /**
     * 分块代次:重建时递增,检索只认「文档当前生效代次」的分块。
     *
     * <p>这样重建可以先写新代、成功后再切指针,期间旧代仍可检索——不再是"先删后建"的检索空洞。</p>
     */
    private Integer generation;

    /**
     * 向量同步状态(PENDING/SYNCED/FAILED/SKIPPED)
     */
    private String vectorStatus;

    /**
     * 向量写入补偿重试次数
     */
    private Integer vectorRetryCount;

    /**
     * 子块在父块内的起始偏移(仅父子分块模式;用于精确剔除被禁用内容)
     */
    private Integer parentOffsetStart;

    /**
     * 子块在父块内的结束偏移
     */
    private Integer parentOffsetEnd;

    /**
     * 创建时间
     */
    private Date createTime;

    /**
     * 本地开发向量(float32 小端)。仅 {@code rag.knowledge.vector-store=mysql} 时写入。
     *
     * <p>标 {@code select=false} 让默认查询不带出 BLOB——分块列表动辄上千行,
     * 每行 1.5KB 向量会把响应撑爆。检索时用显式 select 取。</p>
     */
    @com.baomidou.mybatisplus.annotation.TableField(select = false)
    private byte[] embedding;

    /**
     * 向量维度:与当前 {@code vector-dim} 不一致的向量在检索时跳过,
     * 避免换 embedding 模型后拿到一堆长度不同的数组。
     */
    private Integer embeddingDim;

}
