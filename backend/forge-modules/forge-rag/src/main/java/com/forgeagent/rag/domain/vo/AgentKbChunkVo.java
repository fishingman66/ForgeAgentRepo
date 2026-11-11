package com.forgeagent.rag.domain.vo;

import io.github.linpeilie.annotations.AutoMapper;
import lombok.Data;
import com.forgeagent.rag.domain.AgentKbChunk;
import java.io.Serial;
import java.io.Serializable;
import java.util.Date;
/**
 * 知识库分块视图对象
 *
 * @author ForgeAgent
 */
@Data
@AutoMapper(target = AgentKbChunk.class)
public class AgentKbChunkVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 主键
     */
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
     * 分块序号
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
     * 估算 token 数
     */
    private Integer tokenCount;

    /**
     * 累计检索命中次数
     */
    private Integer hitCount;

    /**
     * 来源定位标记(如 page=3/sheet=销售表)
     */
    private String pageLabel;

    /**
     * 所属章节路径(如 部署 > 数据库)
     */
    private String headingPath;

    /**
     * 分块代次(重建时递增)
     */
    private Integer generation;

    /**
     * 向量同步状态(PENDING/SYNCED/FAILED/SKIPPED)
     */
    private String vectorStatus;

    /**
     * 分块角色(c=检索子块 p=父块上下文块)
     */
    private String chunkRole;

    /**
     * 父块ID
     */
    private Long parentId;

    /**
     * 创建时间
     */
    private Date createTime;

}
