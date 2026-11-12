package com.forgeagent.rag.domain.vo;

import io.github.linpeilie.annotations.AutoMapper;
import lombok.Data;
import com.forgeagent.rag.domain.AgentKnowledge;
import java.io.Serial;
import java.io.Serializable;
import java.util.Date;
/**
 * 知识库注册视图对象
 *
 * @author ForgeAgent
 */
@Data
@AutoMapper(target = AgentKnowledge.class)
public class AgentKnowledgeVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 主键
     */
    private Long id;

    /**
     * 所属工作空间ID(NULL表示全局/历史数据)
     */
    private Long workspaceId;

    /**
     * 知识库名称
     */
    private String name;

    /**
     * 知识库描述
     */
    private String description;

    /**
     * 状态(0正常 1停用)
     */
    private String status;

    /**
     * 嵌入模型
     */
    private String embeddingModel;

    /**
     * 重排序模型
     */
    private String rerankModel;

    /**
     * 相关度阈值(0~1,作用于向量相似度/精排分,为空不过滤)
     */
    private java.math.BigDecimal scoreThreshold;
/**
 * 精排相关度阈值(0~1,作用于 rerankScore;NULL 不过滤精排结果)
 */
private java.math.BigDecimal rerankThreshold;

    /**
     * 向量库后端
     */
    private String vectorBackend;

    /**
     * 向量维度
     */
    private Integer vectorDim;

    /**
     * 零召回兜底(R4-3):1=允许通用知识回答须声明(默认),0=严格模式一律拒答
     */
    private Integer fallbackAnswerEnabled;

    /**
     * 上传后需审核(R5-2)
     */
    private Integer reviewRequired;

    /**
     * 库级检索参数覆盖 JSON(R5-6)
     */
    private String retrievalOverrides;

    /**
     * R5-5 图片描述库级开关(1=开启)
     */
    private Integer vlmEnabled;

    /**
     * 分块大小(字符数)
     */
    private Integer chunkSize;

    /**
     * 分块重叠字符数
     */
    private Integer chunkOverlap;

    /**
     * 分块计价单位:char / token
     */
    private String chunkUnit;

    /**
     * 分块策略:structure / fixed
     */
    private String splitStrategy;

    /**
     * 清洗规则 JSON(NULL=默认)
     */
    private String cleanRules;

    /**
     * 配置过期文档数(分块/清洗口径变化后待差异重建的文档数;0=全部与当前配置一致)
     */
    private Long staleCount;

    /**
     * 父子分块开关(1=先切父块再切子块,检索命中子块注入父块全文)
     */
    private String parentChunkEnabled;

    /**
     * 父块目标大小(字符)
     */
    private Integer parentChunkSize;

    /**
     * 文档数量
     */
    private Integer docCount;

    /**
     * 备注
     */
    private String remark;

    /**
     * 创建时间
     */
    private Date createTime;

    /**
     * 更新时间
     */
    private Date updateTime;

}
